import org.ihtsdo.otf.sqs.service.ExpressionConstraintToLuceneConverter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * How many real out-of-range expressions carry more than one "(* NOT ...)"
 * clause? Those are the ones PR A now routes back to the range chain, so this
 * is the fraction of the measured speedup that the multi-clause guard gives up.
 */
public class NotInProbe {
	public static void main(String[] args) throws Exception {
		ExpressionConstraintToLuceneConverter converter = new ExpressionConstraintToLuceneConverter();
		List<String> expressions = Files.readAllLines(Path.of(args[0]));
		int converted = 0, withNot = 0, multi = 0, failed = 0;
		for (String ecl : expressions) {
			if (ecl.isBlank()) continue;
			String lucene;
			try {
				lucene = converter.parse(ecl);
			} catch (Exception e) {
				failed++;
				continue;
			}
			converted++;
			int clauses = count(lucene, "(* NOT");
			if (clauses >= 1) withNot++;
			if (clauses > 1) {
				multi++;
				System.out.println("MULTI(" + clauses + "): " + ecl);
			}
		}
		System.out.println("expressions converted : " + converted);
		System.out.println("with a (* NOT clause  : " + withNot);
		System.out.println("with MORE than one    : " + multi + "   <- lose the fast path");
		System.out.println("failed to convert     : " + failed);
	}

	private static int count(String haystack, String needle) {
		int n = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) n++;
		return n;
	}
}
