package io.souqly.returns.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import io.souqly.platform.security.Caller;
import io.souqly.returns.api.ApiModels.BuyerResponseBody;
import io.souqly.returns.api.ApiModels.DecisionBody;
import io.souqly.returns.api.ApiModels.InspectionBody;
import io.souqly.returns.api.ApiModels.ReturnRequestBody;
import io.souqly.returns.api.ApiModels.ReturnResponse;
import io.souqly.returns.api.ApiModels.WorkItemResponse;
import io.souqly.returns.returns.ReturnExceptions.ReturnRejectedException;
import io.souqly.returns.returns.ReturnService;
import io.souqly.returns.returns.ReturnWork;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReturnController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final ReturnService returns;
    private final ReturnWork work;

    ReturnController(ReturnService returns, ReturnWork work) {
        this.returns = returns;
        this.work = work;
    }

    /**
     * Asks to return items of one order. Answers {@code 202}: the return policy and, if needed, the
     * seller decide asynchronously. Repeating the request with the same {@code Idempotency-Key}
     * returns the same return.
     */
    @PostMapping("/api/v1/returns")
    ResponseEntity<ReturnResponse> request(@RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody ReturnRequestBody body, Authentication authentication) {
        if (!idempotencyKey.matches(ApiModels.IDENTIFIER)) {
            throw new ReturnRejectedException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1-64 letters, digits, '.', '_' or '-'");
        }
        if (body.items().stream().map(ReturnRequestBody.Item::sku).distinct().count() != body.items().size()) {
            throw new ReturnRejectedException("DUPLICATE_ITEMS", "Each SKU may appear only once per return");
        }
        var placement = returns.request(Caller.from(authentication), idempotencyKey, body.orderId(), body.reason(),
                body.comment(), body.toItems());
        var location = URI.create("/api/v1/returns/" + placement.request().id());
        var response = ReturnResponse.from(placement.request());
        if (!placement.created()) {
            return ResponseEntity.ok().location(location).header(REPLAYED_HEADER, "true").body(response);
        }
        return ResponseEntity.accepted().location(location).body(response);
    }

    @GetMapping("/api/v1/returns")
    List<ReturnResponse> mine(Authentication authentication) {
        return returns.mine(Caller.from(authentication)).stream().map(ReturnResponse::from).toList();
    }

    /** The return and its decisions: for its buyer, its seller, operations and the warehouse. */
    @GetMapping("/api/v1/returns/{id}")
    ReturnResponse get(@PathVariable UUID id, Authentication authentication) {
        var detail = returns.get(id, Caller.from(authentication));
        return ReturnResponse.from(detail.request(), detail.decisions());
    }

    /** The buyer accepts or disputes a seller's rejection. */
    @PostMapping("/api/v1/returns/{id}/response")
    ReturnResponse respond(@PathVariable UUID id, @Valid @RequestBody BuyerResponseBody body,
            Authentication authentication) {
        return ReturnResponse.from(work.buyerResponse(id, Caller.from(authentication), body.response(), body.note()));
    }

    /** The seller approves or rejects a return of their products (a rejection needs a reason). */
    @PostMapping("/api/v1/returns/{id}/seller-decision")
    ReturnResponse sellerDecision(@PathVariable UUID id, @Valid @RequestBody DecisionBody body,
            Authentication authentication) {
        return ReturnResponse.from(work.sellerDecision(id, Caller.from(authentication), body.decision(), body.note()));
    }

    /** Operations settle a dispute, with a reason either way. */
    @PostMapping("/api/v1/returns/{id}/arbitration")
    ReturnResponse arbitrate(@PathVariable UUID id, @Valid @RequestBody DecisionBody body,
            Authentication authentication) {
        return ReturnResponse.from(work.arbitrate(id, Caller.from(authentication), body.decision(), body.note()));
    }

    /** The warehouse scanned the parcel in. */
    @PostMapping("/api/v1/returns/{id}/received")
    ReturnResponse received(@PathVariable UUID id, Authentication authentication) {
        return ReturnResponse.from(work.received(id, Caller.from(authentication)));
    }

    /** The warehouse's inspection: pass refunds the buyer, fail rejects the return. */
    @PostMapping("/api/v1/returns/{id}/inspection")
    ReturnResponse inspect(@PathVariable UUID id, @Valid @RequestBody InspectionBody body,
            Authentication authentication) {
        return ReturnResponse.from(work.inspect(id, Caller.from(authentication), body.result(), body.note()));
    }

    /** The caller's queue: seller decisions, disputes or inspections, depending on their role. */
    @GetMapping("/api/v1/return-tasks")
    List<WorkItemResponse> tasks(Authentication authentication) {
        return work.queue(Caller.from(authentication)).stream().map(WorkItemResponse::from).toList();
    }
}
