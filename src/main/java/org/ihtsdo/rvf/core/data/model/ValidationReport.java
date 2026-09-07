package org.ihtsdo.rvf.core.data.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ValidationReport {

	private Long executionId;
	private long timeTakenInSeconds;
	private String reportUrl;
	private int totalTestsRun;
	private int totalSkips;
	private int totalWarnings;
	private int totalFailures;
	private int totalTestsIncomplete;
	private final List<TestRunItem> assertionsFailed;
	private final List<TestRunItem> assertionsWarning;
	private final List<TestRunItem> assertionsSkipped;
	private final List<TestRunItem> assertionsPassed;

	/**
	 * Which assertion packs produced this report.
	 *
	 * <p>Empty on the MySQL engine and on any DuckDB run using only the bundled
	 * store, which is every deployment today. It stops being optional the
	 * moment assertions are fetched at runtime: baked into the image, the image
	 * tag named the corpus, and nothing else does. Without this, "which
	 * assertions produced this report" is unanswerable, and trading a rebuild
	 * for silent drift is no trade.
	 *
	 * <p>Name, version, digest and assertion count per pack - the digest
	 * because a version is a label a publisher controls and a digest is not.
	 */
	private List<AssertionPackRecord> assertionPacks = new ArrayList<>();

	/** One pack's identity, as it appears in a report. */
	public record AssertionPackRecord(String name, String version, String digest,
			int assertions) {
	}

	public ValidationReport() {
		assertionsFailed = new ArrayList<>();
		assertionsWarning = new ArrayList<>();
		assertionsSkipped = new ArrayList<>();
		assertionsPassed = new ArrayList<>();
		totalTestsRun = 0;
		totalSkips = 0;
		totalWarnings = 0;
		totalFailures = 0;
		totalTestsIncomplete = 0;
	}

	public Long getExecutionId() {
		return executionId;
	}

	public void setExecutionId(Long executionId) {
		this.executionId = executionId;
	}

	public long getTimeTakenInSeconds() {
		return timeTakenInSeconds;
	}

	public String getReportUrl() {
		return reportUrl;
	}

	public void setReportUrl(String reportUrl) {
		this.reportUrl = reportUrl;
	}

	public List<AssertionPackRecord> getAssertionPacks() {
		return assertionPacks;
	}

	public void setAssertionPacks(List<AssertionPackRecord> assertionPacks) {
		this.assertionPacks = assertionPacks == null ? new ArrayList<>() : assertionPacks;
	}

	public int getTotalTestsRun() {
		return totalTestsRun;
	}

	public int getTotalSkips() {
		return totalSkips;
	}

	public int getTotalFailures() {
		return totalFailures;
	}

	public int getTotalTestsIncomplete() {
		return totalTestsIncomplete;
	}

	public int getTotalWarnings() {
		return totalWarnings;
	}

	public List<TestRunItem> getAssertionsSkipped() {
		return assertionsSkipped;
	}

	public List<TestRunItem> getAssertionsFailed() {
		return assertionsFailed;
	}

	public List<TestRunItem> getAssertionsPassed() {
		return assertionsPassed;
	}

	public List<TestRunItem> getAssertionsWarning() {
		return assertionsWarning;
	}

	public void setTotalTestsRun(int totalTestsRun) {
		this.totalTestsRun = totalTestsRun;
	}

	public void setTotalSkips(int totalSkips) {
		this.totalSkips = totalSkips;
	}

	public void setTotalWarnings(int totalWarnings) {
		this.totalWarnings = totalWarnings;
	}

	public void setTotalFailures(int totalFailures) {
		this.totalFailures = totalFailures;
	}

	public void setTotalTestsIncomplete(int totalTestsIncomplete) {
		this.totalTestsIncomplete = totalTestsIncomplete;
	}

	public void addSkippedAssertions(List<TestRunItem> skippedItems){
		if (hasItems(skippedItems)) {
			assertionsSkipped.addAll(skippedItems);
			int noOfItems = skippedItems.size();
			totalSkips += noOfItems;
			totalTestsRun += noOfItems;
		}
	}

	public void addWarningAssertions(List<TestRunItem> warningItems){
		if (hasItems(warningItems)) {
			assertionsWarning.addAll(warningItems);
			int noOfItems = warningItems.size();
			totalWarnings += noOfItems;
			totalTestsRun += noOfItems;
		}
	}

	public void addFailedAssertions(List<TestRunItem> failedItems){
		if (hasItems(failedItems)) {
			assertionsFailed.addAll(failedItems);
			int noOfItems = failedItems.size();
			totalFailures += noOfItems;
			totalTestsRun += noOfItems;
		}
	}

	public void addIncompleteAssesrtions(List<TestRunItem> incompleteItems) {
		if (hasItems(incompleteItems)) {
			int noOfItems = incompleteItems.size();
			totalTestsIncomplete += noOfItems;
		}
	}

	public void addPassedAssertions(List<TestRunItem> passedItems){
		if (hasItems(passedItems)) {
			assertionsPassed.addAll(passedItems);
			int noOfItems = passedItems.size();
			totalTestsRun += noOfItems;
		}
	}

	public void setTimeTakenInSeconds(long timeTakenInSeconds) {
		this.timeTakenInSeconds = timeTakenInSeconds;
	}

	public void addTimeTaken(long seconds){
		timeTakenInSeconds += seconds;
	}
	
	public void sortAssertionLists() {
		Collections.sort(assertionsFailed);
		Collections.sort(assertionsWarning);
		Collections.sort(assertionsSkipped);
		Collections.sort(assertionsPassed);
	}

	private boolean hasItems(List<TestRunItem> items) {
		return ((items != null) && (!items.isEmpty()));
	}
}
