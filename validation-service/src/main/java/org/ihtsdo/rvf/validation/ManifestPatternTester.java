package org.ihtsdo.rvf.validation;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.ihtsdo.rvf.validation.log.ValidationLog;
import org.ihtsdo.rvf.validation.model.FileElement;
import org.ihtsdo.rvf.validation.model.Folder;
import org.ihtsdo.rvf.validation.model.ManifestFile;
import org.ihtsdo.rvf.validation.resource.ResourceProvider;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class ManifestPatternTester {

	private static final String MANIFEST_STRUCTURE_TEST = "ManifestPackageStructureTest";
	private static final String MANIFEST = "manifest.xml";
	private static final String ALPHA = "_ALPHA_";
	private static final String BETA = "_BETA_";
	private static final String X_PREFIX = "x";
	private static final String README_FILE_PREFIX = "Readme";

	private final ValidationLog validationLog;
	private final ResourceProvider resourceManager;
	private final ManifestFile manifestFile;
	private final TestReportable report;

	public ManifestPatternTester(final ValidationLog validationLog, final ResourceProvider resourceManager, final ManifestFile manifestFile,
			final TestReportable report) {
		this.validationLog = validationLog;
		this.resourceManager = resourceManager;
		this.manifestFile = manifestFile;
		this.report = report;
	}

	public void runTests() {
		final Date startTime = new Date();
		if (manifestFile == null || manifestFile.getListing() == null) {
			validationLog.assertionError("Manifest file expected but not found.");
			report.addError("0", startTime, MANIFEST, MANIFEST, MANIFEST, MANIFEST_STRUCTURE_TEST, "", "No Manifest File Found", MANIFEST,null);
		} else {
			final List<Folder> folders = manifestFile.getListing().getFolders();
			final AtomicInteger folderCounter = new AtomicInteger(0);
			final AtomicInteger fileCounter = new AtomicInteger(0);
			testManifestXmlStructure(folders, startTime);
			testStructure(folders, folderCounter, fileCounter, startTime);
			validationLog.info("Manifest file structure testing for {} files and {} folders completed in {} milliseconds.", fileCounter, folderCounter, (new Date().getTime() - startTime.getTime()));
		}
	}

	private void testManifestXmlStructure(List<Folder> folders, Date startTime) {
		if(folders.isEmpty()) return;;
		String packageName = folders.get(0).getFolderName();
		boolean isProductionVersion = true;
		if(StringUtils.containsIgnoreCase(packageName,ALPHA) || StringUtils.containsIgnoreCase(packageName,BETA)) {
			isProductionVersion = false;
			if(!packageName.startsWith(X_PREFIX)) {
				validationLog.assertionError("In manifest configuration, release package is a non-production build but does not have 'x' prefix in package name: ", packageName);
				report.addError(packageName, startTime, packageName, packageName,"", MANIFEST_STRUCTURE_TEST,"","Release package is not a production build but does not have 'x' prefix in package name","Non-production package must have 'x' prefix in package name",null);
			}
		}
		String packageNameFormat = "[x]*SnomedCT_.*_\\d{8}T\\d{6}Z";
		boolean isMatched = Pattern.matches(packageNameFormat, packageName);
		if(!isMatched) {
			if(isProductionVersion) {
				validationLog.assertionError("In manifest configuration, package release name {} is not formatted correctly, should be in format SnomedCT_*_[ddMMYYYY]T[HHmmss]Z", packageName);
				report.addError(packageName, startTime, packageName, packageName,"", MANIFEST_STRUCTURE_TEST,"","Release package name in manifest file is not formatted correctly","Release package name in manifest file should have format SnomedCT_*_[ddMMYYYY]T[HHmmss]Z.zip",null);
			} else {
				validationLog.assertionError("In manifest configuration, package release name {} is not formatted correctly, should be in format xSnomedCT_*_[ddMMYYYY]T[HHmmss]Z", packageName);
				report.addError(packageName, startTime, packageName, packageName,"", MANIFEST_STRUCTURE_TEST,"","Release package name in manifest file is not formatted correctly","Release package name in manifest file should have format xSnomedCT_*_[ddMMYYYY]T[HHmmss]Z.zip",null);
			}
		}
		List<String> filesListInManifest = new ArrayList<>();
		findFilesInManifest(folders, filesListInManifest);
		boolean hasReadmeFile = false;
		for (String filepath : filesListInManifest) {
			String fileName = FilenameUtils.getName(filepath);
			if(StringUtils.containsIgnoreCase(fileName,README_FILE_PREFIX)) {
				hasReadmeFile = true;
				break;
			}
		}
		if(!hasReadmeFile) {
			validationLog.assertionError("In manifest configuration, there is no Readme file");
			report.addError(MANIFEST, startTime, MANIFEST, MANIFEST,"", MANIFEST_STRUCTURE_TEST,"","In manifest configuration, there is no Readme file","There should be Readme file present in very package",null);
		}
		if(!isProductionVersion) {
			for (String filepath : filesListInManifest) {
				String fileName = FilenameUtils.getName(filepath);
				if(!fileName.startsWith("x") && !StringUtils.containsIgnoreCase(fileName,README_FILE_PREFIX)) {
					validationLog.assertionError("In manifest configuration, there should be 'x' prefix in filename when the release package is a non-production: {}", filepath);
					report.addError("", startTime, fileName, fileName, fileName, MANIFEST_STRUCTURE_TEST, "", "In manifest configuration, there should be 'x' prefix in filename when the release package is a non-production", filepath,null);
				}
			}
		}

	}

	private void findFilesInManifest(final List<Folder> folders, List<String> filesList) {
		if (folders.isEmpty()) return;
		for (final Folder folder : folders) {
			final List<FileElement> files = folder.getFiles();
			for (final FileElement file : files) {
				final String filename = file.getFileName();
				filesList.add(filename);
			}
			final List<Folder> children = folder.getFolders();
			if (!children.isEmpty()) {
				findFilesInManifest(children, filesList);
			}
		}
	}


	private void testStructure(final List<Folder> folders, final AtomicInteger folderCounter, final AtomicInteger fileCounter, final Date startTime) {
		if (folders.isEmpty()) return;
		for (final Folder folder : folders) {
			folderCounter.incrementAndGet();
			final String name = folder.getFolderName();
			final boolean match = resourceManager.match(name);
			if (!match) {
				validationLog.assertionError("Invalid package structure expected directory at {} but found none", name);
				report.addError(folderCounter + "-" + fileCounter, startTime, name, name, name, MANIFEST_STRUCTURE_TEST, "", "No Folder Found", name,null);
			} else {
				report.addSuccess(folderCounter + "-" + fileCounter, startTime, name, name, "", MANIFEST_STRUCTURE_TEST, "");
			}
			final List<FileElement> files = folder.getFiles();
			for (final FileElement file : files) {
				fileCounter.incrementAndGet();
				final String filename = file.getFileName();
				if (!(resourceManager.match(filename))) {
					validationLog.assertionError("Invalid package structure expected file at {} but found none", filename);
					report.addError(folderCounter + "-" + fileCounter, startTime, filename, filename, filename, MANIFEST_STRUCTURE_TEST, "", "No File Found", filename,null);
				} else {
					report.addSuccess(folderCounter + "-" + fileCounter, startTime, filename, filename, "", MANIFEST_STRUCTURE_TEST, "");
				}
			}
			final List<Folder> children = folder.getFolders();
			if (!children.isEmpty()) {
				testStructure(children, folderCounter, fileCounter, startTime);
			}
		}
	}
}
