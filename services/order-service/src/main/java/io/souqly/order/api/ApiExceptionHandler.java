package io.souqly.order.api;

import java.util.List;

import io.souqly.order.checkout.CheckoutExceptions.IdempotencyKeyReusedException;
import io.souqly.order.checkout.CheckoutExceptions.MixedCurrencyException;
import io.souqly.order.checkout.CheckoutExceptions.OrderNotFoundException;
import io.souqly.order.checkout.CheckoutExceptions.ProductUnavailableException;
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

/** RFC 9457 problem details with stable codes. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler
    ProblemDetail keyReused(IdempotencyKeyReusedException ex) {
        return Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key reused with a different request", ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail unavailable(ProductUnavailableException ex) {
        var problem = Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "PRODUCT_UNAVAILABLE",
                "Some items cannot be bought", ex.getMessage());
        problem.setProperty("skus", ex.skus());
        return problem;
    }

    @ExceptionHandler
    ProblemDetail mixedCurrency(MixedCurrencyException ex) {
        return Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "MIXED_CURRENCY", "Items priced in different currencies",
                ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail notFound(OrderNotFoundException ex) {
        return Problems.of(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found", ex.getMessage());
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
