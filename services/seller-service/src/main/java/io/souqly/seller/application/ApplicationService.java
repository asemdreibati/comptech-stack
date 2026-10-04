package io.souqly.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import io.souqly.platform.formio.FormValidator;
import io.souqly.platform.security.Caller;
import io.souqly.platform.storage.FileTypes;
import io.souqly.platform.storage.ObjectStorage.PresignedRequest;
import io.souqly.platform.storage.ObjectStorage.StoredObject;
import io.souqly.seller.application.ApplicationExceptions.ApplicationConflictException;
import io.souqly.seller.application.ApplicationExceptions.ApplicationNotFoundException;
import io.souqly.seller.application.ApplicationExceptions.InvalidDocumentException;
import io.souqly.seller.config.OnboardingProperties;
import io.souqly.seller.identity.KeycloakAdmin;
import io.souqly.seller.security.Permissions;
import io.souqly.seller.workflow.Onboarding;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An applicant's side of onboarding: fill in the KYC details, upload documents straight to object
 * storage, submit. Submitting starts (or, after a request for information, resumes) the review
 * workflow in the same database transaction as the status change.
 */
@Service
public class ApplicationService {

    /** The same rule Keycloak's user profile applies to the {@code seller_id} attribute. */
    static final Pattern SELLER_HANDLE = Pattern.compile("^[a-z0-9-]{2,40}$");
    static final String KYC_FORM = "seller-kyc";

    private final ApplicationStore applications;
    private final FormValidator forms;
    private final KeycloakAdmin keycloak;
    private final DocumentStorage storage;
    private final RuntimeService runtime;
    private final TaskService tasks;
    private final OnboardingProperties properties;
    private final Clock clock;

    public ApplicationService(ApplicationStore applications, FormValidator forms, KeycloakAdmin keycloak,
            DocumentStorage storage, RuntimeService runtime, TaskService tasks, OnboardingProperties properties,
            Clock clock) {
        this.applications = applications;
        this.forms = forms;
        this.keycloak = keycloak;
        this.storage = storage;
        this.runtime = runtime;
        this.tasks = tasks;
        this.properties = properties;
        this.clock = clock;
    }

    public record Detail(SellerApplication application, List<ApplicationDocument> documents,
            List<ReviewDecision> decisions) {
    }

    public record UploadTicket(ApplicationDocument document, PresignedRequest upload) {
    }

    public SellerApplication create(Caller applicant, String sellerHandle, Map<String, Object> kyc) {
        if (applicant.sellerId() != null) {
            throw new ApplicationConflictException("ALREADY_SELLER", "You already sell as " + applicant.sellerId());
        }
        Map<String, Object> validated = validate(sellerHandle, kyc);
        Instant now = clock.instant();
        var application = new SellerApplication(UUID.randomUUID(), applicant.subject(), sellerHandle, validated,
                ApplicationStatus.DRAFT, null, false, 0, now, now, null, null);
        try {
            applications.insert(application);
        }
        catch (DuplicateKeyException ex) {
            throw uniqueViolation(ex);
        }
        return application;
    }

    public SellerApplication update(UUID id, Caller applicant, String sellerHandle, Map<String, Object> kyc) {
        SellerApplication current = owned(id, applicant);
        requireEditable(current);
        Map<String, Object> validated = sellerHandle.equals(current.sellerHandle())
                ? forms.validate(KYC_FORM, kyc).data()
                : validate(sellerHandle, kyc);
        try {
            return applications.updateDetails(id, current.version(), sellerHandle, validated, clock.instant())
                    .orElseThrow(() -> new ApplicationConflictException("CONCURRENT_CHANGE",
                            "The application changed while you were editing it; reload and try again"));
        }
        catch (DuplicateKeyException ex) {
            throw uniqueViolation(ex);
        }
    }

