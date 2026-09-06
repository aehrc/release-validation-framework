package org.ihtsdo.rvf.core.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real package names, because the whole job is reading them correctly.
 */
class PreviousReleaseResolverTest {

	private final PreviousReleaseResolver resolver = new PreviousReleaseResolver();

	private static final String AU_DAILY =
			"SnomedCT_ManagedServiceAU_DAILYBUILD_BETA_AU1000036_20260930T120000Z.zip";
	private static final String AU_SEP =
			"SnomedCT_ManagedServiceAU_PRODUCTION_AU1000036_20260831T120000Z.zip";
	private static final String AU_AUG =
			"SnomedCT_ManagedServiceAU_PRODUCTION_AU1000036_20260731T120000Z.zip";
	private static final String INT_JUL =
			"SnomedCT_InternationalRF2_PRODUCTION_20260731T120000Z.zip";

	@Test
	void picksTheNewestEarlierReleaseOfTheSameEdition() {
		assertEquals(AU_SEP, resolver.resolve(AU_DAILY, List.of(AU_AUG, AU_SEP, INT_JUL)).orElseThrow());
	}

	@Test
	void matchesAcrossReleaseStatus() {
		// The point of a nightly: a BETA daily build validated against the last
		// PRODUCTION release. Treating those as different editions finds nothing.
		assertTrue(resolver.resolve(AU_DAILY, List.of(AU_SEP)).isPresent());
	}

	@Test
	void neverPicksTheSameOrALaterRelease() {
		// AU_SEP is its own name and AU_DAILY is later, so neither is a valid
		// predecessor of AU_SEP - only AU_AUG is.
		assertEquals(AU_AUG, resolver.resolve(AU_SEP, List.of(AU_AUG, AU_SEP, AU_DAILY)).orElseThrow());
	}

	@Test
	void willNotCrossEditions() {
		// An AU release has no international predecessor. Returning one would
		// validate AU content against international content and report noise.
		assertTrue(resolver.resolve(AU_DAILY, List.of(INT_JUL)).isEmpty());
	}

	@Test
	void prefersTheCloserEditionMatchOverTheLaterDate() {
		// Same module id as well as the same edition name, so a better match,
		// even though the other candidate is newer.
		String otherAu = "SnomedCT_ManagedServiceAU_PRODUCTION_20260901T120000Z.zip";
		assertEquals(AU_SEP, resolver.resolve(AU_DAILY, List.of(otherAu, AU_SEP)).orElseThrow());
	}

	@Test
	void handlesTheShortInternationalNaming() {
		String older = "SnomedCT_Release_INT_20140131.zip";
		String newer = "SnomedCT_Release_INT_20140731.zip";
		String target = "SnomedCT_Release_INT_20150131.zip";
		assertEquals(newer, resolver.resolve(target, List.of(older, newer)).orElseThrow());
	}

	@Test
	void emptyWhenNothingIsKept() {
		assertTrue(resolver.resolve(AU_DAILY, List.of()).isEmpty());
	}

	@Test
	void emptyWhenTheNameCarriesNoDate() {
		// Guessing here would silently validate against the wrong content.
		assertTrue(resolver.resolve("some-release.zip", List.of(AU_SEP)).isEmpty());
	}

	@Test
	void readsTheLastDateInTheName() {
		// The SI form carries a time as well, and a name can hold more than one
		// date-like token - the effective time is the last.
		assertEquals("20260930", resolver.effectiveTime(AU_DAILY));
		assertEquals("20140731", resolver.effectiveTime("SnomedCT_Release_INT_20140731.zip"));
		assertNull(resolver.effectiveTime("no-date-here.zip"));
	}

	@Test
	void editionTokensDropStatusAndDates() {
		Set<String> tokens = resolver.editionTokens(AU_DAILY);
		assertTrue(tokens.contains("managedserviceau"));
		assertTrue(tokens.contains("au1000036"));
		assertFalse(tokens.contains("dailybuild"));
		assertFalse(tokens.contains("beta"));
		assertFalse(tokens.contains("production"));
		assertFalse(tokens.contains("snomedct"));
		assertFalse(tokens.contains("20260930t120000z"));
	}
}
