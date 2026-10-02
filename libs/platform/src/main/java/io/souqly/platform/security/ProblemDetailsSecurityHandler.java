package io.souqly.platform.security;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.souqly.platform.web.Problems;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Answers 401 and 403 with the same problem-details body as every other API error. The
 * standard bearer-token handlers still run first so the RFC 6750 {@code WWW-Authenticate}
 * header is set. Token validation details are not echoed back to the caller.
 */
public class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final AuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();
    private final JsonMapper json;

    public ProblemDetailsSecurityHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException, ServletException {
        bearerEntryPoint.commence(request, response, ex);
        write(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required",
                "A valid bearer token is required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException, ServletException {
        bearerAccessDenied.handle(request, response, ex);
        write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Forbidden",
                "The token does not grant the permission this operation needs");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
            String title, String detail) throws IOException {
        var problem = Problems.of(status, code, title, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
