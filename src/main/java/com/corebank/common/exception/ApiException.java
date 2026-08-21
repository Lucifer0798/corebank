package com.corebank.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for errors that map onto a deliberate HTTP response rather than a 500.
 * The {@code code} is a stable, machine-readable token clients can branch on.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
