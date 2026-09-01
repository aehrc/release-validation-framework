package org.ihtsdo.rvf.core.messaging;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.ihtsdo.rvf.core.service.ValidationReportService;
import org.ihtsdo.rvf.core.service.ValidationReportService.State;
import org.ihtsdo.rvf.core.service.ValidationRunner;
import org.ihtsdo.rvf.core.service.config.ValidationRunConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.SessionCallback;
import org.springframework.stereotype.Component;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

/**
 * Runs exactly one queued validation, then exits, so a scheduler can own the
 * worker lifecycle: KEDA {@code ScaledJob}, Container Apps event-driven jobs, or
 * anything else that creates one process per queued message.
 *
 * <p>The alternative already in the codebase is {@link ValidationMessageListener}
 * - a long-lived {@code @JmsListener} scaled by replica count. That is still the
 * default and is the better fit when runs are frequent, because it pays JVM
 * start once per pod rather than once per run. This mode exists for the opposite
 * case: infrequent runs, where a pool sized for the busy moment sits idle the
 * rest of the time, and where a crashed run should not be able to poison a
 * long-lived worker.
 *
 * <h2>Why the receive is transacted rather than a plain receive</h2>
 *
 * <p>{@code JmsTemplate.receive()} acknowledges as soon as the message is
 * handed over and closes the session. The validation then runs for minutes
 * outside any transaction, so a process killed mid-run - a spot node reclaimed,
 * an OOM, a scale-in - loses the message. The retry the scheduler then performs
 * would find an empty queue, exit 0, and report success having validated
 * nothing. That is worse than not retrying at all.
 *
 * <p>So the receive, the run and the acknowledgement happen inside one
 * transacted session: commit only once the run has reached a terminal state, and
 * roll back otherwise so the broker redelivers. The cost is a JMS session held
 * open for the duration of a run, which is what the {@code @JmsListener} does
 * too.
 *
 * <p>Redelivery is bounded by the broker, not here. ActiveMQ's default policy is
 * six attempts before the message goes to the dead-letter queue, which is the
 * behaviour you want for a message that kills every worker that touches it.
 *
 * <h2>Exit codes, and what they deliberately do not mean</h2>
 *
 * <pre>
 *   0   ran to a terminal COMPLETE state, or there was no message to take
 *   1   the run reached FAILED, or the message could not be processed
 * </pre>
 *
 * <p><b>A validation that finds assertion failures exits 0.</b> Findings are the
 * product, not an error. {@link ValidationRunner} distinguishes these already:
 * it writes {@code FAILED} only when {@code statusReport.getFailureMessages()}
 * is non-empty, which holds infrastructure errors, and writes {@code COMPLETE}
 * for a run that worked whatever the assertions said. Conflating the two would
 * make a scheduler retry a release that is simply invalid.
 *
 * <p>An empty queue exits 0 on purpose. Under a {@code ScaledJob} one job is
 * created per message, but the queue is a race: another worker may already have
 * taken it. That is normal, not a failure, and a non-zero exit there would show
 * up as a broken job on every scale-up.
 */
@Component
@ConditionalOnProperty(name = "rvf.execution.oneShot", havingValue = "true")
public class ValidationOneShotRunner implements ApplicationRunner {

	private static final Logger LOGGER = LoggerFactory.getLogger(ValidationOneShotRunner.class);

	static final int EXIT_OK = 0;
	static final int EXIT_FAILED = 1;

	private final ValidationRunner runner;
	private final ValidationReportService reportService;
	private final ConnectionFactory connectionFactory;
	private final String queueName;
	private final long receiveTimeoutMillis;
	private final boolean isWorker;
	private final Runtime runtime;

