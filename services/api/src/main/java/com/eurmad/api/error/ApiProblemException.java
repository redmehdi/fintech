package com.eurmad.api.error;

import org.springframework.http.HttpStatus;

/**
 * Application failure rendered as the shared Problem envelope. Carries only an HTTP status: the
 * client-facing message is fixed per status so no internal detail can leak.
 */
public final class ApiProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    private ApiProblemException(HttpStatus status) {
        super(status.name(), null, false, false);
        this.status = status;
    }

    /** The request is malformed (e.g. carries a body where the contract accepts none). */
    public static ApiProblemException badRequest() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST);
    }

    /** Resource is absent or not owned by the caller; the two cases are indistinguishable. */
    public static ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND);
    }

    /** The request conflicts with the current state of the resource (e.g. a disallowed transition). */
    public static ApiProblemException conflict() {
        return new ApiProblemException(HttpStatus.CONFLICT);
    }

    /** The caller is not eligible for the operation or a business rule rejected it. */
    public static ApiProblemException unprocessable() {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** Transient contention (e.g. a lock wait timed out); nothing was changed and a retry may succeed. */
    public static ApiProblemException serviceUnavailable() {
        return new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE);
    }

    public HttpStatus status() {
        return status;
    }
}
