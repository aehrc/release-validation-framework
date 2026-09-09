package org.ihtsdo.rvf.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.io.IOUtils;
import org.ihtsdo.rvf.core.service.FailureExportService;
import org.ihtsdo.rvf.core.service.ValidationReportService;
import org.ihtsdo.rvf.core.service.ValidationReportService.State;
import org.ihtsdo.rvf.core.service.ValidationRunCatalogue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/result")
@Tag(name = "Validation Results")
public class ResultController {

	private static final String MESSAGE = "Message";
	@Autowired
	private ValidationReportService reportService;

	@Autowired
	private ValidationRunCatalogue runCatalogue;

	@Autowired
	private FailureExportService failureService;

	@GetMapping
	@Operation(summary = "List the validation runs held in the job store, newest first.",
			description = "Returns the run id, storage location, state and headline counts for each run, "
					+ "which is what is needed to open a report. Without this a caller has to have kept a "
					+ "note of the run id and storage location from when the validation was submitted. "
					+ "The report itself is not included: fetch it from /result/{runId}.")
	public ResponseEntity<List<ValidationRunCatalogue.RunSummary>> listResults(
			@Parameter(description = "How many runs to return, newest first. Defaults to 50.")
			@RequestParam(value = "limit", required = false, defaultValue = "50") final int limit) {
		return ResponseEntity.ok(runCatalogue.list(Math.clamp(limit, 1, 500)));
	}

	@RequestMapping(value = "{runId}", method = RequestMethod.GET)
	@Operation(summary = "Retrieve the validation report for a given run id and storage location.", description = "Retrieves the validation report specified by the runId and storageLocation.")
	public ResponseEntity<Map<String, Object>> getResult(
			@Parameter(description = "Unique number") @PathVariable final Long runId,
			@RequestParam(value = "storageLocation") final String storageLocation)
			throws IOException {
		// Can we find an rvf status file at that location? Return 404 if not.
		final Map<String, Object> responseMap = new LinkedHashMap<>();
		final State state = reportService.getCurrentState(runId,
				storageLocation);
		final HttpStatus returnStatus = HttpStatus.OK;
		if (state == null) {
			responseMap.put(MESSAGE, "No validation state found at " + storageLocation);
		} else {
			responseMap.put("status", state.toString());
            switch (state) {
                case READY, QUEUED -> responseMap.put(MESSAGE, "Validation hasn't started running yet!");
                case RUNNING -> {
                    String progress = reportService.recoverProgress(storageLocation);
                    responseMap.put(MESSAGE, "Validation is still running.");
                    responseMap.put("Progress", progress);
                }
                default -> reportService.recoverResult(responseMap, storageLocation);
            }
		}
		return new ResponseEntity<>(responseMap, returnStatus);
	}

	@RequestMapping(value = "/structure/{runId}", method = RequestMethod.GET)
	@ResponseBody
	@Operation(hidden = true, summary = "Returns a structure test report", description = "Retrieves the structure test report as txt file for the runId and storage location.")
	public FileSystemResource getStructureReport(
			@PathVariable final Long runId,
			@RequestParam(value = "storageLocation") final String storageLocation)
			throws IOException {
		File tempReport = File.createTempFile(
				"structure_validation_" + runId.toString(), ".txt");
		try (Writer writer = new FileWriter(tempReport);
				InputStream reportInputStream = reportService
						.getStructureReport(runId, storageLocation)) {
			if (reportInputStream != null) {
				IOUtils.copy(reportInputStream, writer, "UTF-8");
			} else {
				String msg = "No structure report found for runId:" + runId
						+ " at " + storageLocation;
				writer.append(msg);
			}
			return new FileSystemResource(tempReport);
		}
	}

	/**
	 * Every failing row for a run, not the sample the report carries.
	 *
	 * <p>{@code results.json} keeps only {@code failureExportMax} instances per
	 * assertion - 10 by default, 100 on the nightly - so it answers "40,000
	 * concepts failed" and cannot answer "which ones". The full set is already
	 * archived per run as {@code failures.parquet}; nothing exposed it.
	 *
	 * <p>CSV is streamed straight out of the parquet by DuckDB rather than
	 * assembled in memory, because a bad night is millions of rows: measured at
	 * 20.8MB per million as parquet against 177.3MB as CSV. {@code format=parquet}
	 * hands back the archive itself, which is the better answer for tooling -
	 * DuckDB and pandas both read it directly.
	 *
	 * <p>{@code assertionId} filters in the query, so asking for one assertion's
	 * failures does not transfer the whole run.
	 */
	@GetMapping(value = "{runId}/failures")
	@Operation(summary = "Download every failing row for a run, uncapped.",
			description = "The report holds only the first N instances per assertion. This streams the "
					+ "complete set from the run's failure archive. format=csv (default) or parquet; "
					+ "assertionId narrows it to one assertion.")
	public ResponseEntity<StreamingResponseBody> getFailures(
			@Parameter(description = "Unique number") @PathVariable final Long runId,
			@RequestParam(value = "storageLocation") final String storageLocation,
			@Parameter(description = "Only this assertion's failures")
			@RequestParam(value = "assertionId", required = false) final String assertionId,
			@Parameter(description = "csv or parquet") @RequestParam(value = "format",
					required = false, defaultValue = "csv") final String format)
			throws IOException {

		File archive = failureService.stageArchive(storageLocation);
		if (archive == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}

		// A filtered export that matches nothing is a 404, not a 200 carrying a
		// header line and no rows. The report UI offers "all N as CSV" beside
		// every failure, and for a run archived before Drools and MRCM rows
		// reached the archive that link produced an empty file - which reads as
		// "no failures" while the report beside it says 5,158. The count is a
		// column-statistics read, about a millisecond on a million rows.
		if (assertionId != null && !assertionId.isBlank() && failureService.countFor(archive, assertionId) == 0) {
			Files.deleteIfExists(archive.toPath());
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}

		boolean parquet = "parquet".equalsIgnoreCase(format);
		String filename = "failures-" + runId + (parquet ? ".parquet" : ".csv");
		StreamingResponseBody body = out -> {
			try {
				if (parquet) {
					try (InputStream in = new FileInputStream(archive)) {
						IOUtils.copy(in, out);
					}
				} else {
					failureService.writeCsv(archive, assertionId, out);
				}
			} finally {
				Files.deleteIfExists(archive.toPath());
			}
		};
		return ResponseEntity.ok()
				.header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
				.header("Content-Type", parquet ? "application/vnd.apache.parquet" : "text/csv")
				.body(body);
	}
}
