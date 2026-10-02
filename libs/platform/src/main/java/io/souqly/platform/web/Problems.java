package io.souqly.platform.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * RFC 9457 problem details in the shape every Souqly API uses: a type URI derived from a stable
 * machine-readable {@code code}, which clients branch on instead of parsing text.
 */
public final class Problems {

    public static final String TYPE_BASE = "https://souqly.io/problems/";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String code, String title, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
