package io.souqly.returns.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.souqly.returns.returns.ReturnDecision;
import io.souqly.returns.returns.ReturnReason;
import io.souqly.returns.returns.ReturnRequest;
import io.souqly.returns.returns.ReturnService;
import io.souqly.returns.returns.ReturnStatus;
import io.souqly.returns.returns.ReturnWork;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

final class ApiModels {

    static final String IDENTIFIER = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";

    private ApiModels() {
    }

    record ReturnRequestBody(
            @NotBlank @Pattern(regexp = IDENTIFIER) String orderId,
            @NotNull ReturnReason reason,
            @Size(max = 1000) String comment,
            @NotEmpty @Size(max = 20) List<@Valid @NotNull Item> items) {

        record Item(@NotNull @Pattern(regexp = IDENTIFIER) String sku, @Min(1) @Max(100) int quantity) {
        }

        List<ReturnService.Item> toItems() {
            return items.stream().map(item -> new ReturnService.Item(item.sku(), item.quantity())).toList();
        }
    }

    record DecisionBody(@NotNull ReturnWork.Decision decision, @Size(max = 1000) String note) {
    }

    record BuyerResponseBody(@NotNull ReturnWork.BuyerResponse response, @Size(max = 1000) String note) {
    }

    record InspectionBody(@NotNull ReturnWork.Inspection result, @Size(max = 1000) String note) {
    }

    record DecisionView(String stage, String actorId, String decision, String note, Instant decidedAt) {

        static DecisionView from(ReturnDecision decision) {
            return new DecisionView(decision.stage(), decision.actorId(), decision.decision(), decision.note(),
                    decision.decidedAt());
        }
    }

    record ReturnResponse(UUID id, String orderId, String sellerId, ReturnStatus status, ReturnReason reason,
            String comment, List<ReturnRequest.Line> lines, BigDecimal refundAmount, String currency, String refundId,
            List<DecisionView> decisions, long version, Instant createdAt, Instant updatedAt, Instant decidedAt) {

        static ReturnResponse from(ReturnRequest request) {
            return from(request, List.of());
        }

        static ReturnResponse from(ReturnRequest r, List<ReturnDecision> decisions) {
            return new ReturnResponse(r.id(), r.orderId(), r.sellerId(), r.status(), r.reason(), r.comment(), r.lines(),
                    r.refundAmount(), r.currency(), r.refundId(), decisions.stream().map(DecisionView::from).toList(),
                    r.version(), r.createdAt(), r.updatedAt(), r.decidedAt());
        }
    }

    record WorkItemResponse(String taskId, String stage, Instant createdAt, Instant dueAt, ReturnResponse returnRequest) {

        static WorkItemResponse from(ReturnWork.WorkItem item) {
            return new WorkItemResponse(item.taskId(), item.stage(), item.createdAt(), item.dueAt(),
                    ReturnResponse.from(item.request()));
        }
    }
}
