package io.souqly.returns.workflow;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.souqly.platform.payments.PaymentGateway;
import io.souqly.returns.events.ReturnEvents;
import io.souqly.returns.orders.OrderReplica;
import io.souqly.returns.returns.ReturnDecision;
import io.souqly.returns.returns.ReturnRequest;
import io.souqly.returns.returns.ReturnStatus;
import io.souqly.returns.returns.ReturnStore;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import static io.souqly.returns.returns.ReturnStatus.APPROVED;
import static io.souqly.returns.returns.ReturnStatus.AWAITING_SELLER;
import static io.souqly.returns.returns.ReturnStatus.CANCELLED;
import static io.souqly.returns.returns.ReturnStatus.IN_DISPUTE;
import static io.souqly.returns.returns.ReturnStatus.RECEIVED;
import static io.souqly.returns.returns.ReturnStatus.REFUNDED;
import static io.souqly.returns.returns.ReturnStatus.REJECTED;
import static io.souqly.returns.returns.ReturnStatus.REQUESTED;
import static io.souqly.returns.returns.ReturnStatus.SELLER_REJECTED;

/**
 * The work behind each step of the {@code return-request} process, called from the BPMN as
 * {@code ${returns.<step>(execution)}}. Status changes run as asynchronous jobs and publish their
 * event before the job commits.
 */
@Component("returns")
public class ReturnSteps {

    private static final Logger log = LoggerFactory.getLogger(ReturnSteps.class);

    private final ReturnStore returns;
    private final OrderReplica orders;
    private final ReturnEvents events;
    private final PaymentGateway payments;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public ReturnSteps(ReturnStore returns, OrderReplica orders, ReturnEvents events, PaymentGateway payments,
            MeterRegistry meterRegistry, Clock clock) {
        this.returns = returns;
        this.orders = orders;
        this.events = events;
        this.payments = payments;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public void askSeller(DelegateExecution execution) {
        events.publish(move(execution, Set.of(REQUESTED), AWAITING_SELLER), "ReturnAwaitingSeller");
    }

    /** The seller let the deadline pass; marketplace policy treats silence as consent. */
    public void sellerSilent(DelegateExecution execution) {
        returns.insertDecision(new ReturnDecision(returnId(execution), "SELLER_DECISION", "system", "APPROVE",
                "No answer from the seller in time", clock.instant()));
        meterRegistry.counter("souqly.returns.seller-sla-missed").increment();
    }

    public void approve(DelegateExecution execution) {
        events.publish(move(execution, Set.of(REQUESTED, AWAITING_SELLER, IN_DISPUTE), APPROVED), "ReturnApproved");
    }

    public void sellerRejected(DelegateExecution execution) {
        events.publish(move(execution, Set.of(AWAITING_SELLER), SELLER_REJECTED), "ReturnRejectedBySeller");
    }

    public void openDispute(DelegateExecution execution) {
        events.publish(move(execution, Set.of(SELLER_REJECTED), IN_DISPUTE), "ReturnDisputed");
    }

    public void reject(DelegateExecution execution) {
        events.publish(move(execution, Set.of(SELLER_REJECTED, IN_DISPUTE, RECEIVED), REJECTED), "ReturnRejected");
        meterRegistry.counter("souqly.returns.completed", "outcome", "rejected").increment();
    }

    public void cancel(DelegateExecution execution) {
        events.publish(move(execution, Set.of(APPROVED), CANCELLED), "ReturnCancelled");
        meterRegistry.counter("souqly.returns.completed", "outcome", "cancelled").increment();
    }

    public void receive(DelegateExecution execution) {
        events.publish(move(execution, Set.of(APPROVED), RECEIVED), "ReturnReceived");
    }

    /**
     * Refunds the returned items. The PSP key is derived from the return, so a retried job never
     * refunds twice. A timeout is retried; a refusal raises an incident for a person to resolve.
     */
    public void refund(DelegateExecution execution) {
        ReturnRequest request = load(execution);
        String paymentId = orders.find(request.orderId()).map(order -> order.paymentId())
                .orElseThrow(() -> new IllegalStateException("Order " + request.orderId() + " is unknown"));
        switch (payments.refund(request.refundKey(), paymentId, request.refundAmount(), request.currency())) {
            case PaymentGateway.Succeeded refunded -> {
                returns.recordRefund(request.id(), refunded.paymentId(), clock.instant());
                execution.setVariable(Returns.REFUND_ID, refunded.paymentId());
            }
            case PaymentGateway.Declined declined -> throw new IllegalStateException(
                    "PSP refused the refund of return " + request.id() + ": " + declined.code());
            case PaymentGateway.Unknown unknown -> throw new IllegalStateException(
                    "Refund of return " + request.id() + " not confirmed yet: " + unknown.reason());
        }
    }

    public void refunded(DelegateExecution execution) {
        events.publish(move(execution, Set.of(RECEIVED), REFUNDED), "ReturnRefunded");
        meterRegistry.counter("souqly.returns.completed", "outcome", "refunded").increment();
    }

    private ReturnRequest move(DelegateExecution execution, Set<ReturnStatus> from, ReturnStatus to) {
        UUID id = returnId(execution);
        if (!returns.transition(id, from, to, clock.instant())) {
            log.error("Return {} is not in {}, cannot become {}", id, from, to);
            throw new IllegalStateException("Return " + id + " is not in " + from + ", cannot become " + to);
        }
        return load(execution);
    }

    private ReturnRequest load(DelegateExecution execution) {
        UUID id = returnId(execution);
        return returns.find(id).orElseThrow(() -> new IllegalStateException("Return " + id + " is gone"));
    }

    private static UUID returnId(DelegateExecution execution) {
        return UUID.fromString((String) execution.getVariable(Returns.RETURN_ID));
    }
}
