package org.ihtsdo.rvf.core.service.duck;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The build gate on the bundled store.
 *
 * <p>{@code src/main/resources/duck/store.json} is a checked-in build output of
 * the assertion corpus that {@code checkout-resources.sh} pins. The two are
 * separate inputs that must move together, and only this test makes them do so:
 * bump {@code ASSERTIONS_REF} without republishing and {@code mvn package}
 * fails here, before an image is ever built.
 *
 * <p>Without it the pair would drift silently. Nothing at run time reads the
 * corpus SQL - precompiling is precisely what removes that read - so a stale
 * store produces a complete, plausible report built from the previous corpus's
 * assertions. There is no symptom to notice.
 *
 * <p>Skipped when the corpus has not been checked out, which is the case in a
 * clean clone before {@code checkout-resources.sh} runs. That is a real hole,
 * and the reason the same comparison also runs at load time in
 * {@link DuckStoreLocator}: between them, the only way to ship a mismatched
 * pair is to build with no corpus AND deploy with no corpus.
 *
 * <p>DO NOT try to prove this test bites by editing a corpus script and running
 * Maven. {@code checkout-resources.sh} is bound to {@code generate-resources},
 * so the build resets the checkout to the pinned ref before {@code test} runs
 * and silently discards the edit - the test then passes and looks vacuous. That
 * is the correct behaviour: what this guards is the PIN moving, not a local
 * edit surviving. To exercise the comparison directly, mutate the corpus and
 * call {@link DuckStoreLocator#load()} against {@code target/classes} without
 * the Maven lifecycle; {@link DuckStoreLocatorTest} covers the same logic on a
 * temporary corpus, where nothing resets anything.
 */
class BundledStoreMatchesCorpusTest {

	private static final Path CORPUS = Path.of("snomed-release-validation-assertions");

	@Test
	void theBundledStoreWasPublishedFromThePinnedCorpus() throws IOException {
		assumeTrue(Files.isDirectory(CORPUS),
				"assertion corpus not checked out - run ./checkout-resources.sh");

		// Not DuckStoreLocator's own load(): this must fail on a mismatch even
		// if someone later softens the runtime check to a warning.
		DuckStoreLocator locator = new DuckStoreLocator("", CORPUS.toString());
		DuckStore store = locator.load();

		Map<String, String> hashes = store.assertionSourceHashes();
		assertEquals(store.assertions().size(), hashes.size(),
				"every bundled assertion should record the hash of the SQL it was compiled "
						+ "from; a store missing them cannot be verified against the corpus");
		assertTrue(hashes.size() > 300,
				"bundled store looks truncated: " + hashes.size() + " assertions. "
						+ "A store that reads as nearly empty still PASSES every validation, "
						+ "because no assertions means no findings.");
	}

	/**
	 * Every assertion the corpus declares must be IN the store, unless it is
	 * named in {@code duck/known-store-omissions.json} with a reason.
	 *
	 * <p>The check above is one-directional: it verifies the assertions the
	 * store already holds, so it cannot notice ones the corpus declares and the
	 * store lacks. That gap is not theoretical - folding the 200 AMT assertions
	 * into the corpus left this suite green while none of them existed to the
	 * DuckDB engine at all.
	 *
	 * <p>Which is the dangerous shape of failure here: a missing assertion does
	 * not error, it simply never runs, and a report with a whole category
	 * absent still reads as a healthy green run. So the store has to report its
	 * own denominator.
	 */
	@Test
	void everyCorpusAssertionIsInTheStoreOrExplained() throws IOException {
		assumeTrue(Files.isDirectory(CORPUS),
				"assertion corpus not checked out - run ./checkout-resources.sh");

		DuckStore store = new DuckStoreLocator("", CORPUS.toString()).load();
		Set<String> stored = store.assertions().keySet();
		Map<String, String> declared = declaredAssertions(CORPUS.resolve("manifest.xml"));
		Map<String, String> allowed = knownOmissions();

		// Sorted so the failure message is stable and diffable run to run.
		Map<String, String> unexplained = new TreeMap<>();
		declared.forEach((uuid, sqlFile) -> {
			if (!stored.contains(uuid) && !allowed.containsKey(uuid)) {
				unexplained.put(uuid, sqlFile);
			}
		});

		if (!unexplained.isEmpty()) {
			StringBuilder message = new StringBuilder()
					.append(unexplained.size()).append(" of ").append(declared.size())
					.append(" corpus assertions are absent from the bundled store, so they")
					.append(" would never run and the report would still be green.")
					.append("\nRepublish the store from the pinned corpus, or record each")
					.append(" with a reason in duck/known-store-omissions.json.\n");
			unexplained.entrySet().stream().limit(10).forEach(e ->
					message.append("  ").append(e.getKey()).append("  ").append(e.getValue()).append('\n'));
			if (unexplained.size() > 10) {
				message.append("  ... and ").append(unexplained.size() - 10).append(" more\n");
			}
			fail(message.toString());
		}

		// An allow-list entry for an assertion that IS present, or that the
		// corpus no longer declares, is stale - it would go on excusing an
		// absence that has been fixed or has moved on.
		Set<String> stale = new TreeSet<>(allowed.keySet());
		stale.removeIf(uuid -> !stored.contains(uuid) && declared.containsKey(uuid));
		assertTrue(stale.isEmpty(),
				"duck/known-store-omissions.json excuses assertions that are present or no "
						+ "longer declared; remove them: " + stale);
	}

	/** uuid -&gt; sqlFile for every {@code <script>} the manifest declares. */
	private static Map<String, String> declaredAssertions(Path manifest) throws IOException {
		Map<String, String> out = new LinkedHashMap<>();
		try (InputStream in = Files.newInputStream(manifest)) {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			// Corpus-controlled input, but there is no reason to resolve anything
			// external while reading it.
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			NodeList scripts = factory.newDocumentBuilder().parse(in)
					.getElementsByTagName("script");
			for (int i = 0; i < scripts.getLength(); i++) {
				Element script = (Element) scripts.item(i);
				String uuid = script.getAttribute("uuid");
				if (!uuid.isBlank()) {
					out.put(uuid, script.getAttribute("sqlFile"));
				}
			}
		} catch (ParserConfigurationException | SAXException e) {
			throw new IOException("could not read " + manifest, e);
		}
		return out;
	}

	private static Map<String, String> knownOmissions() throws IOException {
		Path path = Path.of("duck", "known-store-omissions.json");
		if (!Files.isRegularFile(path)) {
			return Map.of();
		}
		JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
		JsonObject omissions = root.getAsJsonObject("omissions");
		Map<String, String> out = new LinkedHashMap<>();
		if (omissions != null) {
			omissions.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
		}
		return out;
	}
}
