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
		} catch (Throwable t) {
			System.out.println("RESULT   : FAILED");
			System.out.println(t.getClass().getName() + ": "
					+ String.valueOf(t.getMessage()).substring(0,
							Math.min(3000, String.valueOf(t.getMessage()).length())));
			System.exit(1);
		}
	}

	private static ResourceManager local(String path) {
		return new ResourceManager(new ManualResourceConfiguration(
				true, false, new ResourceConfiguration.Local(path), null), new DefaultResourceLoader());
	}
}
