package io.souqly.seller.api;

import java.util.List;
import java.util.Map;

import io.souqly.platform.formio.FormNotFoundException;
import io.souqly.platform.formio.FormValidationException;
import io.souqly.platform.formio.FormioUnavailableException;
import io.souqly.platform.web.Problems;
import io.souqly.seller.application.ApplicationExceptions.ApplicationConflictException;
import io.souqly.seller.application.ApplicationExceptions.ApplicationNotFoundException;
import io.souqly.seller.application.ApplicationExceptions.InvalidDocumentException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** RFC 9457 problem details with stable codes. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** Input problems; every other conflict code is a 409. */
    private static final Map<String, HttpStatus> STATUS = Map.of(
            "INVALID_SELLER_ID", HttpStatus.UNPROCESSABLE_CONTENT,
            "UNSUPPORTED_DOCUMENT_TYPE", HttpStatus.UNPROCESSABLE_CONTENT,
            "DOCUMENT_TOO_LARGE", HttpStatus.UNPROCESSABLE_CONTENT,
            "NOTE_REQUIRED", HttpStatus.UNPROCESSABLE_CONTENT,
            "DECISION_NOT_ALLOWED", HttpStatus.UNPROCESSABLE_CONTENT);

    @ExceptionHandler
    ProblemDetail conflict(ApplicationConflictException ex) {
        return Problems.of(STATUS.getOrDefault(ex.code(), HttpStatus.CONFLICT), ex.code(), "Request not allowed",
                ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail notFound(ApplicationNotFoundException ex) {
        return Problems.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail invalidDocument(InvalidDocumentException ex) {
        return Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DOCUMENT", "Document rejected", ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail kyc(FormValidationException ex) {
        var problem = Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_KYC", "Invalid KYC details",
                "Some details do not satisfy the seller application form");
        problem.setProperty("errors", ex.errors());
        return problem;
    }

    @ExceptionHandler
    ProblemDetail formMissing(FormNotFoundException ex) {
        logger.error("The seller-kyc form is not loaded in Form.io: " + ex.getMessage());
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "FORM_UNAVAILABLE", "Applications are unavailable",
                "The application form is not available");
    }

    @ExceptionHandler({FormioUnavailableException.class, RestClientException.class})
    ResponseEntity<ProblemDetail> dependencyDown(RuntimeException ex) {
        logger.warn("A dependency of seller applications is unavailable: " + ex.getMessage());
        var problem = Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                "Applications cannot be processed right now", "Please retry shortly");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5").body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();
        var problem = Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid request",
                "Request validation failed");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
}
