package io.souqly.search.api;

import io.souqly.platform.web.Problems;
import io.souqly.search.query.SearchUnavailableException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler
    ProblemDetail invalid(InvalidSearchException ex) {
        return Problems.of(HttpStatus.BAD_REQUEST, "INVALID_SEARCH", "Invalid search", ex.getMessage());
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> unavailable(SearchUnavailableException ex) {
        logger.warn("Search unavailable: " + ex.getMessage());
        var problem = Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_UNAVAILABLE", "Search is unavailable",
                "Search is temporarily unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5").body(problem);
    }
}
