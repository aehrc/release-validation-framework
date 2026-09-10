package org.ihtsdo.rvf.core.service;

import com.google.gson.stream.JsonReader;
import com.google.gson.Gson;
import com.google.gson.stream.JsonToken;
import org.ihtsdo.rvf.core.service.config.ValidationJobResourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.stream.Stream;

/**
 * Lists the validation runs held in the job store.
 *
 * <p>Everything needed to open a report - the run id and the storage location -
 * is already on disk, but nothing exposed it, so a caller had to have written
 * both down when the run was submitted. This walks the store instead.
 *
 * <p>The store layout is set by {@link ValidationReportService}: a run occupies
 * one directory, and inside it {@code rvf/state.txt} holds the state and
 * {@code rvf/results.json} the report. A directory without {@code state.txt} is
 * not a run and is skipped, which covers the {@code files_to_validate}
 * directories that hold uploaded releases.
 */
@Service
public class ValidationRunCatalogue {

	private static final Logger LOGGER = LoggerFactory.getLogger(ValidationRunCatalogue.class);

	private static final String STATE = "rvf/state.txt";
	private static final String RESULTS = "rvf/results.json";
	private static final String PROGRESS = "rvf/progress.txt";
	private static final String SUBMITTED = "rvf/submitted.txt";
	private static final String SUMMARY = "rvf/summary.json";

	@Autowired
	private ValidationJobResourceConfig jobResourceConfig;

	/**
	 * One row of the list. Any field except the storage location may be absent:
	 * a run that is still queued has written its state and nothing else.
	 */
	public record RunSummary(
			String storageLocation,
			Long runId,
			String state,
			/** The most recent line of progress.txt: what the run is doing now. */
			String progress,
			String testFileName,
			String groups,
			Integer totalTestsRun,
			Integer totalFailures,
			Integer totalWarnings,
			String startTime,
			String endTime,
			/** ISO-8601 instant the run was enqueued, or null on older runs. */
			String submitted,
			long lastModified) {}

