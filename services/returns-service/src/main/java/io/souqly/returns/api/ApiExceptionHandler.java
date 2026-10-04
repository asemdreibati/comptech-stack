package io.souqly.returns.api;

import java.util.List;
import java.util.Map;

import io.souqly.platform.web.Problems;
import io.souqly.returns.returns.ReturnExceptions.ReturnNotFoundException;
import io.souqly.returns.returns.ReturnExceptions.ReturnRejectedException;

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

/** RFC 9457 problem details with stable codes. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Map<String, HttpStatus> STATUS = Map.of(
            "ORDER_NOT_FOUND", HttpStatus.NOT_FOUND,
            "ITEM_NOT_IN_ORDER", HttpStatus.UNPROCESSABLE_CONTENT,
            "MIXED_SELLERS", HttpStatus.UNPROCESSABLE_CONTENT,
            "IDEMPOTENCY_KEY_REUSED", HttpStatus.UNPROCESSABLE_CONTENT,
            "NOTE_REQUIRED", HttpStatus.UNPROCESSABLE_CONTENT,
            "INVALID_IDEMPOTENCY_KEY", HttpStatus.BAD_REQUEST,
            "DUPLICATE_ITEMS", HttpStatus.BAD_REQUEST);

    @ExceptionHandler
    ProblemDetail rejected(ReturnRejectedException ex) {
        return Problems.of(STATUS.getOrDefault(ex.code(), HttpStatus.CONFLICT), ex.code(), "Request not allowed",
                ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail notFound(ReturnNotFoundException ex) {
        return Problems.of(HttpStatus.NOT_FOUND, "RETURN_NOT_FOUND", "Return not found", ex.getMessage());
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
