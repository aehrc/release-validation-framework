package org.ihtsdo.rvf.core.service;

import com.google.gson.stream.JsonReader;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
			String testFileName,
			String groups,
			Integer totalTestsRun,
			Integer totalFailures,
			Integer totalWarnings,
			String startTime,
			String endTime,
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

		List<Path> runDirs = new ArrayList<>();
		try (Stream<Path> entries = Files.list(root)) {
			entries.filter(Files::isDirectory)
					.filter(d -> Files.isRegularFile(d.resolve(STATE)))
					.forEach(runDirs::add);
		} catch (IOException e) {
			LOGGER.warn("Could not list the job store at {}: {}", root.toAbsolutePath(), e.toString());
			return List.of();
		}

		// Newest first, by the state file rather than the directory: the
		// directory's timestamp does not move when a run finishes, so ordering on
		// it would sort by when a run STARTED and bury a run that has just
		// completed beneath older ones.
		runDirs.sort(Comparator.comparingLong((Path d) -> lastModified(d.resolve(STATE))).reversed());

		List<RunSummary> out = new ArrayList<>(Math.min(limit, runDirs.size()));
		for (Path dir : runDirs) {
			if (out.size() >= limit) {
				break;
			}
			out.add(summarise(dir));
		}
		return out;
	}

	private RunSummary summarise(Path dir) {
		String storageLocation = dir.getFileName().toString();
		String state = readTrimmed(dir.resolve(STATE));
		long modified = lastModified(dir.resolve(STATE));

		Path results = dir.resolve(RESULTS);
		if (!Files.isRegularFile(results)) {
			// Queued or running: the state is written before the report exists.
			return new RunSummary(storageLocation, null, state, null, null, null, null, null, null, null, modified);
		}
		try {
			return readSummary(results, storageLocation, state, modified);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not summarise {}: {}", results, e.toString());
			return new RunSummary(storageLocation, null, state, null, null, null, null, null, null, null, modified);
		}
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
	private RunSummary readSummary(Path file, String storageLocation, String state, long modified) throws IOException {
		Long runId = null;
		String testFileName = null;
		String groups = null;
		Integer run = null;
		Integer failures = null;
		Integer warnings = null;
		String startTime = null;
		String endTime = null;

		try (JsonReader in = new JsonReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
			in.beginObject();
			while (in.hasNext()) {
				if (!"rvfValidationResult".equals(in.nextName())) {
					in.skipValue();
					continue;
				}
				in.beginObject();
				while (in.hasNext()) {
					switch (in.nextName()) {
						case "validationConfig" -> {
							in.beginObject();
							while (in.hasNext()) {
								switch (in.nextName()) {
									case "runId" -> runId = in.nextLong();
									case "testFileName" -> testFileName = nextStringOrNull(in);
									case "groupsList" -> groups = String.join(", ", readStringArray(in));
									default -> in.skipValue();
								}
							}
							in.endObject();
						}
						case "TestResult" -> {
							in.beginObject();
							while (in.hasNext()) {
								switch (in.nextName()) {
									case "totalTestsRun" -> run = in.nextInt();
									case "totalFailures" -> failures = in.nextInt();
									case "totalWarnings" -> warnings = in.nextInt();
									// assertionsFailed / Passed / Warning / Skipped fall to
									// skipValue, which is the whole point of streaming this.
									default -> in.skipValue();
								}
							}
							in.endObject();
						}
						case "startTime" -> startTime = nextStringOrNull(in);
						case "endTime" -> endTime = nextStringOrNull(in);
						default -> in.skipValue();
					}
				}
				in.endObject();
			}
			in.endObject();
		}
		return new RunSummary(storageLocation, runId, state, testFileName, groups, run, failures, warnings,
				startTime, endTime, modified);
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
