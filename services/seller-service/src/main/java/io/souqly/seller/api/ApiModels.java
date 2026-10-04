package io.souqly.seller.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.souqly.seller.application.ApplicationDocument;
import io.souqly.seller.application.ApplicationService;
import io.souqly.seller.application.ApplicationStatus;
import io.souqly.seller.application.DocumentType;
import io.souqly.seller.application.ReviewDecision;
import io.souqly.seller.application.RiskTier;
import io.souqly.seller.application.SellerApplication;
import io.souqly.seller.review.ReviewService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

final class ApiModels {

    private ApiModels() {
    }

    /**
     * @param sellerId the seller ID to sell under once approved
     * @param kyc      the {@code seller-kyc} form's data
     */
    record ApplicationRequest(@NotNull @Size(min = 2, max = 40) String sellerId, @NotNull Map<String, Object> kyc) {
    }

    record DocumentUploadRequest(@NotNull DocumentType type, @NotBlank String contentType, @Positive long sizeBytes) {
    }

    record DecisionRequest(@NotNull ReviewService.Decision decision, @Size(max = 1000) String note) {
    }

    record DocumentView(UUID id, DocumentType type, String contentType, long sizeBytes,
            ApplicationDocument.Status status, Instant createdAt) {

        static DocumentView from(ApplicationDocument document) {
            return new DocumentView(document.id(), document.type(), document.contentType(), document.sizeBytes(),
                    document.status(), document.createdAt());
        }
    }

    /** @param reviewerId shown to reviewers only */
    record DecisionView(String stage, String reviewerId, String decision, String note, Instant decidedAt) {

        static DecisionView from(ReviewDecision decision) {
            return new DecisionView(decision.stage(), decision.reviewerId(), decision.decision(), decision.note(),
                    decision.decidedAt());
        }
    }

    record ApplicationResponse(UUID id, String sellerId, ApplicationStatus status, RiskTier riskTier,
            boolean escalated, Map<String, Object> kyc, List<DocumentView> documents, List<DecisionView> decisions,
            long version, Instant createdAt, Instant updatedAt, Instant submittedAt, Instant decidedAt) {

        static ApplicationResponse from(SellerApplication application) {
            return from(new ApplicationService.Detail(application, List.of(), List.of()));
        }

        static ApplicationResponse from(ApplicationService.Detail detail) {
            SellerApplication a = detail.application();
            return new ApplicationResponse(a.id(), a.sellerHandle(), a.status(), a.riskTier(), a.escalated(), a.kyc(),
                    detail.documents().stream().map(DocumentView::from).toList(),
                    detail.decisions().stream().map(DecisionView::from).toList(),
                    a.version(), a.createdAt(), a.updatedAt(), a.submittedAt(), a.decidedAt());
        }
    }

    record UploadResponse(DocumentView document, String uploadUrl, Map<String, String> uploadHeaders,
            Instant expiresAt) {
    }

    record DownloadResponse(String url, Instant expiresAt) {
    }

    /** One task in the compliance queue, with what a reviewer needs to triage it. */
    record ReviewItemResponse(String taskId, ReviewService.Stage stage, int priority, Instant createdAt,
            Instant dueAt, boolean escalated, int sharedIdentifiers, String firstReviewer, UUID applicationId,
            String sellerId, String legalName, String country, String businessType, RiskTier riskTier) {

        static ReviewItemResponse from(ReviewService.ReviewItem item) {
            SellerApplication a = item.application();
            return new ReviewItemResponse(item.taskId(), item.stage(), item.priority(), item.createdAt(),
                    item.dueAt(), a.escalated(), item.sharedIdentifiers(), item.firstReviewer(), a.id(),
                    a.sellerHandle(), a.kycText("legalName"), a.kycText("country"), a.kycText("businessType"),
                    a.riskTier());
        }
    }
}
