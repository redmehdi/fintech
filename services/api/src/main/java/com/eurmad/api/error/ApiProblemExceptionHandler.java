package com.eurmad.api.error;

import com.eurmad.api.error.ProblemErrorController.Problem;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiProblemExceptionHandler {

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<Problem> handle(ApiProblemException exception, HttpServletResponse response) {
        return ProblemResponses.response(response, exception.status().value(), UUID.randomUUID());
    }
}
