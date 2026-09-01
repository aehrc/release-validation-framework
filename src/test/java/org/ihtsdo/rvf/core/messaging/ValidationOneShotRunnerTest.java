package org.ihtsdo.rvf.core.messaging;

import org.ihtsdo.rvf.core.service.ValidationReportService;
import org.ihtsdo.rvf.core.service.ValidationReportService.State;
import org.ihtsdo.rvf.core.service.ValidationRunner;
import org.ihtsdo.rvf.core.service.config.ValidationRunConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The behaviour a scheduler depends on: the exit code, and whether the message
 * survives a run that did not finish.
 *
 * <p>Drives the real {@code consumeOne()} with a mocked JMS connection, so the
 * {@code JmsTemplate} it builds internally is exercised rather than stubbed. The
 * assertions that matter are about the *order* of receive, run and commit, which
 * is why a mock is the right instrument here - an embedded broker would prove
 * ActiveMQ delivers messages, which was never in doubt.
 */
class ValidationOneShotRunnerTest {

	private static final String QUEUE = "rvf-validation-queue";

	private ValidationRunner runner;
	private ValidationReportService reportService;
	private ConnectionFactory connectionFactory;
	private Session session;
	private MessageConsumer consumer;

	@BeforeEach
	void setUp() throws JMSException {
		runner = mock(ValidationRunner.class);
		reportService = mock(ValidationReportService.class);
		session = mock(Session.class);
		consumer = mock(MessageConsumer.class);

		Connection connection = mock(Connection.class);
		connectionFactory = mock(ConnectionFactory.class);
		when(connectionFactory.createConnection()).thenReturn(connection);
		when(connection.createSession(anyBoolean(), anyInt())).thenReturn(session);
		when(session.getTransacted()).thenReturn(true);
		when(session.createQueue(anyString())).thenReturn(mock(Queue.class));
		when(session.createConsumer(any())).thenReturn(consumer);
	}

	private ValidationOneShotRunner oneShot(boolean isWorker) {
		return new ValidationOneShotRunner(runner, reportService, connectionFactory,
				QUEUE, 1L, isWorker, mock(Runtime.class));
	}

	private void queued(long runId, String storageLocation) throws JMSException {
		TextMessage message = mock(TextMessage.class);
		when(message.getText()).thenReturn(
				"{\"runId\":" + runId + ",\"storageLocation\":\"" + storageLocation + "\"}");
		when(consumer.receive(anyLong())).thenReturn(message);
	}

	@Test
	void enablingOneShotWithoutDisablingTheListenerFailsAtStartup() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> oneShot(true).run(null));

		assertTrue(e.getMessage().contains("rvf.execution.isWorker=false"),
				"the message must name the property to set: isWorker defaults to true, "
				+ "so this is the mistake people will actually make");
		verify(runner, never()).run(any());
	}

	@Test
	void aRunThatReachesCompleteExitsZero() throws JMSException {
		queued(42L, "nightly/");
		when(reportService.getCurrentState(42L, "nightly/")).thenReturn(State.COMPLETE);

		assertEquals(0, oneShot(false).consumeOne());

		verify(runner).run(any(ValidationRunConfig.class));
	}

	@Test
	void aRunThatReachedFailedExitsNonZero() throws JMSException {
		queued(42L, "nightly/");
		when(reportService.getCurrentState(42L, "nightly/")).thenReturn(State.FAILED);

		assertEquals(1, oneShot(false).consumeOne());
	}

	/**
	 * Under a {@code ScaledJob} one job is created per message, but the queue is
	 * a race - another worker may have taken it first. Normal, not a failure; a
	 * non-zero exit here would show a broken job on every scale-up.
	 */
	@Test
	void anEmptyQueueExitsZeroWithoutRunningAnything() throws JMSException {
		when(consumer.receive(anyLong())).thenReturn(null);

		assertEquals(0, oneShot(false).consumeOne());

		verify(runner, never()).run(any());
		verify(session, never()).commit();
	}

	/**
	 * The load-bearing test. Acknowledging on receipt would mean a process killed
	 * mid-run loses the message, and the scheduler's retry would find an empty
	 * queue, exit 0, and report success having validated nothing.
	 */
	@Test
	void theMessageIsAcknowledgedOnlyAfterTheRunFinishes() throws JMSException {
		queued(42L, "nightly/");
		when(reportService.getCurrentState(42L, "nightly/")).thenReturn(State.COMPLETE);

		oneShot(false).consumeOne();

		InOrder order = inOrder(consumer, runner, session);
		order.verify(consumer).receive(anyLong());
		order.verify(runner).run(any());
		order.verify(session).commit();
	}

	@Test
	void aCrashingRunLeavesTheMessageOnTheQueue() throws JMSException {
		queued(42L, "nightly/");
		doThrow(new RuntimeException("node reclaimed")).when(runner).run(any());

		assertEquals(1, oneShot(false).consumeOne());

		verify(session, never()).commit();
	}

	/**
	 * A message that is not a TextMessage, or carries unreadable JSON, must not
	 * be committed either - it goes back to the broker and hits the redelivery
	 * limit, which is what puts it on the dead-letter queue rather than silently
	 * dropping it.
	 */
	@Test
	void anUnreadableMessageIsNotAcknowledged() throws JMSException {
		TextMessage message = mock(TextMessage.class);
		when(message.getText()).thenReturn("not json");
		when(consumer.receive(anyLong())).thenReturn(message);

		assertEquals(1, oneShot(false).consumeOne());

		verify(runner, never()).run(any());
		verify(session, never()).commit();
	}
}
