package org.ihtsdo.rvf.core.service.duck;

import org.ihtsdo.rvf.core.data.model.TestRunItem;
import org.ihtsdo.rvf.core.data.model.ValidationReport;
import org.ihtsdo.rvf.core.service.ValidationReportService;
import org.ihtsdo.rvf.core.service.WhitelistService;
import org.ihtsdo.rvf.core.service.config.MysqlExecutionConfig;
import org.ihtsdo.rvf.core.service.config.ValidationRunConfig;
import org.ihtsdo.rvf.core.service.pojo.ValidationStatusReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Extension validation: the two-phase split and the dependency combine.
 *
 * <p>The fixture is arranged so that every count distinguishes one specific way
 * of getting the merge wrong, and no count is reachable by two routes:
 *
 * <ul>
 * <li><b>Which schema each phase ran against.</b> {@code newComponents} carries
 *     the {@code release-type-validation} keyword and compares the prospective
 *     against the previous release. Against the extension it finds 2; against
 *     the combined schema it would also find the two international concepts and
 *     report 4. {@code orphanDescriptions} carries a different keyword and finds
 *     0 against the combined schema but 2 against the extension alone. One
 *     number each way, so a run that routed either phase to the wrong schema
 *     cannot produce both.
 * <li><b>The merge rule.</b> Concept 1 is newer in the extension, 5 is newer in
 *     the dependency, 7 is a TIE, 2 is extension-only and 100/101 are
 *     dependency-only. Counting the survivors by module gives 2 extension rows
 *     and 4 dependency rows - a single pair of numbers that pins all five cases,
 *     including MySQL's {@code >=}/{@code >} asymmetry that hands ties to the
 *     dependency.
 * <li><b>The key columns.</b> {@code identifier_s} has no {@code id}, and its
 *     fixture holds {@code (1000, 'A')} and {@code (2000, 'A')} - the same
 *     alternateIdentifier under two schemes. Keyed on the pair, 4 rows survive;
 *     keyed on alternateIdentifier alone, 3. See UPSTREAM-SQL-DEFECTS.md
 *     defect 7, which is this table taking MySQL's whole combine down.
 * </ul>
 */
class DuckDbValidationServiceExtensionTest {

	private static final String UUID_NEW_COMPONENTS = "11111111-1111-1111-1111-111111111111";
	private static final String UUID_ORPHANS = "22222222-2222-2222-2222-222222222222";
	private static final String UUID_EXT_MODULE = "33333333-3333-3333-3333-333333333333";
	private static final String UUID_DEP_MODULE = "44444444-4444-4444-4444-444444444444";
	private static final String UUID_IDENTIFIERS = "55555555-5555-5555-5555-555555555555";

	private static final String EXT_MODULE = "32506021000036107";
	private static final String DEP_MODULE = "900000000000207008";

