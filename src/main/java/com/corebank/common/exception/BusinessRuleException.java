package com.corebank.common.exception;

import org.springframework.http.HttpStatus;

/** A well-formed request that the banking rules refuse: frozen account, closed account, currency mismatch. */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
