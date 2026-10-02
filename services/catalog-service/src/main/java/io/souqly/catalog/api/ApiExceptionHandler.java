package io.souqly.catalog.api;

import java.util.List;
import java.util.Map;

import io.souqly.catalog.api.Preconditions.PreconditionRequiredException;
import io.souqly.catalog.category.CategoryNotFoundException;
import io.souqly.catalog.formio.AttributeValidationException;
import io.souqly.catalog.formio.FormNotFoundException;
import io.souqly.catalog.formio.SchemaUnavailableException;
import io.souqly.catalog.product.ProductExceptions.InvalidImageException;
import io.souqly.catalog.product.ProductExceptions.ProductAccessDeniedException;
import io.souqly.catalog.product.ProductExceptions.ProductNotFoundException;
import io.souqly.catalog.product.ProductExceptions.ProductStateException;
import io.souqly.catalog.product.ProductExceptions.SkuTakenException;
import io.souqly.catalog.product.ProductExceptions.VersionConflictException;
import io.souqly.platform.mongo.TransactionContentionException;
import io.souqly.platform.web.Problems;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** Lifecycle rules: which code means which status. */
    private static final Map<String, HttpStatus> STATE_STATUS = Map.of(
            "UNKNOWN_CATEGORY", HttpStatus.UNPROCESSABLE_CONTENT,
            "UNSUPPORTED_IMAGE_TYPE", HttpStatus.UNPROCESSABLE_CONTENT,
            "IMAGE_TOO_LARGE", HttpStatus.UNPROCESSABLE_CONTENT,
            "IMAGE_NOT_FOUND", HttpStatus.NOT_FOUND,
            "IMAGE_REQUIRED", HttpStatus.CONFLICT,
            "IMAGE_LIMIT", HttpStatus.CONFLICT,
            "IMAGE_REJECTED", HttpStatus.CONFLICT,
            "UPLOAD_MISSING", HttpStatus.CONFLICT);

    @ExceptionHandler
    ProblemDetail attributes(AttributeValidationException ex) {
        var problem = Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_ATTRIBUTES", "Invalid listing attributes",
                ex.getMessage());
        problem.setProperty("errors", ex.errors());
        return problem;
    }

    @ExceptionHandler
    ProblemDetail state(ProductStateException ex) {
        return Problems.of(STATE_STATUS.getOrDefault(ex.code(), HttpStatus.CONFLICT), ex.code(), "Request not allowed",
                ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail invalidImage(InvalidImageException ex) {
        return Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_IMAGE", "Image rejected", ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail productNotFound(ProductNotFoundException ex) {
        return Problems.of(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Listing not found", ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail categoryNotFound(CategoryNotFoundException ex) {
        return Problems.of(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "Category not found", ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail formNotFound(FormNotFoundException ex) {
        return Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_FORM", "Form not found", ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail accessDenied(ProductAccessDeniedException ex) {
        return Problems.of(HttpStatus.FORBIDDEN, ex.reason().name(), "Forbidden", ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail skuTaken(SkuTakenException ex) {
        return Problems.of(HttpStatus.CONFLICT, "SKU_TAKEN", "SKU already listed", ex.getMessage());
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> versionConflict(VersionConflictException ex) {
        var problem = Problems.of(HttpStatus.PRECONDITION_FAILED, "VERSION_CONFLICT", "Listing was modified",
                ex.getMessage());
        problem.setProperty("currentVersion", ex.currentVersion());
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .eTag("\"" + ex.currentVersion() + "\"").body(problem);
    }

    @ExceptionHandler
    ProblemDetail preconditionRequired(PreconditionRequiredException ex) {
        return Problems.of(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED", "If-Match required",
                ex.getMessage());
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> schemaUnavailable(SchemaUnavailableException ex) {
        logger.warn("Category schemas unavailable: " + ex.getMessage());
        var problem = Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "SCHEMA_UNAVAILABLE",
                "Listings cannot be validated right now", "The category schema service is unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5").body(problem);
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> contention(TransactionContentionException ex) {
        var problem = Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "CONTENTION", "Too much contention, retry shortly",
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1").body(problem);
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
