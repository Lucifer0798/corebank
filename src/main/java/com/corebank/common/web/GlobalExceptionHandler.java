package com.corebank.common.web;

import com.corebank.common.exception.ApiException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every failure into an RFC 7807 problem document so clients get one predictable error shape.
 * Internal detail never leaks: unexpected exceptions are logged here and reported as a bare 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String CODE = "code";

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        return problem(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleInvalidBody(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new TreeMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> errors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleInvalidParameters(HandlerMethodValidationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
    }

    /**
     * The one query parameters actually raise. Every paginated controller is annotated
     * {@code @Validated}, which routes their {@code @Min}/{@code @Max} parameters through
     * {@code MethodValidationInterceptor} (AOP) rather than the built-in handler-method path --
     * so they arrive here as a {@link ConstraintViolationException} and never as the
     * {@link HandlerMethodValidationException} above. Without this, a {@code page=-1} fell all
     * the way through to the catch-all and came back as a 500, telling a caller their own bad
     * parameter was the server's fault.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> errors = new TreeMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            // "list.page" -> "page": the method the parameter belongs to is an implementation
            // detail, and the caller only ever supplied the last segment.
            String path = violation.getPropertyPath().toString();
            errors.putIfAbsent(path.substring(path.lastIndexOf('.') + 1), violation.getMessage());
        }
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
        return problem(HttpStatus.BAD_REQUEST, "MISSING_HEADER",
                "Required header '" + ex.getHeaderName() + "' is missing");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        return problem(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION",
                "The request conflicts with data that already exists");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The record was modified concurrently; retry the request");
    }

    /**
     * A path this API does not serve. Spring hands an unmatched request to the static resource
     * handler, which raises this rather than anything routing-shaped -- so without a handler it
     * reached the catch-all below and came back as a 500, logged at ERROR with a stack trace.
     * Routine scanner and typo traffic reading as the application falling over is both wrong for
     * the caller and noise for whoever watches the logs.
     *
     * <p>Its own code rather than {@code RESOURCE_NOT_FOUND}: a client branching on this needs to
     * tell "the URL you called does not exist" from "the id you asked for does not exist", and
     * only the second is worth retrying against a different record. Nothing about the requested
     * path is echoed back, matching every other handler here.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND", "No endpoint exists at this path");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You are not allowed to perform this action");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "Authentication failed");
    }

    /**
     * A request body that is missing, is not JSON, or has a field of the wrong type. Not an
     * {@link ErrorResponse}, so the catch-all's check below would never see it. The parser's own
     * message quotes the payload and names Jackson internals, so none of it is passed on.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Rejected unreadable request body: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST_BODY",
                "The request body is missing, is not valid JSON, or has a field of the wrong type");
    }

    /**
     * A path variable or query parameter that will not convert, such as a non-UUID id. Also not
     * an {@link ErrorResponse}. Names the parameter, which the API declares, but not the value,
     * which the caller sent.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Rejected request parameter: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "Parameter '" + ex.getName() + "' has an invalid value");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex) {
        return problem(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
                "Required parameter '" + ex.getParameterName() + "' is missing");
    }

    /**
     * Genuine 500s, plus every framework client error the handlers above do not name. The second
     * group is why this checks for {@link ErrorResponse} before answering 500: Spring MVC's own
     * client errors (an unsupported content type or method, an unacceptable {@code Accept})
     * implement it and carry their real status, and used to be swallowed here as 500s logged at
     * ERROR -- a form-encoded POST to unfreeze answered 500 instead of 415.
     *
     * <p>The check lives here rather than in its own {@code @ExceptionHandler} because
     * {@code ErrorResponse} is an interface, which the annotation cannot name, and the exceptions
     * implementing it share no common class below {@link Exception}: some extend
     * {@code ErrorResponseException}, most extend {@code ServletException}. Checking the interface
     * covers both, and any the framework adds later, without listing them. A framework exception
     * reporting a 5xx is still a server fault and is treated as one.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            return handleFrameworkClientError(ex, error);
        }
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred"));
    }

    /**
     * The code is the status's own name ({@code METHOD_NOT_ALLOWED}, {@code UNSUPPORTED_MEDIA_TYPE},
     * {@code NOT_ACCEPTABLE}), so a framework exception nobody here has heard of still gets a
     * stable one. The framework's headers are kept: they carry the {@code Allow} a 405 owes the
     * caller and the {@code Accept} that tells a 415 what would have worked.
     */
    private ResponseEntity<ProblemDetail> handleFrameworkClientError(Exception ex, ErrorResponse error) {
        HttpStatusCode status = error.getStatusCode();
        log.warn("Rejected request with {}: {}", status.value(), ex.getMessage());
        HttpStatus known = HttpStatus.resolve(status.value());
        String code = known != null ? known.name() : "HTTP_" + status.value();
        String detail = switch (status.value()) {
            case 405 -> "This endpoint does not support that HTTP method; see the Allow header";
            case 406 -> "This endpoint cannot produce any of the requested media types";
            case 415 -> "This endpoint does not accept that content type; see the Accept header";
            default -> known != null ? known.getReasonPhrase() : "The request was rejected";
        };
        return ResponseEntity.status(status).headers(error.getHeaders()).body(problem(status, code, detail));
    }

    private ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        HttpStatus known = HttpStatus.resolve(status.value());
        problem.setTitle(known != null ? known.getReasonPhrase() : null);
        problem.setType(URI.create("https://corebank.example/problems/" + code.toLowerCase().replace('_', '-')));
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put(CODE, code);
        extras.put("timestamp", Instant.now().toString());
        extras.forEach(problem::setProperty);
        return problem;
    }
}