	@Autowired
	public ValidationOneShotRunner(ValidationRunner runner,
			ValidationReportService reportService,
			ConnectionFactory connectionFactory,
			@Value("${rvf.validation.queue.name}") String queueName,
			@Value("${rvf.execution.oneShot.receiveTimeoutSeconds:60}") long receiveTimeoutSeconds,
			@Value("${rvf.execution.isWorker:true}") boolean isWorker) {
		this(runner, reportService, connectionFactory, queueName, receiveTimeoutSeconds, isWorker, Runtime.getRuntime());
	}

	ValidationOneShotRunner(ValidationRunner runner,
			ValidationReportService reportService,
			ConnectionFactory connectionFactory,
			String queueName,
			long receiveTimeoutSeconds,
			boolean isWorker,
			Runtime runtime) {
		this.runner = runner;
		this.reportService = reportService;
		this.connectionFactory = connectionFactory;
		this.queueName = queueName;
		this.receiveTimeoutMillis = receiveTimeoutSeconds * 1000L;
		this.isWorker = isWorker;
		this.runtime = runtime;
	}

	/**
	 * Two consumers of one queue in one process would each take a share of the
	 * messages, and this one exits after its first - so the listener's messages
	 * would be run by a process that is shutting down. Since
	 * {@code rvf.execution.isWorker} defaults to <em>true</em>, enabling one-shot
	 * and forgetting to disable the listener is the likely mistake, so fail at
	 * startup rather than half-work.
	 */
	@Override
	public void run(ApplicationArguments args) {
		if (isWorker) {
			throw new IllegalStateException(
					"rvf.execution.oneShot=true requires rvf.execution.isWorker=false: "
					+ "the JmsListener and the one-shot runner would compete for the same queue");
		}
		runtime.halt(consumeOne());
	}

	/**
	 * Visible for testing: everything except the exit itself.
	 */
	int consumeOne() {
		JmsTemplate template = new JmsTemplate(connectionFactory);
		template.setSessionTransacted(true);
		template.setReceiveTimeout(receiveTimeoutMillis);

		SessionCallback<Integer> oneRun = session -> {
			try (MessageConsumer consumer = session.createConsumer(session.createQueue(queueName))) {
				Message message = consumer.receive(receiveTimeoutMillis);
				if (message == null) {
					LOGGER.info("No validation queued on {} within {}ms - nothing to do",
							queueName, receiveTimeoutMillis);
					return EXIT_OK;
				}
				int exitCode = runOne(message);
				// Acknowledge only now. A rollback here, or a process death
				// before it, leaves the message on the queue for redelivery.
				session.commit();
				return exitCode;
			}
		};

		try {
			Integer exitCode = template.execute(oneRun, true);
			return exitCode == null ? EXIT_FAILED : exitCode;
		} catch (Exception e) {
			// The session is rolled back by the template, so the message
			// returns to the queue and the scheduler's retry has something to
			// consume. Exiting non-zero is what makes that retry happen.
			LOGGER.error("One-shot validation did not complete; message left for redelivery", e);
			return EXIT_FAILED;
		}
	}

	private int runOne(Message message) throws JMSException {
		if (!(message instanceof TextMessage textMessage)) {
			throw new IllegalStateException("Expected a TextMessage on " + queueName
					+ " but got " + message.getClass().getName());
		}
		ValidationRunConfig config;
		try {
			config = new Gson().fromJson(textMessage.getText(), ValidationRunConfig.class);
		} catch (JsonSyntaxException e) {
			throw new IllegalStateException("Could not read the validation config from the message", e);
		}
		if (config == null) {
			throw new IllegalStateException("Null validation config in message on " + queueName);
		}

		LOGGER.info("One-shot run {} starting: {}", config.getRunId(), config);
		runner.run(config);

		// run() writes a terminal state and does not throw, so the state file is
		// the only thing that knows whether this worked.
		State state = reportService.getCurrentState(config.getRunId(), config.getStorageLocation());
		LOGGER.info("One-shot run {} finished in state {}", config.getRunId(), state);
		return state == State.COMPLETE ? EXIT_OK : EXIT_FAILED;
	}
}
