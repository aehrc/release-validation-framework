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
			"beta", "alpha", "member", "delta", "snapshot", "full", "package",
			"ncts", "sct", "distribution", "all");

	/**
	 * A SNOMED namespace, which is the identity that survives renaming.
	 *
	 * <p>The same edition is named several ways here, and the schemes share no
	 * words at all:
	 *
	 * <pre>
	 *   SnomedCT_ManagedServiceAU_DAILYBUILD_BETA_AU1000036_20260930T120000Z.zip
	 *   NCTS_SCT_RF2_DISTRIBUTION_32506021000036107-20260731-ALL.zip
	 * </pre>
	 *
	 * <p>Matching on words alone finds nothing between those two, which is
	 * exactly the pair a nightly needs. What they do share is the AU namespace
	 * {@code 1000036}: plainly in {@code AU1000036}, and inside the module
	 * concept id {@code 32506021000036107}, because a SNOMED identifier is
	 * item-namespace-partition-check and the namespace is the seven digits
	 * before the last three.
	 */
	private static final Pattern NAMESPACE_IN_ID = Pattern.compile("(\\d{7})\\d{3}$");
	private static final Pattern TRAILING_NAMESPACE = Pattern.compile("(?:^|[^0-9])(\\d{7})$");
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

	/**
	 * The name's tokens with status words and dates removed, plus any SNOMED
	 * namespace found in them.
	 *
	 * <p>The namespace is what makes two differently-named packages of the same
	 * edition comparable; see {@link #NAMESPACE_IN_ID}. It is added as a token
	 * in its own right, so a match on it counts exactly like a match on a word.
	 */
	Set<String> editionTokens(String filename) {
		String stem = filename.replaceAll("(?i)\\.zip$", "");
		Set<String> tokens = new LinkedHashSet<>();
		for (String raw : stem.split("[_\\-.]")) {
			String token = raw.toLowerCase(Locale.ROOT);
			if (token.isBlank() || STATUS_TOKENS.contains(token) || EFFECTIVE_TIME.matcher(raw).matches()) {
				continue;
			}
			tokens.add(token);
			namespaceOf(token).ifPresent(ns -> tokens.add("ns:" + ns));
		}
		return tokens;
	}

	/**
	 * The SNOMED namespace a token carries, if any.
	 *
	 * <p>Two shapes occur: a full identifier, where the namespace is the seven
	 * digits before the partition and check digits, and a token that simply ends
	 * in the namespace, such as {@code au1000036}. A plain eight-digit date
	 * cannot reach here, because dates are dropped before this is called.
	 */
	Optional<String> namespaceOf(String token) {
		Matcher inId = NAMESPACE_IN_ID.matcher(token);
		if (inId.find() && token.chars().allMatch(Character::isDigit) && token.length() >= 10) {
			return Optional.of(inId.group(1));
		}
		Matcher trailing = TRAILING_NAMESPACE.matcher(token);
		if (trailing.find()) {
			return Optional.of(trailing.group(1));
		}
		return Optional.empty();
	}

	private static int overlap(Set<String> a, Set<String> b) {
		return (int) Arrays.stream(a.toArray(String[]::new)).filter(b::contains).count();
	}
}