	private static final String STORE = """
			{
			 "formatVersion": 1,
			 "generator": {"tool": "DuckDbValidationServiceExtensionTest"},
			 "sentinels": [
			  {"placeholder": "<RUNID>", "sentinel": "424242424242424242"},
			  {"placeholder": "<ASSERTIONUUID>", "sentinel": "rvfph_assertionuuid_"},
			  {"placeholder": "<PROSPECTIVE>", "sentinel": "rvfph_prospective_"},
			  {"placeholder": "<PREVIOUS>", "sentinel": "rvfph_previous_"},
			  {"placeholder": "<DEPENDENCY>", "sentinel": "rvfph_dependency_"}
			 ],
			 "tableColumns": {
			  "concept_s": "id BIGINT, effectivetime VARCHAR, active VARCHAR, moduleid BIGINT, definitionstatusid BIGINT",
			  "concept_d": "id BIGINT, effectivetime VARCHAR, active VARCHAR, moduleid BIGINT, definitionstatusid BIGINT",
			  "description_s": "id BIGINT, effectivetime VARCHAR, active VARCHAR, moduleid BIGINT, conceptid BIGINT, languagecode VARCHAR, typeid BIGINT, term VARCHAR, casesignificanceid BIGINT",
			  "identifier_s": "identifierschemeid BIGINT, alternateidentifier VARCHAR, effectivetime VARCHAR, active VARCHAR, moduleid BIGINT, referencedcomponentid BIGINT"
			 },
			 "prerequisites": [],
			 "ports": [],
			 "assertions": {
			  "11111111-1111-1111-1111-111111111111": {
			   "file": "new-components.sql", "text": "Concepts new since the previous release",
			   "keywords": "release-type-validation", "severity": "",
			   "statements": [
			    "insert into qa_result (run_id, assertion_id, concept_id, details, component_id, table_name) select 424242424242424242, 'rvfph_assertionuuid_', p.id, 'new', p.id, 'concept_s' from rvfph_prospective_.concept_s p where not exists (select 1 from rvfph_previous_.concept_s v where v.id = p.id)"]},
			  "22222222-2222-2222-2222-222222222222": {
			   "file": "orphans.sql", "text": "Descriptions whose concept is missing",
			   "keywords": "component-centric-validation", "severity": "",
			   "statements": [
			    "insert into qa_result (run_id, assertion_id, concept_id, details, component_id, table_name) select 424242424242424242, 'rvfph_assertionuuid_', d.conceptid, 'orphan', d.id, 'description_s' from rvfph_prospective_.description_s d where not exists (select 1 from rvfph_prospective_.concept_s c where c.id = d.conceptid)"]},
			  "33333333-3333-3333-3333-333333333333": {
			   "file": "ext-module.sql", "text": "Surviving concepts in the extension module",
			   "keywords": "component-centric-validation", "severity": "",
			   "statements": [
			    "insert into qa_result (run_id, assertion_id, concept_id, details, component_id, table_name) select 424242424242424242, 'rvfph_assertionuuid_', id, 'ext', id, 'concept_s' from rvfph_prospective_.concept_s where moduleid = 32506021000036107"]},
			  "44444444-4444-4444-4444-444444444444": {
			   "file": "dep-module.sql", "text": "Surviving concepts in the dependency module",
			   "keywords": "component-centric-validation", "severity": "",
			   "statements": [
			    "insert into qa_result (run_id, assertion_id, concept_id, details, component_id, table_name) select 424242424242424242, 'rvfph_assertionuuid_', id, 'dep', id, 'concept_s' from rvfph_prospective_.concept_s where moduleid = 900000000000207008"]},
			  "55555555-5555-5555-5555-555555555555": {
			   "file": "identifiers.sql", "text": "Surviving identifier rows",
			   "keywords": "component-centric-validation", "severity": "",
			   "statements": [
			    "insert into qa_result (run_id, assertion_id, concept_id, details, component_id, table_name) select 424242424242424242, 'rvfph_assertionuuid_', referencedcomponentid, 'identifier', referencedcomponentid, 'identifier_s' from rvfph_prospective_.identifier_s"]}
			 }
			}
			""";

	private static final String GROUPS_XML = """
			<assertionGroupingStrategy>
			 <group name="extension" includeStandaloneCategories="release-type-validation,component-centric-validation" />
			</assertionGroupingStrategy>
			""";

	@TempDir
	private Path root;

	private Path storeFile;
	private Path corpus;
	private Path work;
	private Path prospective;
	private Path previous;
	private Path dependency;
	private ValidationReportService reportService;
	private WhitelistService whitelistService;