	/**
	 * The most recently written runs, newest first.
	 *
	 * @param limit how many to return; the walk is cheap but reading reports is
	 *              not, so the directories are ordered first and only this many
	 *              are opened.
	 */
	public List<RunSummary> list(int limit) {
		if (jobResourceConfig.isUseCloud()) {
			// The cloud path would need a prefix listing through ResourceManager,
			// which has no such call. Saying so beats returning an empty list
			// that reads as "there are no reports".
			throw new UnsupportedOperationException(
					"Listing runs is only implemented for a local job store; this instance is cloud-backed");
		}
		Path root = Path.of(jobResourceConfig.getLocal().getPath());
		if (!Files.isDirectory(root)) {
			return List.of();
		}

		// Identifying a run and timing it are ONE operation, not two. Every
		// operation here is a network round trip - the deployed store is
		// blobfuse with every cache off, measured at ~16ms per call against
		// nctsdevstorage - and this used to do three per directory: an
		// isDirectory, an isRegularFile on state.txt, then a getLastModifiedTime
		// on the same file. Reading the attributes once answers all three, and a
		// directory with no state.txt is not a run, which is how the uploaded
		// releases in files_to_validate are excluded.
		Map<Path, Long> stateModified = new LinkedHashMap<>();
		try (Stream<Path> entries = Files.list(root)) {
			for (Path dir : (Iterable<Path>) entries::iterator) {
				try {
					BasicFileAttributes attrs = Files.readAttributes(dir.resolve(STATE), BasicFileAttributes.class);
					if (attrs.isRegularFile()) {
						stateModified.put(dir, attrs.lastModifiedTime().toMillis());
					}
				} catch (IOException e) {
					// Not a run directory, or gone since the listing. Either way
					// there is nothing to show for it.
					LOGGER.trace("Skipping {}: {}", dir, e.toString());
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Could not list the job store at {}: {}", root.toAbsolutePath(), e.toString());
			return List.of();
		}

		// Newest first, by the state file rather than the directory: the
		// directory's timestamp does not move when a run finishes, so ordering on
		// it would sort by when a run STARTED and bury a run that has just
		// completed beneath older ones.
		List<Path> runDirs = new ArrayList<>(stateModified.keySet());
		runDirs.sort(Comparator.comparingLong((Path d) -> stateModified.getOrDefault(d, 0L)).reversed());

		List<RunSummary> out = new ArrayList<>(Math.min(limit, runDirs.size()));
		for (Path dir : runDirs) {
			if (out.size() >= limit) {
				break;
			}
			out.add(summarise(dir, stateModified.getOrDefault(dir, 0L)));
		}
		return out;
	}

	/** A run whose state can no longer change: nothing about it needs re-reading. */
	private static boolean isTerminal(String state) {
		return "COMPLETE".equals(state) || "FAILED".equals(state);
	}

	private RunSummary summarise(Path dir, long stateModified) {
		String storageLocation = dir.getFileName().toString();
		String state = readTrimmed(dir.resolve(STATE));

		// progress.txt and submitted.txt are read for runs IN FLIGHT only. The
		// in-flight card is the only thing that shows either - the run table
		// below it shows neither - and on a store with no caching each is a
		// round trip per run whether anything displays it or not.
		String progress = null;
		String submitted = null;
		long modified = stateModified;
		if (!isTerminal(state)) {
			// The last line, not the first: RVF appends a line per phase, so the
			// end of the file is what the run is doing now.
			progress = lastLine(dir.resolve(PROGRESS));
			// When the run was submitted, written once at QUEUED. Absent for runs
			// submitted before that existed, and for those the caller has only
			// lastModified - which is the last state change, not the run's age.
			submitted = readTrimmed(dir.resolve(SUBMITTED));
			modified = Math.max(stateModified, lastModified(dir.resolve(PROGRESS)));
		}

		// The report's dozen scalars, from the sidecar if it is there. Parsing
		// the report itself costs a 1.1MB read per run against a mount with
		// every cache off: 18 runs measured 1,110ms of pure I/O, and it grows
		// with the store. The sidecar is a few hundred bytes.
		Path digest = dir.resolve(SUMMARY);
		try {
			// Read it rather than asking whether it exists first: absence is an
			// exception either way, and probing would double the round trips on
			// the path this exists to make cheap.
			return readSummary(digest, storageLocation, state, progress, submitted, modified);
		} catch (NoSuchFileException e) {
			// Never listed before, or invalidated by a rewritten report.
			LOGGER.trace("No sidecar for {}", storageLocation);
		} catch (IOException | RuntimeException e) {
			// A truncated or half-written sidecar must not hide the report it
			// was derived from.
			LOGGER.warn("Ignoring unreadable {}: {}", digest, e.toString());
		}

		Path results = dir.resolve(RESULTS);
		if (!Files.isRegularFile(results)) {
			// Queued or running: the state is written before the report exists.
			return new RunSummary(storageLocation, null, state, progress, null, null, null, null, null, null, null, submitted, modified);
		}
		try {
			RunSummary summary = readSummary(results, storageLocation, state, progress, submitted, modified);
			cache(digest, summary);
			return summary;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not summarise {}: {}", results, e.toString());
			return new RunSummary(storageLocation, null, state, progress, null, null, null, null, null, null, null, submitted, modified);
		}
	}

	/**
	 * Writes the sidecar this listing reads next time.
	 *
	 * <p>In the REPORT's own shape, so {@link #readSummary} parses both and the
	 * two cannot drift: one parser, one set of field names. The scalars are
	 * derived from the report rather than serialised separately for the same
	 * reason.
	 *
	 * <p>Best-effort by design. A read-only store, a full disk or a lost race
	 * costs the next listing a re-parse and nothing else, so a failure here is
	 * logged at debug and never propagated - the caller asked for a list of
	 * runs, not for a cache write.
	 */
	private void cache(Path digest, RunSummary s) {
		Map<String, Object> config = new LinkedHashMap<>();
		if (s.runId() != null) {
			config.put("runId", s.runId());
		}
		if (s.testFileName() != null) {
			config.put("testFileName", s.testFileName());
		}
		if (s.groups() != null) {
			// The parser joins this array with ", " and the record holds the
			// joined string, so a single element round-trips exactly.
			config.put("groupsList", List.of(s.groups()));
		}
		Map<String, Object> result = new LinkedHashMap<>();
		if (s.totalTestsRun() != null) {
			result.put("totalTestsRun", s.totalTestsRun());
		}
		if (s.totalFailures() != null) {
			result.put("totalFailures", s.totalFailures());
		}
		if (s.totalWarnings() != null) {
			result.put("totalWarnings", s.totalWarnings());
		}
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("validationConfig", config);
		doc.put("TestResult", result);
		if (s.startTime() != null) {
			doc.put("startTime", s.startTime());
		}
		if (s.endTime() != null) {
			doc.put("endTime", s.endTime());
		}

		// Written beside its target and moved into place: a reader must never
		// see a half-written sidecar, and a partial one would be indistinguishable
		// from a report with no numbers in it.
		Path tmp = null;
		try {
			tmp = Files.createTempFile(digest.getParent(), "summary", ".tmp");
			Files.writeString(tmp, new Gson().toJson(doc), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, digest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				// SMB and blobfuse mounts do not all offer it.
				Files.move(tmp, digest, StandardCopyOption.REPLACE_EXISTING);
			}
			tmp = null;
		} catch (IOException | RuntimeException e) {
			LOGGER.debug("Could not cache {}: {}", digest, e.toString());
		} finally {
			if (tmp != null) {
				try {
					Files.deleteIfExists(tmp);
				} catch (IOException ignored) {
					// Nothing useful to do, and the listing still succeeded.
				}
			}
		}
	}

	/** The handful of scalars a listing needs, filled in as the stream is walked. */
	private static final class Fields {
		Long runId;
		String testFileName;
		String groups;
		Integer totalTestsRun;
		Integer totalFailures;
		Integer totalWarnings;
		String startTime;
		String endTime;
	}

	/**
	 * Pulls a dozen scalars out of the report without building the object graph.
	 *
	 * <p>A report holds every assertion that ran - the passed list alone can be
	 * hundreds of entries, each with its failing instances - and a listing needs
	 * none of it. Parsing whole reports to show a dozen rows would read tens of
	 * megabytes off a network file share to display a few numbers, so this walks
	 * the token stream and skips every array it does not need.
	 */
	private RunSummary readSummary(Path file, String storageLocation, String state, String progress,
			String submitted, long modified) throws IOException {
		Fields f = new Fields();
		try (JsonReader in = new JsonReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
			readResultObject(in, f);
		}
		return new RunSummary(storageLocation, f.runId, state, progress, f.testFileName, f.groups,
				f.totalTestsRun, f.totalFailures, f.totalWarnings, f.startTime, f.endTime, submitted, modified);
	}

	/**
	 * Reads a result object, in either of the two shapes this data takes.
	 *
	 * <p>The file on disk has {@code validationConfig} and {@code TestResult} at
	 * the top level: {@link ValidationReportService#writeResults} serialises the
	 * status report directly. The {@code /result/{runId}} endpoint then wraps
	 * that same content in an {@code rvfValidationResult} member before
	 * returning it.
	 *
	 * <p>Both are accepted, by recursing when the wrapper is met. Handling only
	 * the wrapped shape is exactly the mistake this method was written to
	 * correct: it was built against a saved HTTP response rather than a stored
	 * file, so every field came back null against the real store while the
	 * tests passed.
	 */
	private void readResultObject(JsonReader in, Fields f) throws IOException {
		in.beginObject();
		while (in.hasNext()) {
			switch (in.nextName()) {
				case "rvfValidationResult" -> readResultObject(in, f);
				case "validationConfig" -> {
					in.beginObject();
					while (in.hasNext()) {
						switch (in.nextName()) {
							case "runId" -> f.runId = in.nextLong();
							case "testFileName" -> f.testFileName = nextStringOrNull(in);
							case "groupsList" -> f.groups = String.join(", ", readStringArray(in));
							default -> in.skipValue();
						}
					}
					in.endObject();
				}
				case "TestResult" -> {
					in.beginObject();
					while (in.hasNext()) {
						switch (in.nextName()) {
							case "totalTestsRun" -> f.totalTestsRun = in.nextInt();
							case "totalFailures" -> f.totalFailures = in.nextInt();
							case "totalWarnings" -> f.totalWarnings = in.nextInt();
							// assertionsFailed / Passed / Warning / Skipped fall to
							// skipValue, which is the whole point of streaming this.
							default -> in.skipValue();
						}
					}
					in.endObject();
				}
				case "startTime" -> f.startTime = nextStringOrNull(in);
				case "endTime" -> f.endTime = nextStringOrNull(in);
				default -> in.skipValue();
			}
		}
		in.endObject();
	}

	private static List<String> readStringArray(JsonReader in) throws IOException {
		if (in.peek() == JsonToken.NULL) {
			in.nextNull();
			return List.of();
		}
		List<String> values = new ArrayList<>();
		in.beginArray();
		while (in.hasNext()) {
			values.add(in.nextString());
		}
		in.endArray();
		return values;
	}

	private static String nextStringOrNull(JsonReader in) throws IOException {
		if (in.peek() == JsonToken.NULL) {
			in.nextNull();
			return null;
		}
		return in.nextString();
	}

	/** The last non-blank line, or null. Progress is appended a line per phase. */
	private static String lastLine(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String last = null;
			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				if (!line.isBlank()) {
					last = line.trim();
				}
			}
			return last;
		} catch (IOException e) {
			return null;
		}
	}

	private static String readTrimmed(Path file) {
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line = reader.readLine();
			return line == null ? null : line.trim();
		} catch (IOException e) {
			return null;
		}
	}

	private static long lastModified(Path file) {
		try {
			return Files.getLastModifiedTime(file).toMillis();
		} catch (IOException e) {
			return 0L;
		}
	}
}
