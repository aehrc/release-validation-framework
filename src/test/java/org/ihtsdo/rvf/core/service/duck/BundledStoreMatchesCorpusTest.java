package org.ihtsdo.rvf.core.service.duck;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
