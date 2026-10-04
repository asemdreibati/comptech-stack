package io.souqly.seller.api;

import java.util.List;

import io.souqly.platform.security.Caller;
import io.souqly.seller.api.ApiModels.ApplicationResponse;
import io.souqly.seller.api.ApiModels.DecisionRequest;
import io.souqly.seller.api.ApiModels.ReviewItemResponse;
import io.souqly.seller.review.ReviewService;
import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reviews")
class ReviewController {

    private final ReviewService reviews;

    ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    /** The caller's review queue: escalated and older tasks first. */
    @GetMapping
    List<ReviewItemResponse> queue(Authentication authentication) {
        return reviews.queue(Caller.from(authentication)).stream().map(ReviewItemResponse::from).toList();
    }

    /** Approve, reject, or ask the applicant for more information (rejections and requests need a note). */
    @PostMapping("/{taskId}/decision")
    ApplicationResponse decide(@PathVariable String taskId, @Valid @RequestBody DecisionRequest request,
            Authentication authentication) {
        return ApplicationResponse.from(
                reviews.decide(taskId, Caller.from(authentication), request.decision(), request.note()));
    }
}
