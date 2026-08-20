package com.jipi.ticket_ledger.payment.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface PaymentOutboxEventRepository extends JpaRepository<PaymentOutboxEvent, Long> {

    Optional<PaymentOutboxEvent> findByEventId(UUID eventId);

    List<PaymentOutboxEvent> findAllByPaymentIdOrderByIdAsc(Long paymentId);
}
