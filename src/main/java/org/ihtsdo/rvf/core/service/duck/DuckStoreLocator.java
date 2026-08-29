package org.ihtsdo.rvf.core.service.duck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Finds the precompiled assertion store, and refuses one that does not match
 * the assertion corpus it is shipped beside.
 *
 * <p>The store is BUNDLED: {@code src/main/resources/duck/store.json} is a
 * checked-in build output of the corpus pinned in {@code checkout-resources.sh},
 * so a built artefact always has one and nothing has to be mounted, configured
 * or generated at deploy time. {@code rvf.duck.store} still overrides it, which
 * is how you try a different corpus without rebuilding.
 *
 * <p>Bundling a generated file creates exactly one hazard, and this class exists
 * to close it. The store and the corpus are separate inputs that must move
 * together: bump {@code ASSERTIONS_REF} without republishing and the artefact
 * executes the OLD transpiled SQL while reporting it under the NEW corpus's
 * assertion text, uuids and groups. Nothing downstream reads the corpus SQL any
 * more - that is the whole point of precompiling - so there is no natural place
 * for the mismatch to surface. It would simply produce confident wrong answers.
 *
 * <p>So the publisher's per-assertion {@code sha256} is checked against the
 * corpus on disk, and a mismatch is fatal. Not a warning: a warning in a
 * container log is not seen, and the failure this guards against is one whose
 * output looks entirely normal.
 */
@Component
public class DuckStoreLocator {

	private static final Logger LOGGER = LoggerFactory.getLogger(DuckStoreLocator.class);

	/** The store baked into the artefact, on the classpath. */
	static final String BUNDLED_STORE = "/duck/store.json";

	/**
	 * How many differing files to name before giving up on the message. A corpus
	 * bump changes a handful of files; a store built from an entirely different
	 * corpus changes hundreds, and printing all of them buries the one line that
	 * says what to do about it.
	 */
	private static final int MAX_REPORTED = 10;

	private final String configuredStore;
	private final String corpusRoot;

	public DuckStoreLocator(@Value("${rvf.duck.store:}") String configuredStore,
			@Value("${rvf.assertion.resource.local.path:}") String corpusRoot) {
		this.configuredStore = configuredStore;
		this.corpusRoot = corpusRoot;
	}

	/** Where the store came from, for logging. */
	public String description() {
		return isConfigured() ? configuredStore : "bundled " + BUNDLED_STORE;
	}

	private boolean isConfigured() {
		return configuredStore != null && !configuredStore.isBlank();
	}

	/**
	 * Reads the store, then verifies it against the corpus.
	 *
	 * @throws IOException if neither a configured nor a bundled store can be read
	 * @throws IllegalStateException if the store does not match the corpus
	 */
	public DuckStore load() throws IOException {
		DuckStore store = isConfigured()
				? DuckStore.read(Path.of(configuredStore))
				: readBundled();
		verifyAgainstCorpus(store);
		return store;
	}

