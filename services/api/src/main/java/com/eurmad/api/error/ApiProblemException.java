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

    /** Resource is absent or not owned by the caller; the two cases are indistinguishable. */
    public static ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND);
    }

    public HttpStatus status() {
        return status;
    }
}
