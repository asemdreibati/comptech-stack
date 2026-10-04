package io.souqly.seller.review;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import io.souqly.platform.security.Caller;
import io.souqly.seller.application.ApplicationExceptions.ApplicationConflictException;
import io.souqly.seller.application.ApplicationExceptions.ApplicationNotFoundException;
import io.souqly.seller.application.ApplicationStore;
import io.souqly.seller.application.ReviewDecision;
import io.souqly.seller.application.SellerApplication;
import io.souqly.seller.security.Permissions;
import io.souqly.seller.workflow.Onboarding;
import org.cibseven.bpm.engine.OptimisticLockingException;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.exception.NullValueException;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The compliance side: a queue of review tasks, and decisions on them. A reviewer only sees and
 * decides tasks of the groups their permissions open ({@link Permissions#reviewGroups}).
 */
@Service
public class ReviewService {

    public enum Stage { REVIEW, SECOND_REVIEW }

    public enum Decision { APPROVE, REJECT, MORE_INFO }

    public record ReviewItem(String taskId, Stage stage, int priority, Instant createdAt, Instant dueAt,
            String firstReviewer, int sharedIdentifiers, SellerApplication application) {
    }

    private final TaskService tasks;
    private final RuntimeService runtime;
    private final ApplicationStore applications;
    private final Clock clock;

    public ReviewService(TaskService tasks, RuntimeService runtime, ApplicationStore applications, Clock clock) {
        this.tasks = tasks;
        this.runtime = runtime;
        this.applications = applications;
        this.clock = clock;
    }

    /** Open review tasks for the caller, most urgent first. */
    public List<ReviewItem> queue(Caller reviewer) {
        List<Task> open = tasks.createTaskQuery()
                .processDefinitionKey(Onboarding.PROCESS_KEY)
                .taskCandidateGroupIn(Permissions.reviewGroups(reviewer))
                .active()
                .orderByTaskPriority().desc()
                .orderByTaskCreateTime().asc()
                .listPage(0, 100);
        if (open.isEmpty()) {
            return List.of();
        }
        Map<String, String> businessKeys = runtime.createProcessInstanceQuery()
                .processInstanceIds(open.stream().map(Task::getProcessInstanceId).collect(Collectors.toSet()))
                .list().stream()
                .collect(Collectors.toMap(ProcessInstance::getId, ProcessInstance::getBusinessKey));
        return open.stream()
                .map(task -> {
                    UUID applicationId = UUID.fromString(businessKeys.get(task.getProcessInstanceId()));
                    var variables = tasks.getVariables(task.getId(),
                            List.of(Onboarding.FIRST_REVIEWER, Onboarding.DUPLICATE_COUNT));
                    Number shared = (Number) variables.get(Onboarding.DUPLICATE_COUNT);
                    return applications.find(applicationId).map(application -> new ReviewItem(task.getId(),
                            stage(task), task.getPriority(), task.getCreateTime().toInstant(),
                            task.getDueDate() != null ? task.getDueDate().toInstant() : null,
                            (String) variables.get(Onboarding.FIRST_REVIEWER),
                            shared != null ? shared.intValue() : 0, application)).orElse(null);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Records a reviewer's decision and moves the workflow on. The task is completed in the same
     * transaction as the audit record, so a decision is never lost or recorded twice.
     */
    @Transactional
    public SellerApplication decide(String taskId, Caller reviewer, Decision decision, String note) {
        Task task = tasks.createTaskQuery()
                .taskId(taskId)
                .processDefinitionKey(Onboarding.PROCESS_KEY)
                .taskCandidateGroupIn(Permissions.reviewGroups(reviewer))
                .singleResult();
        if (task == null) {
            throw new ApplicationNotFoundException("review task " + taskId);
        }
        Stage stage = stage(task);
        if (stage == Stage.SECOND_REVIEW && decision == Decision.MORE_INFO) {
            throw new ApplicationConflictException("DECISION_NOT_ALLOWED",
                    "A second review can only approve or reject");
        }
        if (decision != Decision.APPROVE && (note == null || note.isBlank())) {
            throw new ApplicationConflictException("NOTE_REQUIRED",
                    "Explain a rejection or a request for information; the applicant is told why");
        }
        if (stage == Stage.SECOND_REVIEW
                && reviewer.subject().equals(tasks.getVariable(taskId, Onboarding.FIRST_REVIEWER))) {
            throw new ApplicationConflictException("FOUR_EYES_REQUIRED",
                    "High-risk applications need a second reviewer other than the first");
        }

        String businessKey = runtime.createProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId()).singleResult().getBusinessKey();
        UUID applicationId = UUID.fromString(businessKey);
        applications.insertDecision(new ReviewDecision(applicationId, stage.name(), reviewer.subject(),
                decision.name(), note, clock.instant()));
        Map<String, Object> variables = stage == Stage.REVIEW
                ? Map.of(Onboarding.DECISION, decision.name(), Onboarding.FIRST_REVIEWER, reviewer.subject())
                : Map.of(Onboarding.SECOND_DECISION, decision.name());
        try {
            tasks.setAssignee(taskId, reviewer.subject());
            tasks.complete(taskId, variables);
        }
        catch (OptimisticLockingException | NullValueException ex) {
            throw new ApplicationConflictException("ALREADY_DECIDED", "Someone else decided this task first");
        }
        return applications.find(applicationId).orElseThrow();
    }

    private static Stage stage(Task task) {
        return Onboarding.TASK_SECOND_REVIEW.equals(task.getTaskDefinitionKey()) ? Stage.SECOND_REVIEW : Stage.REVIEW;
    }
}
