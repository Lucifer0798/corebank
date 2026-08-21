package com.corebank.common.exception;

import org.springframework.http.HttpStatus;

/** State collision: duplicate email, replayed idempotency key with a different payload, stale write. */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
