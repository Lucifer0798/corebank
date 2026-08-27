package com.corebank.common.exception;

import org.springframework.http.HttpStatus;

/**
 * OpenSearch is a downstream read projection, not the source of truth for anything -- the same
 * status Kafka and Redis already have in this application. A search request that can't reach it
 * fails cleanly with this rather than a bare 500, but nothing else in the system is affected: no
 * posting, KYC decision or other write ever depends on search being up.
 */
public class SearchUnavailableException extends ApiException {

    public SearchUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_UNAVAILABLE", "Search is temporarily unavailable");
        initCause(cause);
    }
}