	@BeforeEach
	void setUp() throws Exception {
		storeFile = root.resolve("store.json");
		Files.writeString(storeFile, STORE);

		corpus = root.resolve("corpus");
		Files.createDirectories(corpus);
		Files.writeString(corpus.resolve("groups.xml"), GROUPS_XML);
		Files.writeString(corpus.resolve("policies.xml"), "<policyValues/>");

		work = root.resolve("work");
		Files.createDirectories(work);

		prospective = root.resolve("prospective");
		writeRf2(prospective, "Snapshot/Terminology/sct2_Concept_Snapshot_AU1000036_20260831.txt", """
				id	effectiveTime	active	moduleId	definitionStatusId
				1	20260831	1	%s	900000000000074008
				2	20260831	1	%s	900000000000074008
				5	20260101	1	%s	900000000000074008
				7	20260831	1	%s	900000000000074008
				""".formatted(EXT_MODULE, EXT_MODULE, EXT_MODULE, EXT_MODULE));
		// d2 and d3 hang off international concepts, so they are orphans until
		// the dependency is merged in and resolved after.
		writeRf2(prospective, "Snapshot/Terminology/sct2_Description_Snapshot-en_AU1000036_20260831.txt", """
				id	effectiveTime	active	moduleId	conceptId	languageCode	typeId	term	caseSignificanceId
				900	20260831	1	%s	1	en	900000000000013009	Local term	900000000000448009
				901	20260831	1	%s	100	en	900000000000013009	Term on intl concept	900000000000448009
				902	20260831	1	%s	101	en	900000000000013009	Another intl term	900000000000448009
				""".formatted(EXT_MODULE, EXT_MODULE, EXT_MODULE));
		writeRf2(prospective, "Snapshot/Terminology/sct2_Identifier_Snapshot_AU1000036_20260831.txt", """
				identifierSchemeId	alternateIdentifier	effectiveTime	active	moduleId	referencedComponentId
				1000	B	20260831	1	%s	1
				1000	C	20260831	1	%s	2
				""".formatted(EXT_MODULE, EXT_MODULE));

		previous = root.resolve("previous");
		writeRf2(previous, "Snapshot/Terminology/sct2_Concept_Snapshot_AU1000036_20260731.txt", """
				id	effectiveTime	active	moduleId	definitionStatusId
				1	20260731	1	%s	900000000000074008
				5	20260101	1	%s	900000000000074008
				""".formatted(EXT_MODULE, EXT_MODULE));

		dependency = root.resolve("dependency");
		writeRf2(dependency, "Snapshot/Terminology/sct2_Concept_Snapshot_INT_20260801.txt", """
				id	effectiveTime	active	moduleId	definitionStatusId
				1	20260101	1	%s	900000000000074008
				5	20260831	1	%s	900000000000074008
				7	20260831	1	%s	900000000000074008
				100	20260801	1	%s	900000000000074008
				101	20260801	1	%s	900000000000074008
				""".formatted(DEP_MODULE, DEP_MODULE, DEP_MODULE, DEP_MODULE, DEP_MODULE));
		writeRf2(dependency, "Snapshot/Terminology/sct2_Identifier_Snapshot_INT_20260801.txt", """
				identifierSchemeId	alternateIdentifier	effectiveTime	active	moduleId	referencedComponentId
				1000	A	20260801	1	%s	100
				1000	B	20260801	1	%s	1
				2000	A	20260801	1	%s	101
				""".formatted(DEP_MODULE, DEP_MODULE, DEP_MODULE));

		reportService = mock(ValidationReportService.class);
		whitelistService = mock(WhitelistService.class);
		when(whitelistService.isWhitelistDisabled()).thenReturn(true);
	}

	@Test
	void releaseTypeAssertionsRunAgainstTheExtensionAndTheRestAgainstTheCombinedSchema() {
		ValidationReport report = runExtension();

		// Concepts 2 and 7 are absent from the previous EXTENSION. Had this
		// phase run against the combined schema it would have counted the two
		// international concepts as new as well, and reported 4.
		assertEquals(2L, item(report, UUID_NEW_COMPONENTS).getFailureCount(),
				"release-type assertions see the extension alone");

		// Both descriptions on international concepts resolve, which they can
		// only do against the merged snapshot.
		assertEquals(0L, item(report, UUID_ORPHANS).getFailureCount(),
				"non-release-type assertions see the extension merged with its dependency");
	}

	@Test
	void theMergeKeepsTheLatestRowPerComponentAndGivesTiesToTheDependency() {
		ValidationReport report = runExtension();

		// 1 (extension newer) and 2 (extension only).
		assertEquals(2L, item(report, UUID_EXT_MODULE).getFailureCount());
		// 5 (dependency newer), 7 (TIE - MySQL's >= hands it to the dependency),
		// 100 and 101 (dependency only).
		assertEquals(4L, item(report, UUID_DEP_MODULE).getFailureCount());
	}

