package org.ihtsdo.rvf.execution.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ihtsdo.rvf.entity.ValidationReport;
import org.ihtsdo.rvf.execution.service.config.ValidationRunConfig;

import com.google.gson.annotations.SerializedName;

public class ValidationStatusReport {
	private ValidationRunConfig validationConfig;
	private Map<String,String> reportSummary;
	private List<String> failureMessages;
	@SerializedName("TestResult")
	private ValidationReport resultReport;
	private Date startTime;
	private Date endTime;
	private int totalRF2FilesLoaded;

	@SerializedName("rf2Files")
	private List<String> rf2FilesLoaded;
	
	public ValidationStatusReport(ValidationRunConfig validationConfig) {
		this.validationConfig = validationConfig;
		totalRF2FilesLoaded = -1;
		rf2FilesLoaded = new ArrayList<>();
		failureMessages = new ArrayList<>();
		reportSummary = new HashMap<>();
	}

	public ValidationReport getResultReport() {
		return resultReport;
	}

	public void setResultReport(ValidationReport resultReport) {
		this.resultReport = resultReport;
	}

	public List<String> getFailureMessages() {
		return this.failureMessages;
	}

	public void addFailureMessage(String failureMessage) {
		this.failureMessages.add(failureMessage);
	}

	public Date getStartTime() {
		return startTime;
	}

	public void setStartTime(Date startTime) {
		this.startTime = startTime;
	}

	public Date getEndTime() {
		return endTime;
	}

	public void setEndTime(Date endTime) {
		this.endTime = endTime;
	}

	public ValidationRunConfig getValidationConfig() {
		return validationConfig;
	}

	public void setRF2Files(List<String> rf2FilesLoaded) {
		this.rf2FilesLoaded = rf2FilesLoaded;
	}

	public int getTotalRF2FilesLoaded() {
		if (totalRF2FilesLoaded == -1) {
			return rf2FilesLoaded.size();
		}
		return this.totalRF2FilesLoaded;
	}

	public void setTotalRF2FilesLoaded(int totalRF2FilesLoaded) {
		this.totalRF2FilesLoaded = totalRF2FilesLoaded;
	}

	public List<String> getRf2FilesLoaded() {
		return rf2FilesLoaded;
	}

	public void setRf2FilesLoaded(List<String> rf2FilesLoaded) {
		this.rf2FilesLoaded = rf2FilesLoaded;
	}

	public Map<String, String> getReportSummary() {
		return reportSummary;
	}

	public void setReportSummary(Map<String, String> reportSummary) {
		this.reportSummary = reportSummary;
	}
}
