package org.ihtsdo.rvf;

import org.ihtsdo.drools.validator.rf2.DroolsRF2Validator;
import org.ihtsdo.otf.resourcemanager.ManualResourceConfiguration;
import org.ihtsdo.otf.resourcemanager.ResourceConfiguration;
import org.ihtsdo.otf.resourcemanager.ResourceManager;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Do the pinned Drools rules compile against the pinned snomed-drools?
 *
 * <p>This pairing has silently broken three times: rules calling methods absent
 * from 4.0.0; a domain TYPE ({@code org.ihtsdo.drools.domain.Annotation}) absent
 * from 5.7.0 and quietly resolving to an unrelated class; and Lucene 8.7.0
 * lacking {@code IndexSearcher.storedFields()}. Every one of them passed
 * compilation of RVF itself and failed only at runtime.
 *
 * <p>Constructing a {@link DroolsRF2Validator} builds the KieBase, so a rule
 * that will not compile throws here in seconds. No release data required -
 * this is the pairing check on its own, cheap enough to gate a build with.
 */
public class RulesCompileProbe {

	public static void main(String[] args) {
		if (args.length < 1) {
			System.err.println("usage: RulesCompileProbe <rules-dir>");
			System.exit(64);
		}
		// A blank resource manager loads 0 semantic tags and then throws "Failed to
		// load test resources" - which looks exactly like a rules failure but is
		// the probe's own fault. Point it at a real test-resources directory.
		String resources = args.length > 1 ? args[1] : "";
		System.out.println("rules    : " + args[0]);
		System.out.println("resources: " + (resources.isEmpty() ? "(none - expect a resource failure)" : resources));
		System.out.println("java     : " + System.getProperty("java.version"));
		try {
			DroolsRF2Validator validator = new DroolsRF2Validator(args[0], local(resources));
			// Loading the resources exercises the service classes the rules bind to,
			// which is where the Lucene mismatch would surface rather than at
			// KieBase build time.
			validator.getRuleExecutor().newTestResourceProvider(local(resources));
			System.out.println("RESULT   : rules compiled, executor built OK");

			// Compiling is not running. A rule that builds can still fail the
			// moment it touches a Concept/Description/Relationship, which is
			// where the 4.0.0 domain-type and Lucene mismatches actually
			// surfaced. Pass a release directory to exercise that path too.
			if (args.length > 2) {
				java.util.Set<String> dirs = new java.util.HashSet<>(
						java.util.List.of(args[2]));
				java.util.Set<String> ruleSets = args.length > 3
						? new java.util.HashSet<>(java.util.List.of(args[3].split(",")))
						: new java.util.HashSet<>(java.util.List.of("common-authoring"));
				System.out.println("release  : " + args[2]);
				System.out.println("ruleSets : " + ruleSets);
				long t0 = System.currentTimeMillis();
				var invalid = validator.validateRF2Files(dirs, null, ruleSets, null,
						args.length > 4 ? args[4] : null, null, true);
				System.out.println("RAN      : " + invalid.size() + " rule violations in "
						+ ((System.currentTimeMillis() - t0) / 1000) + "s");
				java.util.Map<String, Integer> byRule = new java.util.TreeMap<>();
				invalid.forEach(i -> byRule.merge(String.valueOf(i.getMessage()), 1, Integer::sum));
				byRule.entrySet().stream()
						.sorted((a, b) -> b.getValue() - a.getValue())
						.limit(10)
						.forEach(e -> System.out.println(String.format("   %6d  %s",
								e.getValue(), e.getKey().substring(0, Math.min(80, e.getKey().length())))));
			}
		} catch (Throwable t) {
			System.out.println("RESULT   : FAILED");
			System.out.println(t.getClass().getName() + ": "
					+ String.valueOf(t.getMessage()).substring(0,
							Math.min(3000, String.valueOf(t.getMessage()).length())));
			// Print the trace. Without it a message like "Cannot read the array
			// length because \"array\" is null" says nothing about WHICH of the
			// three failure modes this is - a rule, the resource loader, or the
			// probe's own configuration - and the whole point of a probe is to
			// tell them apart.
			t.printStackTrace(System.out);
			for (Throwable c = t.getCause(); c != null; c = c.getCause()) {
				System.out.println("CAUSED BY: " + c);
			}
			System.exit(1);
		}
	}

	/**
	 * The path MUST be relative to the working directory.
	 *
	 * <p>{@code ResourceConfiguration.Local} resolves an absolute path to a
	 * directory whose {@code listFiles()} returns null, and the caller streams
	 * that array unguarded - so an absolute path fails as
	 * {@code NullPointerException: Cannot read the array length because "array"
	 * is null}, five frames deep in TestResourceProvider, with nothing pointing
	 * at the path. It reads exactly like missing or wrongly-shaped resources.
	 * Relativise rather than pass through, so a caller supplying an absolute
	 * path gets the working behaviour instead of that NPE.
	 */
	private static ResourceManager local(String path) {
		return new ResourceManager(new ManualResourceConfiguration(
				true, false, new ResourceConfiguration.Local(relativise(path)), null),
				new DefaultResourceLoader());
	}

	private static String relativise(String path) {
		if (path.isEmpty()) {
			return path;
		}
		java.nio.file.Path p = java.nio.file.Paths.get(path);
		if (!p.isAbsolute()) {
			return path.endsWith("/") ? path : path + "/";
		}
		java.nio.file.Path cwd = java.nio.file.Paths.get(System.getProperty("user.dir"));
		try {
			String rel = cwd.relativize(p).toString();
			System.out.println("note     : relativised " + path + " -> " + rel);
			return rel.endsWith("/") ? rel : rel + "/";
		} catch (IllegalArgumentException e) {
			// Different root - cannot relativise. Say so rather than hand back an
			// absolute path that will NPE unhelpfully.
			throw new IllegalArgumentException("resource path must be under the working "
					+ "directory (" + cwd + "); got " + path, e);
		}
	}
}
