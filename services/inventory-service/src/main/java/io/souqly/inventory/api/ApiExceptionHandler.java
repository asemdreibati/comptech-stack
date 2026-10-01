package io.souqly.inventory.api;

import java.net.URI;
import java.util.List;

import io.souqly.inventory.reservation.IdempotencyConflictException;
import io.souqly.inventory.reservation.ReservationNotFoundException;
import io.souqly.inventory.reservation.ReservationStateException;
import io.souqly.inventory.stock.InsufficientStockException;
import io.souqly.inventory.stock.StockNotFoundException;
import io.souqly.inventory.support.TransactionContentionException;

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

/**
 * Maps failures to RFC 9457 problem details. Every problem carries a stable {@code code} that
 * clients can branch on without parsing human-readable text.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String TYPE_BASE = "https://souqly.io/problems/";

    @ExceptionHandler
    ProblemDetail insufficientStock(InsufficientStockException ex) {
        var problem = problem(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "Insufficient stock", ex.getMessage());
        problem.setProperty("sku", ex.sku());
        problem.setProperty("requested", ex.requested());
        problem.setProperty("available", ex.available());
        return problem;
    }

    @ExceptionHandler
    ProblemDetail stockNotFound(StockNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "SKU_NOT_FOUND", "Unknown SKU", ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail reservationNotFound(ReservationNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "Reservation not found", ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail reservationState(ReservationStateException ex) {
        var problem = ex.expired()
                ? problem(HttpStatus.CONFLICT, "RESERVATION_EXPIRED", "Reservation expired", ex.getMessage())
                : problem(HttpStatus.CONFLICT, "INVALID_RESERVATION_STATE", "Invalid reservation state",
                        ex.getMessage());
        problem.setProperty("status", ex.current());
        return problem;
    }

    @ExceptionHandler
    ProblemDetail idempotencyConflict(IdempotencyConflictException ex) {
        return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Order ID reused with different lines",
                ex.getMessage());
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> contention(TransactionContentionException ex) {
        var problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "CONTENTION", "Too much contention, retry shortly",
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
        var problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid request", "Request validation failed");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
