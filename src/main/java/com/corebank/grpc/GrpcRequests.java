package com.corebank.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Request-parsing helpers that fail with the right gRPC status rather than letting a
 * {@code IllegalArgumentException} escape as {@code UNKNOWN}. proto3 has no null and no UUID or
 * timestamp type, so every id and date arrives as a possibly-empty string that has to be
 * validated here rather than by bean validation the way the REST DTOs are.
 */
final class GrpcRequests {

    private GrpcRequests() {
    }

    static UUID uuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalidArgument(field + " is required");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw invalidArgument(field + " must be a UUID");
        }
    }

    /** Empty means "unbounded on this side", matching the REST statement endpoint's optional params. */
    static Instant optionalInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw invalidArgument(field + " must be an ISO-8601 instant");
        }
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalidArgument(field + " is required");
        }
        return value;
    }

    static Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /**
     * Deliberately says nothing about what was being accessed -- the same reasoning as the REST
     * side's flat {@code ACCESS_DENIED} problem document, so a probing caller cannot use the
     * error text to learn which account ids exist.
     */
    static void require(boolean permitted) {
        if (!permitted) {
            throw new StatusRuntimeException(
                    Status.PERMISSION_DENIED.withDescription("You are not allowed to perform this action"));
        }
    }

    private static StatusRuntimeException invalidArgument(String description) {
        return new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription(description));
    }
}