	@Test
	void identifierRowsMergeOnTheirCompositeKeyRatherThanAMissingIdColumn() {
		ValidationReport report = runExtension();

		// (1000,A) dependency-only, (1000,B) extension wins on effectiveTime,
		// (1000,C) extension-only, (2000,A) dependency-only. Keyed on
		// alternateIdentifier alone the two A rows would collapse to 3.
		assertEquals(4L, item(report, UUID_IDENTIFIERS).getFailureCount());
	}

	@Test
	void anExtensionRunWithNoDependencyReportsAFailureRatherThanResults() {
		ValidationStatusReport status = run(new DuckDbValidationService.ReleaseDirectories(
				prospective, previous, null));

		assertTrue(status.getFailureMessages().stream()
						.anyMatch(m -> m.contains("dependency")),
				"expected a dependency failure message, got " + status.getFailureMessages());
		// The report object exists - ValidationRunner supplies it and the
		// service will not throw it away - but nothing was written into it. A
		// run that could not combine must not report results beside the failure,
		// and in particular must not report the release-type assertions that DID
		// run before the combine was reached: on their own they are a validation
		// of one third of the corpus wearing a whole run's report.
		assertEquals(0, status.getResultReport().getTotalTestsRun(),
				"a run that could not combine must report no results at all");
	}

	@Test
	void aStandAloneProductSkipsTheCombineAndRunsEverythingAgainstTheExtension() {
		MysqlExecutionConfig config = extensionConfig();
		config.setStandAloneProduct(true);
		ValidationStatusReport status = run(config, new DuckDbValidationService.ReleaseDirectories(
				prospective, previous, dependency));

		ValidationReport report = status.getResultReport();
		assertEquals(List.of(), status.getFailureMessages());
		// No combine, so the international concepts are absent and both
		// descriptions on them are orphans.
		assertEquals(2L, item(report, UUID_ORPHANS).getFailureCount());
		assertEquals(0L, item(report, UUID_DEP_MODULE).getFailureCount());
	}

	private ValidationReport runExtension() {
		ValidationStatusReport status = run(new DuckDbValidationService.ReleaseDirectories(
				prospective, previous, dependency));
		assertEquals(List.of(), status.getFailureMessages());
		return status.getResultReport();
	}

	private ValidationStatusReport run(DuckDbValidationService.ReleaseDirectories releases) {
		return run(extensionConfig(), releases);
	}

	private ValidationStatusReport run(MysqlExecutionConfig config,
			DuckDbValidationService.ReleaseDirectories releases) {
		// Built as ValidationRunner builds it: the no-arg constructor is
		// Jackson's and leaves every collection null.
		ValidationRunConfig runConfig = new ValidationRunConfig();
		runConfig.setRunId(config.getExecutionId());
		ValidationStatusReport status = new ValidationStatusReport(runConfig);
		new DuckDbValidationService(reportService, whitelistService, storeFile.toString(),
				corpus.toString(), work.toString(), "qa_result")
				.runValidations(config, releases, "storage/", status);
		return status;
	}

	private MysqlExecutionConfig extensionConfig() {
		MysqlExecutionConfig config = new MysqlExecutionConfig(11L);
		config.setGroupNames(List.of("extension"));
		config.setExtensionValidation(true);
		config.setReleaseAsAnEdition(false);
		config.setFailureExportMax(10);
		return config;
	}

	private static TestRunItem item(ValidationReport report, String uuid) {
		UUID wanted = UUID.fromString(uuid);
		for (List<TestRunItem> bucket : List.of(report.getAssertionsFailed(),
				report.getAssertionsPassed(), report.getAssertionsWarning(),
				report.getAssertionsSkipped())) {
			for (TestRunItem candidate : bucket) {
				if (wanted.equals(candidate.getAssertionUuid())) {
					return candidate;
				}
			}
		}
		throw new AssertionError(uuid + " was not reported at all");
	}

	private static void writeRf2(Path releaseRoot, String relativePath, String content)
			throws Exception {
		Path file = releaseRoot.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}
}
