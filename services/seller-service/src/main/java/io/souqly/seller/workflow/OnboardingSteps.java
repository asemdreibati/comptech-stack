package io.souqly.seller.workflow;

import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.souqly.seller.application.ApplicationStatus;
import io.souqly.seller.application.ApplicationStore;
import io.souqly.seller.application.RiskTier;
import io.souqly.seller.application.SellerApplication;
import io.souqly.seller.config.OnboardingProperties;
import io.souqly.seller.events.ApplicationEvents;
import io.souqly.seller.identity.KeycloakAdmin;
import io.souqly.seller.security.Permissions;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import static io.souqly.seller.workflow.Onboarding.APPLICANT_ID;
import static io.souqly.seller.workflow.Onboarding.APPLICATION_ID;

/**
 * The work behind each step of the {@code seller-onboarding} process, called from the BPMN as
 * {@code ${onboarding.<step>(execution)}}.
 *
 * <p>Steps that change an application's status run as asynchronous jobs (see the model) and publish
 * the matching event before the job commits, so a status change and its event succeed or fail
 * together, and a failed job is retried by the engine.
 */
@Component("onboarding")
public class OnboardingSteps {

    private static final Logger log = LoggerFactory.getLogger(OnboardingSteps.class);

    private final ApplicationStore applications;
    private final ApplicationEvents events;
    private final KeycloakAdmin keycloak;
    private final TaskService tasks;
    private final OnboardingProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public OnboardingSteps(ApplicationStore applications, ApplicationEvents events, KeycloakAdmin keycloak,
            TaskService tasks, OnboardingProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this.applications = applications;
        this.events = events;
        this.keycloak = keycloak;
        this.tasks = tasks;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /** Moves the application into review and gathers the inputs of the risk decision. */
    public void screen(DelegateExecution execution) {
        SellerApplication application = move(execution, Set.of(ApplicationStatus.SUBMITTED),
                ApplicationStatus.IN_REVIEW);
        List<UUID> sharing = applications.sharingIdentifiers(application);
        if (!sharing.isEmpty()) {
            log.warn("Application {} shares a bank account or trade licence with {}", application.id(), sharing);
        }
        execution.setVariable(Onboarding.DUPLICATE_COUNT, (long) sharing.size());
        execution.setVariable(Onboarding.DUPLICATES, String.join(",", sharing.stream().map(UUID::toString).toList()));
        execution.setVariable(Onboarding.COUNTRY, application.kycText("country"));
        execution.setVariable(Onboarding.BUSINESS_TYPE, application.kycText("businessType"));
        execution.setVariable(Onboarding.EXPECTED_MONTHLY_ORDERS,
                ((Number) application.kyc().getOrDefault("expectedMonthlyOrders", 0)).longValue());
        execution.setVariable(Onboarding.REVIEW_DUE_AT, Date.from(clock.instant().plus(properties.reviewSla())));
        events.publish(application, "SellerApplicationInReview");
    }

    /** Stores the risk tier the decision table produced. */
    public void recordRisk(DelegateExecution execution) {
        RiskTier tier = RiskTier.valueOf((String) execution.getVariable(Onboarding.RISK_TIER));
        applications.recordRisk(applicationId(execution), tier, clock.instant());
    }

    public void requestInformation(DelegateExecution execution) {
        SellerApplication application = move(execution, Set.of(ApplicationStatus.IN_REVIEW),
                ApplicationStatus.INFORMATION_REQUESTED);
        events.publish(application, "SellerApplicationInformationRequested");
    }

    /**
     * The review missed its SLA: raise the task's priority and open it to compliance leads too.
     * The original reviewers keep it, so nobody's work in progress is taken away.
     */
    public void escalate(DelegateExecution execution) {
        var review = tasks.createTaskQuery()
                .processInstanceId(execution.getProcessInstanceId())
                .taskDefinitionKey(Onboarding.TASK_REVIEW)
                .singleResult();
        if (review != null) {
            tasks.setPriority(review.getId(), Onboarding.ESCALATED_PRIORITY);
            tasks.addCandidateGroup(review.getId(), Permissions.COMPLIANCE_LEADS);
        }
        applications.markEscalated(applicationId(execution), clock.instant());
        meterRegistry.counter("souqly.onboarding.escalations").increment();
        log.warn("Review of application {} missed its SLA and was escalated", applicationId(execution));
    }

    /** Grants the seller identity in Keycloak; idempotent, so a retried job is harmless. */
    public void provision(DelegateExecution execution) {
        SellerApplication application = load(execution);
        keycloak.grantSeller((String) execution.getVariable(APPLICANT_ID), application.sellerHandle());
    }

    public void approve(DelegateExecution execution) {
        SellerApplication application = move(execution, Set.of(ApplicationStatus.IN_REVIEW),
                ApplicationStatus.APPROVED);
        events.publish(application, "SellerApproved");
        meterRegistry.counter("souqly.onboarding.decisions", "outcome", "approved").increment();
    }

    public void reject(DelegateExecution execution) {
        SellerApplication application = move(execution, Set.of(ApplicationStatus.IN_REVIEW),
                ApplicationStatus.REJECTED);
        events.publish(application, "SellerApplicationRejected");
        meterRegistry.counter("souqly.onboarding.decisions", "outcome", "rejected").increment();
    }

    public void expire(DelegateExecution execution) {
        SellerApplication application = move(execution, Set.of(ApplicationStatus.INFORMATION_REQUESTED),
                ApplicationStatus.EXPIRED);
        events.publish(application, "SellerApplicationExpired");
        meterRegistry.counter("souqly.onboarding.decisions", "outcome", "expired").increment();
    }

    private SellerApplication move(DelegateExecution execution, Set<ApplicationStatus> from, ApplicationStatus to) {
        UUID id = applicationId(execution);
        if (!applications.transition(id, from, to, clock.instant())) {
            // The process and the table disagree: stop and raise an incident rather than guess.
            throw new IllegalStateException("Application " + id + " is not in " + from + ", cannot become " + to);
        }
        return load(execution);
    }

    private SellerApplication load(DelegateExecution execution) {
        UUID id = applicationId(execution);
        return applications.find(id).orElseThrow(() -> new IllegalStateException("Application " + id + " is gone"));
    }

    private static UUID applicationId(DelegateExecution execution) {
        return UUID.fromString((String) execution.getVariable(APPLICATION_ID));
    }
}
