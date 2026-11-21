package com.eurmad.api.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProblemErrorController implements ErrorController {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemErrorController.class);

    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<Problem> error(HttpServletRequest request, HttpServletResponse response) {
        // Use the servlet's status and fixed messages. Error attributes can contain request data.
        Object errorStatus = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int statusCode = errorStatus instanceof Integer status ? status : HttpStatus.NOT_FOUND.value();
        UUID correlationId = UUID.randomUUID();
        if (statusCode >= 500) {
            Object error = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            String exceptionType = error instanceof Throwable failure ? failure.getClass().getName() : "none";
            LOG.error("API error status={} correlationId={} exceptionType={}", statusCode, correlationId,
                    exceptionType);
        }
        return ProblemResponses.response(response, statusCode, correlationId);
    }

    public record Problem(String code, String message, UUID correlationId) {}
}
