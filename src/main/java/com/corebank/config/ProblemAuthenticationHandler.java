package com.corebank.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Rejections from the security filter chain never reach a controller, so they would otherwise
 * bypass the global handler and return a differently shaped body. This writes the same
 * RFC 7807 document the rest of the API uses.
 */
@Component
public class ProblemAuthenticationHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "A valid bearer token is required to access this resource");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You are not allowed to perform this action");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {"type":"https://corebank.example/problems/%s","title":"%s","status":%d,\
                "detail":"%s","code":"%s","timestamp":"%s"}"""
                .formatted(code.toLowerCase().replace('_', '-'), status.getReasonPhrase(),
                        status.value(), detail, code, Instant.now()));
    }
}
