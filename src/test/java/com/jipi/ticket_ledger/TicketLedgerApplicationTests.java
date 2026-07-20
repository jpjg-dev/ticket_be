package com.jipi.ticket_ledger;

import com.jipi.ticket_ledger.payment.application.recovery.PaymentRecoveryScheduler;
import com.jipi.ticket_ledger.queue.application.QueueAdmissionScheduler;
import com.jipi.ticket_ledger.reservation.application.ReservationExpirationScheduler;
import com.jipi.ticket_ledger.support.PostgresTestContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TicketLedgerApplicationTests extends PostgresTestContainerSupport {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void contextLoads() {
		assertTrue(applicationContext.getBeansOfType(QueueAdmissionScheduler.class).isEmpty());
		assertTrue(applicationContext.getBeansOfType(ReservationExpirationScheduler.class).isEmpty());
		assertTrue(applicationContext.getBeansOfType(PaymentRecoveryScheduler.class).isEmpty());
	}

}
