package org.ihtsdo.rvf.core.service;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses which kept release is "the previous one" for a release under test.
 *
 * <p>A validation names its previous release by filename, and until now a
 * caller had to know that name and type it. The choice is mechanical: the same
 * edition, the newest effective time strictly before the one being validated.
 * So it is made here rather than by each caller.
 *
 * <p>RF2 package names carry both facts:
 *
 * <pre>
 *   SnomedCT_ManagedServiceAU_DAILYBUILD_BETA_AU1000036_20260930T120000Z.zip
 *   SnomedCT_ManagedServiceAU_PRODUCTION_AU1000036_20260831T120000Z.zip
 *   SnomedCT_InternationalRF2_PRODUCTION_20260731T120000Z.zip
 *   SnomedCT_Release_INT_20140731.zip
 * </pre>
 *
 * <p>The release status is deliberately NOT part of the match. A daily build is
 * validated against the last PRODUCTION release, which is the whole point of
 * the comparison, so treating {@code DAILYBUILD} and {@code PRODUCTION} as
 * different editions would find nothing.
 */
@Service
public class PreviousReleaseResolver {

	/** yyyymmdd, optionally followed by the Thhmmssz the SI adds. */
	private static final Pattern EFFECTIVE_TIME = Pattern.compile("(?<!\\d)(\\d{8})(?:T\\d{6}Z)?(?!\\d)");

	/**
	 * Tokens that say what KIND of release a package is, not which edition. They
	 * are dropped before comparing, so a beta daily build matches the production
	 * release of the same edition.
	 */
	private static final Set<String> STATUS_TOKENS = Set.of(
			"snomedct", "release", "rf2", "production", "dailybuild", "daily", "build",
			"beta", "alpha", "member", "delta", "snapshot", "full", "package");

	/**
	 * The best previous release for {@code underTest}, if there is one.
	 *
	 * @param underTest the filename of the release being validated
	 * @param kept      the filenames held by the release catalogue
	 */
	public Optional<String> resolve(String underTest, Collection<String> kept) {
		if (underTest == null || kept == null || kept.isEmpty()) {
			return Optional.empty();
		}
		String target = effectiveTime(underTest);
		if (target == null) {
			// No date in the name means no way to say which release precedes it,
			// and guessing would silently validate against the wrong content.
			return Optional.empty();
		}
		Set<String> targetEdition = editionTokens(underTest);

		String best = null;
		String bestTime = null;
		int bestOverlap = 0;

		for (String candidate : kept) {
			if (candidate == null || candidate.equalsIgnoreCase(underTest)) {
				continue;
			}
			String time = effectiveTime(candidate);
			// Strictly before: a release is not its own predecessor, and two
			// packages sharing an effective time say nothing about order.
			if (time == null || time.compareTo(target) >= 0) {
				continue;
			}
			Set<String> edition = editionTokens(candidate);
			int overlap = overlap(targetEdition, edition);
			if (overlap == 0) {
				// A different edition entirely. Validating an AU release against
				// an international one would produce noise, not findings.
				continue;
			}
			// Prefer the closest edition match; among equals, the latest release.
			if (overlap > bestOverlap || (overlap == bestOverlap && time.compareTo(bestTime) > 0)) {
				best = candidate;
				bestTime = time;
				bestOverlap = overlap;
			}
		}
		return Optional.ofNullable(best);
	}

	/** The last date in the name, which is the effective time by convention. */
	String effectiveTime(String filename) {
		Matcher m = EFFECTIVE_TIME.matcher(filename);
		String last = null;
		while (m.find()) {
			last = m.group(1);
		}
		return last;
	}

	/** The name's tokens with the status words and the dates removed. */
	Set<String> editionTokens(String filename) {
		String stem = filename.replaceAll("(?i)\\.zip$", "");
		Set<String> tokens = new LinkedHashSet<>();
		for (String raw : stem.split("[_\\-.]")) {
			String token = raw.toLowerCase(Locale.ROOT);
			if (token.isBlank() || STATUS_TOKENS.contains(token) || EFFECTIVE_TIME.matcher(raw).matches()) {
				continue;
			}
			tokens.add(token);
		}
		return tokens;
	}

	private static int overlap(Set<String> a, Set<String> b) {
		return (int) Arrays.stream(a.toArray(String[]::new)).filter(b::contains).count();
	}
}
