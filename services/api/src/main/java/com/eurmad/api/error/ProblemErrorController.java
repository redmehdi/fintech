package com.eurmad.api.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProblemErrorController implements ErrorController {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemErrorController.class);

    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<Problem> error(HttpServletRequest request) {
        // Use the servlet's status and fixed messages. Error attributes can contain request data.
        Object errorStatus = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int statusCode = errorStatus instanceof Integer status ? status : HttpStatus.NOT_FOUND.value();
        HttpStatus status = HttpStatus.resolve(statusCode);
        UUID correlationId = UUID.randomUUID();
        String code = status == null ? "HTTP_" + statusCode : status.name();
        String message = status == null ? "Request failed" : status.getReasonPhrase();
        if (statusCode >= 500) {
            message = "An unexpected server error occurred.";
            Object error = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            String exceptionType = error instanceof Throwable failure ? failure.getClass().getName() : "none";
            LOG.error("API error status={} correlationId={} exceptionType={}", statusCode, correlationId,
                    exceptionType);
        }

        return ResponseEntity.status(statusCode)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore())
                .header("X-Correlation-Id", correlationId.toString())
                .body(new Problem(code, message, correlationId));
    }

    public record Problem(String code, String message, UUID correlationId) {}
}