	private DuckStore readBundled() throws IOException {
		try (InputStream in = DuckStoreLocator.class.getResourceAsStream(BUNDLED_STORE)) {
			if (in == null) {
				// Only reachable if the build dropped the resource. Say which
				// property provides a way out, because the obvious reading of
				// "store not found" is that one was supposed to be configured.
				throw new IOException("No assertion store. " + BUNDLED_STORE
						+ " is missing from the artefact and rvf.duck.store is not set.");
			}
			return DuckStore.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	/**
	 * Compares every assertion's recorded source hash against the corpus file of
	 * the same name.
	 *
	 * <p>Skipped, with a warning, when there is no corpus to compare against -
	 * unit tests and a store-only deployment both hit that, and neither is
	 * wrong. The build-time test is what guarantees the shipped pair agree; this
	 * is the backstop for a store that arrived some other way.
	 */
	void verifyAgainstCorpus(DuckStore store) {
		Map<String, String> expected = store.assertionSourceHashes();
		if (corpusRoot == null || corpusRoot.isBlank() || !Files.isDirectory(Path.of(corpusRoot))) {
			LOGGER.warn("Assertion corpus not available at '{}' - cannot verify that the "
					+ "store ({}) was published from it. {} assertions unverified.",
					corpusRoot, description(), expected.size());
			return;
		}
		if (expected.isEmpty()) {
			// A store with no recorded hashes predates this check. Refusing it
			// would be worse than saying so: it is readable and self-consistent.
			LOGGER.warn("Store {} records no assertion source hashes - it cannot be "
					+ "verified against the corpus.", description());
			return;
		}
		Map<String, Path> corpus = indexByFileName(Path.of(corpusRoot));
		List<String> missing = new ArrayList<>();
		List<String> changed = new ArrayList<>();
		for (Map.Entry<String, String> e : expected.entrySet()) {
			Path file = corpus.get(e.getKey());
			if (file == null) {
				missing.add(e.getKey());
			} else if (!sha256Prefix(file, e.getValue().length()).equals(e.getValue())) {
				changed.add(e.getKey());
			}
		}
		if (missing.isEmpty() && changed.isEmpty()) {
			LOGGER.info("Assertion store {} verified against {} corpus files.",
					description(), expected.size());
			return;
		}
		throw new IllegalStateException(mismatchMessage(missing, changed, expected.size()));
	}

	private String mismatchMessage(List<String> missing, List<String> changed, int total) {
		StringBuilder message = new StringBuilder("The assertion store does not match the "
				+ "assertion corpus. The store was published from a different corpus than "
				+ "the one in this artefact, so a run would execute the store's SQL while "
				+ "reporting it under this corpus's assertion text and uuids.\n"
				+ "  store:  " + description() + "\n"
				+ "  corpus: " + corpusRoot + "\n"
				+ "  " + changed.size() + " of " + total + " assertions differ, "
				+ missing.size() + " are absent from the corpus.\n");
		append(message, "differ", changed);
		append(message, "absent", missing);
		message.append("Republish the store from this corpus (see duck/README.md).");
		return message.toString();
	}

	private static void append(StringBuilder message, String label, List<String> files) {
		if (files.isEmpty()) {
			return;
		}
		message.append("  ").append(label).append(": ")
				.append(String.join(", ", files.subList(0, Math.min(MAX_REPORTED, files.size()))));
		if (files.size() > MAX_REPORTED) {
			message.append(", and ").append(files.size() - MAX_REPORTED).append(" more");
		}
		message.append('\n');
	}

	/**
	 * {@code file name -> path} for every .sql under the corpus.
	 *
	 * <p>By BASE NAME, because that is the only identity the store records - the
	 * publisher takes {@code path.name}. It is unambiguous: the corpus's 453
	 * scripts have 453 distinct names, and the manifest itself addresses them by
	 * name within a category directory, so a duplicate would already be a defect
	 * on the incumbent path. A later duplicate would make this check compare
	 * against an arbitrary one of the two, so it is rejected rather than
	 * silently resolved.
	 */
	private static Map<String, Path> indexByFileName(Path root) {
		try (Stream<Path> files = Files.walk(root)) {
			return files.filter(p -> p.getFileName().toString().endsWith(".sql"))
					.collect(java.util.stream.Collectors.toMap(
							p -> p.getFileName().toString(),
							p -> p,
							(a, b) -> {
								throw new IllegalStateException(
										"Two corpus scripts share the name "
												+ a.getFileName() + ": " + a + " and " + b
												+ ". The store identifies assertions by name, "
												+ "so this is ambiguous.");
							},
							java.util.LinkedHashMap::new));
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read the assertion corpus at " + root, e);
		}
	}

	private static String sha256Prefix(Path file, int length) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			// The publisher hashes the DECODED text, not the bytes, and reads
			// with errors="replace". Match that: a corpus file with a byte that
			// is not valid UTF-8 must hash the same on both sides or the check
			// fires on a file nobody changed.
			String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash).substring(0, length);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read corpus script " + file, e);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