    public UploadTicket requestUpload(UUID id, Caller applicant, DocumentType type, String contentType,
            long sizeBytes) {
        var config = properties.documents();
        if (!config.contentTypes().contains(contentType)) {
            throw new ApplicationConflictException("UNSUPPORTED_DOCUMENT_TYPE",
                    "Documents must be one of " + String.join(", ", config.contentTypes()));
        }
        if (sizeBytes <= 0 || sizeBytes > config.maxBytes()) {
            throw new ApplicationConflictException("DOCUMENT_TOO_LARGE",
                    "Documents must be at most " + config.maxBytes() + " bytes");
        }
        requireEditable(owned(id, applicant));
        var document = new ApplicationDocument(UUID.randomUUID(), id, type, contentType, sizeBytes,
                ApplicationDocument.Status.PENDING, clock.instant(), null);
        applications.insertDocument(document);
        return new UploadTicket(document, storage.store().presignUpload(DocumentStorage.uploadKey(document),
                contentType, sizeBytes, config.uploadUrlTtl()));
    }

    /**
     * Checks what was actually uploaded: its size, and from its first bytes, its real type. A file
     * that is not what it claimed to be is deleted.
     */
    public ApplicationDocument completeUpload(UUID id, UUID documentId, Caller applicant) {
        requireEditable(owned(id, applicant));
        ApplicationDocument document = applications.document(id, documentId)
                .orElseThrow(() -> new ApplicationNotFoundException(documentId));
        switch (document.status()) {
            case VERIFIED -> {
                return document;
            }
            case REJECTED -> throw new ApplicationConflictException("DOCUMENT_REJECTED",
                    "This upload was rejected; start a new one");
            case PENDING -> {
                // verified below
            }
        }
        String uploadKey = DocumentStorage.uploadKey(document);
        StoredObject stored = storage.store().stat(uploadKey).orElseThrow(() -> new ApplicationConflictException(
                "UPLOAD_MISSING", "Nothing has been uploaded yet; PUT the file to the upload URL first"));
        String problem = verify(document, uploadKey, stored);
        if (problem != null) {
            storage.store().delete(uploadKey);
            applications.settleDocument(documentId, ApplicationDocument.Status.REJECTED, clock.instant());
            throw new InvalidDocumentException(problem);
        }
        storage.store().move(uploadKey, DocumentStorage.verifiedKey(document), document.contentType(), "private");
        applications.settleDocument(documentId, ApplicationDocument.Status.VERIFIED, clock.instant());
        return applications.document(id, documentId).orElseThrow();
    }

    /**
     * Submits a draft, or resubmits after a request for information. Every document type must have
     * a verified upload.
     */
    @Transactional
    public SellerApplication submit(UUID id, Caller applicant) {
        SellerApplication application = owned(id, applicant);
        Set<DocumentType> missing = EnumSet.allOf(DocumentType.class);
        applications.documents(id).stream()
                .filter(document -> document.status() == ApplicationDocument.Status.VERIFIED)
                .map(ApplicationDocument::type)
                .forEach(missing::remove);
        if (!missing.isEmpty()) {
            throw new ApplicationConflictException("DOCUMENTS_MISSING", "Verified documents are missing: " + missing);
        }
        switch (application.status()) {
            case DRAFT -> {
                move(application, ApplicationStatus.DRAFT);
                runtime.startProcessInstanceByKey(Onboarding.PROCESS_KEY, id.toString(), Map.of(
                        Onboarding.APPLICATION_ID, id.toString(),
                        Onboarding.APPLICANT_ID, application.applicantId(),
                        Onboarding.REVIEW_SLA, properties.reviewSla().toString(),
                        Onboarding.INFORMATION_DEADLINE, properties.informationDeadline().toString()));
            }
            case INFORMATION_REQUESTED -> {
                move(application, ApplicationStatus.INFORMATION_REQUESTED);
                var task = tasks.createTaskQuery()
                        .processInstanceBusinessKey(id.toString())
                        .taskDefinitionKey(Onboarding.TASK_PROVIDE_INFORMATION)
                        .singleResult();
                if (task == null) {
                    throw new IllegalStateException("Application " + id + " has no open request for information");
                }
                tasks.complete(task.getId());
            }
            default -> throw notEditable(application);
        }
        return applications.find(id).orElseThrow();
    }

