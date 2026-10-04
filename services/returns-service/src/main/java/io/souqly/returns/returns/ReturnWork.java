package io.souqly.returns.returns;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import io.souqly.platform.security.Caller;
import io.souqly.returns.returns.ReturnExceptions.ReturnNotFoundException;
import io.souqly.returns.returns.ReturnExceptions.ReturnRejectedException;
import io.souqly.returns.security.Permissions;
import io.souqly.returns.workflow.Returns;
import org.cibseven.bpm.engine.MismatchingMessageCorrelationException;
import org.cibseven.bpm.engine.OptimisticLockingException;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.exception.NullValueException;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.task.TaskQuery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everyone's part after the buyer asks: the seller decides, the buyer may dispute a rejection,
 * operations arbitrate, and the warehouse receives and inspects the parcel. Each acts on a task of
 * the return's workflow; a caller only finds tasks in their own queue, so a seller cannot decide
 * another seller's return and a buyer cannot answer for someone else.
 */
@Service
public class ReturnWork {

    public enum Decision { APPROVE, REJECT }

    public enum BuyerResponse { ACCEPT, DISPUTE }

    public enum Inspection { PASS, FAIL }

    public record WorkItem(String taskId, String stage, Instant createdAt, Instant dueAt, ReturnRequest request) {
    }

    private final TaskService tasks;
    private final RuntimeService runtime;
    private final ReturnStore returns;
    private final Clock clock;

    public ReturnWork(TaskService tasks, RuntimeService runtime, ReturnStore returns, Clock clock) {
        this.tasks = tasks;
        this.runtime = runtime;
        this.returns = returns;
        this.clock = clock;
    }

    /** The caller's queue: their seller decisions, disputes to arbitrate, parcels to inspect. */
    public List<WorkItem> queue(Caller caller) {
        List<String> groups = Permissions.workGroups(caller);
        if (groups.isEmpty()) {
            return List.of();
        }
        List<Task> open = tasks.createTaskQuery().processDefinitionKey(Returns.PROCESS_KEY)
                .taskCandidateGroupIn(groups).active().orderByTaskCreateTime().asc().listPage(0, 100);
        if (open.isEmpty()) {
            return List.of();
        }
        Map<String, String> returnIds = runtime.createProcessInstanceQuery()
                .processInstanceIds(open.stream().map(Task::getProcessInstanceId).collect(Collectors.toSet()))
                .list().stream()
                .collect(Collectors.toMap(ProcessInstance::getId, ProcessInstance::getBusinessKey));
        return open.stream()
                .map(task -> returns.find(UUID.fromString(returnIds.get(task.getProcessInstanceId())))
                        .map(request -> new WorkItem(task.getId(), stage(task.getTaskDefinitionKey()),
                                task.getCreateTime().toInstant(),
                                task.getDueDate() != null ? task.getDueDate().toInstant() : null, request))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional
    public ReturnRequest sellerDecision(UUID returnId, Caller seller, Decision decision, String note) {
        if (seller.sellerId() == null) {
            throw new ReturnNotFoundException(returnId);
        }
        requireNote(decision == Decision.REJECT, note);
        return complete(returnId, Returns.TASK_SELLER_DECISION,
                tasks.createTaskQuery().taskCandidateGroup(Permissions.sellerGroup(seller.sellerId())),
                seller, decision.name(), note, Map.of(Returns.SELLER_DECISION, decision.name()));
    }

    @Transactional
    public ReturnRequest buyerResponse(UUID returnId, Caller buyer, BuyerResponse response, String note) {
        requireNote(response == BuyerResponse.DISPUTE, note);
        return complete(returnId, Returns.TASK_BUYER_RESPONSE, tasks.createTaskQuery().taskAssignee(buyer.subject()),
                buyer, response.name(), note, Map.of(Returns.BUYER_RESPONSE, response.name()));
    }

    @Transactional
    public ReturnRequest arbitrate(UUID returnId, Caller operator, Decision decision, String note) {
        requireNote(true, note);
        return complete(returnId, Returns.TASK_ARBITRATE,
                tasks.createTaskQuery().taskCandidateGroup(Permissions.MARKETPLACE_OPS),
                operator, decision.name(), note, Map.of(Returns.ARBITRATION, decision.name()));
    }

    @Transactional
    public ReturnRequest inspect(UUID returnId, Caller warehouse, Inspection result, String note) {
        requireNote(result == Inspection.FAIL, note);
        return complete(returnId, Returns.TASK_INSPECT,
                tasks.createTaskQuery().taskCandidateGroup(Permissions.WAREHOUSE),
                warehouse, result.name(), note, Map.of(Returns.INSPECTION, result.name()));
    }

    /** The warehouse scanned the parcel in. Only an approved return is waiting for one. */
    @Transactional
    public ReturnRequest received(UUID returnId, Caller warehouse) {
        ReturnRequest request = returns.find(returnId).orElseThrow(() -> new ReturnNotFoundException(returnId));
        try {
            runtime.createMessageCorrelation(Returns.MESSAGE_PARCEL_RECEIVED)
                    .processInstanceBusinessKey(returnId.toString())
                    .correlateWithResult();
        }
        catch (MismatchingMessageCorrelationException ex) {
            throw new ReturnRejectedException("NOT_AWAITING_PARCEL",
                    "Return " + returnId + " is " + request.status() + " and is not waiting for a parcel");
        }
        returns.insertDecision(new ReturnDecision(returnId, "RECEIPT", warehouse.subject(), "RECEIVED", null,
                clock.instant()));
        return returns.find(returnId).orElseThrow();
    }

    private ReturnRequest complete(UUID returnId, String taskKey, TaskQuery allowed, Caller actor, String decision,
            String note, Map<String, Object> variables) {
        Task task = allowed.processDefinitionKey(Returns.PROCESS_KEY)
                .processInstanceBusinessKey(returnId.toString())
                .taskDefinitionKey(taskKey)
                .singleResult();
        if (task == null) {
            // Not this caller's to decide, or not at this stage.
            throw new ReturnNotFoundException(returnId);
        }
        returns.insertDecision(new ReturnDecision(returnId, stage(taskKey), actor.subject(), decision, note,
                clock.instant()));
        try {
            tasks.setAssignee(task.getId(), actor.subject());
            tasks.complete(task.getId(), variables);
        }
        catch (OptimisticLockingException | NullValueException ex) {
            throw new ReturnRejectedException("ALREADY_DECIDED", "This step was already decided");
        }
        return returns.find(returnId).orElseThrow();
    }

    private static void requireNote(boolean required, String note) {
        if (required && (note == null || note.isBlank())) {
            throw new ReturnRejectedException("NOTE_REQUIRED", "Give a reason; the other party is told why");
        }
    }

    private static String stage(String taskKey) {
        return switch (taskKey) {
            case Returns.TASK_SELLER_DECISION -> "SELLER_DECISION";
            case Returns.TASK_BUYER_RESPONSE -> "BUYER_RESPONSE";
            case Returns.TASK_ARBITRATE -> "ARBITRATION";
            case Returns.TASK_INSPECT -> "INSPECTION";
            default -> taskKey;
        };
    }
}
