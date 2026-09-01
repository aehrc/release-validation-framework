package org.ihtsdo.rvf.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.ihtsdo.rvf.core.service.ReleaseCatalogue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/**
 * The published releases this instance keeps, so a prospective release can be
 * validated against one.
 *
 * <p>Engine-agnostic. It was {@code @ConditionalOnMysqlEngine} because it spoke
 * directly to {@code ReleaseDataManager}, whose idea of a kept release is a
 * loaded schema. That made the whole notion of "RVF already has last month's
 * release" a MySQL feature, which is backwards: it is the point of a nightly.
 * {@link ReleaseCatalogue} has an implementation per engine - schemas under
 * MySQL, stored RF2 packages under DuckDB - and the name this returns is the name
 * to pass back as {@code previousRelease}.
 *
 * <p>The GET methods do not download a release. They answer what is available.
 */
@RestController
@RequestMapping("/releases")
@Tag(name = "Published Releases")
public class ReleaseController {

	private static final Logger logger = LoggerFactory.getLogger(ReleaseController.class);

	private final ReleaseCatalogue releaseCatalogue;

	@Autowired
	public ReleaseController(ReleaseCatalogue releaseCatalogue) {
		this.releaseCatalogue = releaseCatalogue;
	}

	@RequestMapping(value = "{product}/{version}", method = RequestMethod.POST, consumes = "multipart/form-data")
	@ResponseBody
	@Operation(summary = "Keep a published release version",
			description = "Keeps a published release so later validations can be compared against it. "
					+ "Returns the name to pass as previousRelease: under the MySQL engine that is "
					+ "rvf_{product}_{version}; under the DuckDB engine it is the package filename, "
					+ "because that engine keeps the RF2 package rather than a loaded schema.")
	public ResponseEntity<?> uploadRelease(
			@Parameter(description = "The published RF2 zip package") @RequestParam(value = "file") final MultipartFile file,
			@Parameter(description = "The short product name e.g int for international RF2 release") @PathVariable final String product,
			@Parameter(description = "The release date in yyyymmdd e.g 20170131") @PathVariable final String version) {
		try {
			String name = releaseCatalogue.store(file, product, version);
			logger.info("Kept published release as '{}'", name);
			return new ResponseEntity<>(name, HttpStatus.OK);
		} catch (final Exception e) {
			logger.warn("Failed to keep the uploaded release", e);
			return new ResponseEntity<>("Failed to keep the uploaded release: " + e.getMessage(),
					HttpStatus.BAD_REQUEST);
		}
	}

	@RequestMapping(value = "{version}", method = RequestMethod.GET)
	@ResponseBody
	@Operation(summary = "Check a given release is available",
			description = "Checks whether a release is available to validate against. The name is "
					+ "whatever this instance reports from GET /releases - a schema name like "
					+ "rvf_int_20170131 under MySQL, or a package filename under DuckDB.")
	public ResponseEntity<?> getRelease(
			@Parameter(description = "The release name as reported by GET /releases") @PathVariable final String version) {
		if (releaseCatalogue.contains(version)) {
			return new ResponseEntity<>(Boolean.TRUE, HttpStatus.OK);
		}
		return new ResponseEntity<>(Boolean.FALSE, HttpStatus.NOT_FOUND);
	}

	@RequestMapping(value = "", method = RequestMethod.GET)
	@ResponseBody
	@ResponseStatus(HttpStatus.OK)
	@Operation(summary = "Get every release available to validate against",
			description = "Each name can be passed as previousRelease or dependencyRelease on a "
					+ "validation request.")
	public Set<String> getAllKnownReleases() {
		return releaseCatalogue.names();
	}
}
