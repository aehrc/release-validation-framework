package org.ihtsdo.rvf;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.filter.ElementFilter;
import org.jdom2.input.SAXBuilder;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Does every {@code sqlFile} named in a manifest resolve against a given
 * assertions checkout?
 *
 * <p>The manifest and the assertions repository are a matched pair, and nothing
 * checks that they agree. Move either one and RVF throws during Spring startup
 * on the FIRST unresolvable script, so a mismatch is discovered in a cluster,
 * one file at a time, after a deploy. That is how 2026-08-07 broke: an image
 * unrebuilt since 2024 picked up two years of upstream drift through an
 * unpinned clone, and RVF would not start.
 *
 * <p>This resolves ALL of them and reports every failure at once. No database,
 * no release data, no cluster - it needs a manifest and a directory, so it is
 * cheap enough to gate an image build with.
 *
 * <p><b>Resolution is folder-scoped, and that is the whole point.</b>
 * {@code AssertionsDatabaseImporter} builds
 * {@code <dir>/<category>/<sqlFile>}, where category is the element's
 * {@code category} attribute truncated at {@code "validation"} - so
 * {@code file-centric-validation} means the file must live in
 * {@code file-centric/}. A script that upstream MOVED to another category still
 * exists under its old name, so a naive whole-tree search for the filename
 * reports it as present while RVF fails on it. Any check that does not model
 * the folder will under-report.
 *
 * <pre>
 *   ManifestResolveProbe &lt;manifest.xml&gt; &lt;assertions-dir&gt;
 * </pre>
 *
 * Exits 0 if everything resolves, 1 if anything does not.
 */
public class ManifestResolveProbe {

	/**
	 * {@code RvfAssertionsDatabasePrimerService.scriptsDir}. The categories are
	 * not at the root of the assertions checkout - they sit under this - so a
	 * probe pointed straight at the clone would report everything missing.
	 */
	private static final String SCRIPTS_DIR = "scripts";

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: ManifestResolveProbe <manifest.xml> <assertions-dir>");
			System.exit(64);
		}
		File manifest = new File(args[0]);
		File dir = new File(args[1]);
		if (!manifest.isFile()) {
			System.err.println("no such manifest: " + manifest);
			System.exit(66);
		}
		if (!dir.isDirectory()) {
			System.err.println("no such assertions directory: " + dir);
			System.exit(66);
		}

		System.out.println("manifest  : " + manifest.getAbsolutePath());
		System.out.println("assertions: " + dir.getAbsolutePath());

		List<Element> scripts = scriptElements(manifest);
		List<String> missing = new ArrayList<>();
		int checked = 0;

		for (Element element : scripts) {
			String sqlFileName = element.getAttributeValue("sqlFile");
			String category = element.getAttributeValue("category");
			if (sqlFileName == null || category == null) {
				continue;
			}
			// Placeholders such as {{$filename}} are template text, not a script
			// this manifest is asserting the existence of.
			if (sqlFileName.contains("{{")) {
				continue;
			}
			checked++;
			String resolved = SCRIPTS_DIR + "/" + folderFor(category) + "/" + sqlFileName;
			if (!new File(dir, resolved).isFile()) {
				missing.add(resolved + "   (uuid " + element.getAttributeValue("uuid") + ")");
			}
		}

		System.out.printf("%nchecked %,d sqlFile references, %,d unresolved%n", checked, missing.size());
		if (!missing.isEmpty()) {
			System.out.println();
			missing.stream().sorted().forEach(m -> System.out.println("  MISSING  " + m));
			System.out.println();
			System.out.println("RVF would fail to start on the first of these. Fix the manifest and the");
			System.out.println("assertions pin in the SAME change - either alone reproduces the mismatch.");
			System.exit(1);
		}
		System.out.println("RESULT: every sqlFile resolves");
	}

	/**
	 * Mirrors {@code AssertionsDatabaseImporter.addSqlTestsToAssertion}: the
	 * category attribute is written as e.g. {@code file-centric-validation} and
	 * the folder is everything before {@code "validation"}, less the separator.
	 * Kept deliberately identical, quirks included - a probe that resolves paths
	 * differently from the importer is worse than no probe, because it reports
	 * success for a configuration that will not start.
	 */
	static String folderFor(String category) {
		int index = category.indexOf("validation");
		return index > 0 ? category.substring(0, index - 1) : category;
	}

	private static List<Element> scriptElements(File manifest) throws Exception {
		try (InputStream in = new FileInputStream(manifest)) {
			Document xmlDocument = new SAXBuilder().build(in);
			XPathExpression<Element> expression = XPathFactory.instance()
					.compile("//script", new ElementFilter("script"));
			return expression.evaluate(xmlDocument);
		}
	}
}
