package io.souqly.seller.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import io.souqly.platform.security.Caller;
import io.souqly.seller.api.ApiModels.ApplicationRequest;
import io.souqly.seller.api.ApiModels.ApplicationResponse;
import io.souqly.seller.api.ApiModels.DocumentUploadRequest;
import io.souqly.seller.api.ApiModels.DocumentView;
import io.souqly.seller.api.ApiModels.DownloadResponse;
import io.souqly.seller.api.ApiModels.UploadResponse;
import io.souqly.seller.application.ApplicationService;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/applications")
class ApplicationController {

    private final ApplicationService applications;

    ApplicationController(ApplicationService applications) {
        this.applications = applications;
    }

    /** Starts an application as a draft. The KYC details are validated by the {@code seller-kyc} form. */
    @PostMapping
    ResponseEntity<ApplicationResponse> create(@Valid @RequestBody ApplicationRequest request,
            Authentication authentication) {
        var application = applications.create(Caller.from(authentication), request.sellerId(), request.kyc());
        return ResponseEntity.created(URI.create("/api/v1/applications/" + application.id()))
                .body(ApplicationResponse.from(application));
    }

    @GetMapping
    List<ApplicationResponse> mine(Authentication authentication) {
        return applications.mine(Caller.from(authentication)).stream().map(ApplicationResponse::from).toList();
    }

    @GetMapping("/{id}")
    ApplicationResponse get(@PathVariable UUID id, Authentication authentication) {
        return ApplicationResponse.from(applications.get(id, Caller.from(authentication)));
    }

    /** Changes details while the application is a draft or more information was requested. */
    @PutMapping("/{id}")
    ApplicationResponse update(@PathVariable UUID id, @Valid @RequestBody ApplicationRequest request,
            Authentication authentication) {
        return ApplicationResponse.from(
                applications.update(id, Caller.from(authentication), request.sellerId(), request.kyc()));
    }

    /** Returns a signed URL to PUT one document to, straight to object storage. */
    @PostMapping("/{id}/documents")
    ResponseEntity<UploadResponse> requestUpload(@PathVariable UUID id,
            @Valid @RequestBody DocumentUploadRequest request, Authentication authentication) {
        var ticket = applications.requestUpload(id, Caller.from(authentication), request.type(),
                request.contentType(), request.sizeBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(new UploadResponse(DocumentView.from(ticket.document()),
                ticket.upload().url(), ticket.upload().headers(), ticket.upload().expiresAt()));
    }

    /** Verifies an uploaded document's size and real file type. */
    @PostMapping("/{id}/documents/{documentId}/complete")
    DocumentView completeUpload(@PathVariable UUID id, @PathVariable UUID documentId,
            Authentication authentication) {
        return DocumentView.from(applications.completeUpload(id, documentId, Caller.from(authentication)));
    }

    /** A short-lived link to read a verified document. */
    @GetMapping("/{id}/documents/{documentId}")
    DownloadResponse download(@PathVariable UUID id, @PathVariable UUID documentId, Authentication authentication) {
        var link = applications.downloadUrl(id, documentId, Caller.from(authentication));
        return new DownloadResponse(link.url(), link.expiresAt());
    }

    /** Submits for review, or resubmits after a request for information. The review runs asynchronously. */
    @PostMapping("/{id}/submit")
    ResponseEntity<ApplicationResponse> submit(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.accepted()
                .body(ApplicationResponse.from(applications.submit(id, Caller.from(authentication))));
    }
}
