package org.ihtsdo.rvf.core.service;

import org.ihtsdo.otf.sqs.service.dto.ConceptResult;
import org.ihtsdo.rvf.core.data.model.TestRunItem;
import org.ihtsdo.rvf.core.data.model.ValidationReport;
import org.junit.jupiter.api.Test;
import org.snomed.quality.validator.mrcm.Assertion;
import org.snomed.quality.validator.mrcm.ContentType;
import org.snomed.quality.validator.mrcm.ValidationRun;
import org.snomed.quality.validator.mrcm.ValidationType;
import org.snomed.quality.validator.mrcm.model.Attribute;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every MRCM assertion the validator completes must appear in the report.
 *
 * <p>The validator splits a violation into "current" (dated on the release
 * under test) and "previous" (published earlier). RVF built the failure from
 * the current ones only, and discarded the assertion when there were none - so
 * an assertion whose violations were all in published content was in no
 * bucket at all. Two real AU violations were hidden that way, and the total
 * moved between 979 and 980 depending on which module filter happened to
 * remove them.
 *
 * <p>Ruling: a violation is a violation whenever it was published. Previous
 * violations fail exactly as current ones do.
 */
class MRCMValidationServiceReportTest {

	private static final String RELEASE = "20261031";

	@Test
	void anAssertionWhoseViolationsAreAllPreviouslyPublishedIsReportedAsFailed() {
		ValidationRun run = new ValidationRun(RELEASE, ContentType.STATED, false);
		run.addCompletedAssertion(assertion("a8c88cca-305c-40e8-bf03-2d6d03d47755",
				List.of(), List.of(concept("1165901000168107", "20240930"), concept("50740011000036105", "20230531"))));

		ValidationReport report = extract(run);

		TestRunItem failed = only(report.getAssertionsFailed());
		assertEquals(2L, failed.getFailureCount());
		assertEquals(List.of("1165901000168107", "50740011000036105"),
				failed.getFirstNInstances().stream().map(f -> f.getConceptId()).toList());
	}

	@Test
	void currentAndPreviousViolationsAreCountedTogetherOnce() {
		ValidationRun run = new ValidationRun(RELEASE, ContentType.INFERRED, false);
		run.addCompletedAssertion(assertion("d21a8d65-0000-0000-0000-000000000000",
				List.of(concept("1", RELEASE)),
				List.of(concept("2", "20240930"), concept("1", RELEASE))));

		assertEquals(2L, only(extract(run).getAssertionsFailed()).getFailureCount());
	}

	@Test
	void noCompletedAssertionIsMissingFromTheReport() {
		ValidationRun run = new ValidationRun(RELEASE, ContentType.INFERRED, false);
		run.addCompletedAssertion(assertion("00000000-0000-0000-0000-000000000001", List.of(), List.of()));
		run.addCompletedAssertion(assertion("00000000-0000-0000-0000-000000000002",
				List.of(), List.of(concept("3", "20200131"))));
		run.addCompletedAssertion(assertion("00000000-0000-0000-0000-000000000003",
				List.of(concept("4", RELEASE)), List.of()));

		ValidationReport report = extract(run);
		long reported = Stream.of(report.getAssertionsFailed(), report.getAssertionsWarning(),
				report.getAssertionsPassed()).mapToLong(List::size).sum();
		assertEquals(3, reported, "every completed assertion lands in exactly one bucket");
		assertEquals(1, report.getAssertionsPassed().size());
		assertEquals(2, report.getAssertionsFailed().size());
	}

	private static Assertion assertion(String uuid, List<ConceptResult> current, List<ConceptResult> previous) {
		Attribute attribute = new Attribute("272741003", "723596005");
		attribute.setUuid(UUID.fromString(uuid));
		return new Assertion(attribute, ValidationType.ATTRIBUTE_DOMAIN, "", null,
				new ArrayList<>(current), new ArrayList<>(previous), "^ 723264001");
	}

	private static ConceptResult concept(String id, String effectiveTime) {
		return new ConceptResult(id, effectiveTime, "1", "32506021000036107", "900000000000074008", "fsn " + id, List.of());
	}

	private static ValidationReport extract(ValidationRun run) {
		ValidationReport report = new ValidationReport();
		ReflectionTestUtils.invokeMethod(new MRCMValidationService(), "extractTestResults",
				100, report, run, run.getContentType());
		return report;
	}

	private static TestRunItem only(List<TestRunItem> items) {
		assertTrue(items != null && items.size() == 1, "expected exactly one item, got " + items);
		return items.get(0);
	}
}