    public Detail get(UUID id, Caller caller) {
        SellerApplication application = visible(id, caller);
        // Reviewers see the full audit trail. Applicants see the decisions and reasons, not who made them.
        List<ReviewDecision> decisions = applications.decisions(id);
        if (!Permissions.isReviewer(caller)) {
            decisions = decisions.stream()
                    .map(d -> new ReviewDecision(d.applicationId(), d.stage(), null, d.decision(), d.note(),
                            d.decidedAt()))
                    .toList();
        }
        return new Detail(application, applications.documents(id), decisions);
    }

    public List<SellerApplication> mine(Caller applicant) {
        return applications.forApplicant(applicant.subject());
    }

    /** A short-lived link to a verified document, for its applicant or a reviewer. */
    public PresignedRequest downloadUrl(UUID id, UUID documentId, Caller caller) {
        visible(id, caller);
        ApplicationDocument document = applications.document(id, documentId)
                .filter(candidate -> candidate.status() == ApplicationDocument.Status.VERIFIED)
                .orElseThrow(() -> new ApplicationNotFoundException(documentId));
        return storage.store().presignDownload(DocumentStorage.verifiedKey(document),
                properties.documents().downloadUrlTtl());
    }

    private Map<String, Object> validate(String sellerHandle, Map<String, Object> kyc) {
        if (sellerHandle == null || !SELLER_HANDLE.matcher(sellerHandle).matches()) {
            throw new ApplicationConflictException("INVALID_SELLER_ID",
                    "Seller ID must be 2-40 lowercase letters, digits or dashes");
        }
        Map<String, Object> validated = forms.validate(KYC_FORM, kyc).data();
        if (keycloak.sellerIdTaken(sellerHandle)) {
            throw new ApplicationConflictException("SELLER_ID_TAKEN", "Seller ID " + sellerHandle + " is taken");
        }
        return validated;
    }

    private void move(SellerApplication application, ApplicationStatus from) {
        if (!applications.transition(application.id(), Set.of(from), ApplicationStatus.SUBMITTED, clock.instant())) {
            throw new ApplicationConflictException("CONCURRENT_CHANGE",
                    "The application changed while it was being submitted; reload and try again");
        }
    }

    private SellerApplication owned(UUID id, Caller caller) {
        return applications.find(id)
                .filter(application -> application.ownedBy(caller.subject()))
                .orElseThrow(() -> new ApplicationNotFoundException(id));
    }

    private SellerApplication visible(UUID id, Caller caller) {
        return applications.find(id)
                .filter(application -> application.ownedBy(caller.subject()) || Permissions.isReviewer(caller))
                .orElseThrow(() -> new ApplicationNotFoundException(id));
    }

    private static void requireEditable(SellerApplication application) {
        if (!ApplicationStatus.EDITABLE.contains(application.status())) {
            throw notEditable(application);
        }
    }

    private static ApplicationConflictException notEditable(SellerApplication application) {
        return new ApplicationConflictException("NOT_EDITABLE",
                "The application is " + application.status() + " and cannot be changed now");
    }

    private String verify(ApplicationDocument document, String uploadKey, StoredObject stored) {
        if (stored.size() != document.sizeBytes()) {
            return "Uploaded file is " + stored.size() + " bytes but " + document.sizeBytes() + " were declared";
        }
        var actual = FileTypes.detect(storage.store().head(uploadKey, FileTypes.SNIFF_BYTES));
        if (actual.isEmpty() || !actual.get().equals(document.contentType())) {
            return "Uploaded file is not the declared " + document.contentType();
        }
        return null;
    }

    private static ApplicationConflictException uniqueViolation(DuplicateKeyException ex) {
        String message = String.valueOf(ex.getMostSpecificCause().getMessage());
        if (message.contains("ux_application_handle")) {
            return new ApplicationConflictException("SELLER_ID_TAKEN", "Another application asked for this seller ID");
        }
        return new ApplicationConflictException("APPLICATION_EXISTS",
                "You already have an application in progress or approved");
    }
}
