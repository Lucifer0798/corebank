package com.corebank.grpc;

import com.corebank.common.exception.ApiException;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The gRPC counterpart of {@code GlobalExceptionHandler}: turns the application's own
 * {@link ApiException} hierarchy into gRPC statuses instead of RFC 7807 problem documents, so a
 * business rule rejected on this surface reports the same distinction (not found vs. conflict vs.
 * rule violation) it does over HTTP.
 *
 * <p>The stable {@code code} that the JSON API puts in the problem body is attached here as a
 * {@code corebank-code} trailing metadata entry, so a gRPC client can branch on exactly the same
 * token an HTTP client does.
 *
 * <p>Anything unrecognised becomes a bare {@code INTERNAL} with no detail, logged server-side --
 * matching the HTTP handler's rule that internal failure detail never leaves the process.
 */
@Component
@GlobalServerInterceptor
@Order(1)
public class GrpcExceptionInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcExceptionInterceptor.class);
    static final Metadata.Key<String> CODE_KEY =
            Metadata.Key.of("corebank-code", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <R, S> ServerCall.Listener<R> interceptCall(ServerCall<R, S> call, Metadata headers,
                                                       ServerCallHandler<R, S> next) {
        ServerCall.Listener<R> delegate = next.startCall(new ErrorTranslatingCall<>(call), headers);
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onHalfClose() {
                try {
                    super.onHalfClose();
                } catch (RuntimeException ex) {
                    closeWithTranslatedStatus(call, ex);
                }
            }

            @Override
            public void onMessage(R message) {
                try {
                    super.onMessage(message);
                } catch (RuntimeException ex) {
                    closeWithTranslatedStatus(call, ex);
                }
            }
        };
    }

    private static <R, S> void closeWithTranslatedStatus(ServerCall<R, S> call, RuntimeException ex) {
        Metadata trailers = new Metadata();
        call.close(toStatus(ex, trailers), trailers);
    }

    private static Status toStatus(RuntimeException ex, Metadata trailers) {
        if (ex instanceof StatusRuntimeException statusException) {
            // Already carries a deliberate status -- GrpcRequests' INVALID_ARGUMENT/PERMISSION_DENIED.
            return statusException.getStatus();
        }
        if (ex instanceof AccessDeniedException) {
            return Status.PERMISSION_DENIED.withDescription("You are not allowed to perform this action");
        }
        if (ex instanceof ApiException apiException) {
            trailers.put(CODE_KEY, apiException.getCode());
            return statusFor(apiException.getStatus()).withDescription(apiException.getMessage());
        }
        log.error("Unhandled exception on a gRPC call", ex);
        return Status.INTERNAL.withDescription("An unexpected error occurred");
    }

    private static Status statusFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> Status.NOT_FOUND;
            case CONFLICT -> Status.ABORTED;
            case UNPROCESSABLE_ENTITY -> Status.FAILED_PRECONDITION;
            case BAD_REQUEST -> Status.INVALID_ARGUMENT;
            case FORBIDDEN -> Status.PERMISSION_DENIED;
            case UNAUTHORIZED -> Status.UNAUTHENTICATED;
            case SERVICE_UNAVAILABLE -> Status.UNAVAILABLE;
            default -> Status.INTERNAL;
        };
    }

    /** Catches exceptions thrown while the response is being sent, not just while it is handled. */
    private static final class ErrorTranslatingCall<R, S>
            extends io.grpc.ForwardingServerCall.SimpleForwardingServerCall<R, S> {

        private ErrorTranslatingCall(ServerCall<R, S> delegate) {
            super(delegate);
        }

        @Override
        public void close(Status status, Metadata trailers) {
            if (status.isOk() || status.getCause() == null) {
                super.close(status, trailers);
                return;
            }
            Status translated = status.getCause() instanceof RuntimeException runtime
                    ? toStatus(runtime, trailers)
                    : status;
            super.close(translated, trailers);
        }
    }
}
