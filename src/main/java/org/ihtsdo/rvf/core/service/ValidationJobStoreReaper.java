package org.ihtsdo.rvf.core.service;

import org.ihtsdo.rvf.core.service.config.ValidationJobResourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Deletes the uploaded release from finished validations, keeping their reports.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Nothing removed anything from the validation job store. Every submission
 * leaves its release package under
 * {@code {storageLocation}/files_to_validate/}, and a release package is the
 * largest artefact in the system - 853MB for the AU edition measured here. One
 * nightly validation is therefore about 25GB a month, indefinitely, on the
 * volume that also holds every report. The reports themselves are 37KB each and
 * are not the problem.
 *
 * <p>The uploaded package is also the artefact least worth keeping: it came from
 * somewhere - a build in the release service, a nightly export, the release
 * store - and can be fetched again. What cannot be reconstructed is the report,
 * so this only ever deletes {@code files_to_validate/} and never touches
 * {@code rvf/}.
 *
 * <h2>Local job storage only</h2>
 *
 * <p>Deliberately does nothing when the job store is cloud-backed. Walking an
 * object store to age out keys is slow and costs per request, and both S3 and
 * Azure Blob express exactly this as a lifecycle rule that runs server-side for
 * free. Doing it here as well would be a second, worse implementation of a
 * feature the storage already has.
 */
@Service
public class ValidationJobStoreReaper {

	private static final Logger LOGGER = LoggerFactory.getLogger(ValidationJobStoreReaper.class);

	/** The only directory this class will delete from. */
	static final String FILES_TO_VALIDATE = "files_to_validate";

	private final ValidationJobResourceConfig jobResourceConfig;
	private final int retentionDays;

	@Autowired
	public ValidationJobStoreReaper(ValidationJobResourceConfig jobResourceConfig,
			@Value("${rvf.validation.job.retention.days:7}") int retentionDays) {
		this.jobResourceConfig = jobResourceConfig;
		this.retentionDays = retentionDays;
	}

	/**
	 * Says what it will do, at startup, where an operator will see it.
	 *
	 * <p>Retention that is silent is retention nobody knows about until a file
	 * they wanted is gone.
	 */
	@PostConstruct
	public void announce() {
		if (retentionDays <= 0) {
			LOGGER.info("Uploaded releases are kept indefinitely"
					+ " (rvf.validation.job.retention.days={})", retentionDays);
		} else if (jobResourceConfig.isUseCloud()) {
			LOGGER.info("Uploaded releases: retention is the object store's lifecycle rules;"
					+ " this instance reaps nothing");
		} else {
			LOGGER.info("Uploaded releases under '{}' will be deleted after {} day(s); reports are kept",
					jobResourceConfig.getLocal().getPath(), retentionDays);
		}
	}

	/**
	 * Runs an hour after startup and daily thereafter.
	 *
	 * <p>Not on startup itself: a worker restarting into a full disk should come
	 * up and start consuming before it starts deleting, so that a reaper bug can
	 * never be the reason a deployment fails to boot.
	 */
	@Scheduled(initialDelay = 3_600_000L, fixedDelay = 86_400_000L)
	public void reap() {
		if (retentionDays <= 0) {
			LOGGER.debug("Job store reaping disabled (rvf.validation.job.retention.days={})", retentionDays);
			return;
		}
		if (jobResourceConfig.isUseCloud()) {
			LOGGER.info("Job store is cloud-backed; express retention as a storage lifecycle rule on '{}'"
					+ " rather than here", jobResourceConfig.getCloud().getBucketName());
			return;
		}
		Path root = Path.of(jobResourceConfig.getLocal().getPath());
		if (!Files.isDirectory(root)) {
			LOGGER.debug("Job store root {} does not exist yet, nothing to reap", root.toAbsolutePath());
			return;
		}
		Result result = reap(root, Instant.now().minus(Duration.ofDays(retentionDays)));
		if (result.files() > 0) {
			LOGGER.info("Reaped {} uploaded release file(s), {} MB, older than {} day(s) from {}",
					result.files(), result.bytes() / 1048576, retentionDays, root.toAbsolutePath());
		}
	}

	record Result(int files, long bytes) {}

	/**
	 * Package-private and parameterised on the cutoff so a test can exercise the
	 * real walk against a real directory rather than a mock of one.
	 */
	Result reap(Path root, Instant cutoff) {
		List<Path> uploads = new ArrayList<>();
		try (Stream<Path> tree = Files.walk(root)) {
			tree.filter(Files::isDirectory)
					.filter(p -> FILES_TO_VALIDATE.equals(p.getFileName().toString()))
					.forEach(uploads::add);
		} catch (IOException e) {
			LOGGER.warn("Could not walk the job store at {}: {}", root.toAbsolutePath(), e.toString());
			return new Result(0, 0);
		}

		int files = 0;
		long bytes = 0;
		for (Path dir : uploads) {
			try (Stream<Path> entries = Files.list(dir)) {
				for (Path file : entries.filter(Files::isRegularFile).toList()) {
					try {
						if (Files.getLastModifiedTime(file).toInstant().isAfter(cutoff)) {
							continue;
						}
						long size = Files.size(file);
						Files.delete(file);
						files++;
						bytes += size;
					} catch (IOException e) {
						// A file being read by an in-flight validation, or a
						// permission problem. Skip it and keep going: a reaper
						// that aborts on the first awkward file reclaims nothing.
						LOGGER.warn("Could not reap {}: {}", file, e.toString());
					}
				}
			} catch (IOException e) {
				LOGGER.warn("Could not list {}: {}", dir, e.toString());
			}
		}
		return new Result(files, bytes);
	}

	/**
	 * Registers Spring's scheduler only when reaping is switched on, so an
	 * instance that opts out gains no background thread.
	 */
	@Configuration
	@EnableScheduling
	@ConditionalOnProperty(name = "rvf.validation.job.retention.enabled", havingValue = "true",
			matchIfMissing = true)
	static class SchedulingConfig {
	}

	/** Exposed for the startup log and for tests to assert the default. */
	public int getRetentionDays() {
		return retentionDays;
	}
}
