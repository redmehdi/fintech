package com.eurmad.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(OutputCaptureExtension.class)
class ProblemErrorControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProblemErrorController()).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
        "400, Bad Request",
        "401, Unauthorized",
        "403, Forbidden",
        "404, Not Found",
        "405, Method Not Allowed",
        "409, Conflict",
        "422, Unprocessable Entity",
        "429, Too Many Requests",
        "500, An unexpected server error occurred."
    })
    void errorDispatchUsesStatusAndSafeEnvelope(int statusCode, String message) throws Exception {
        String suppliedId = "00000000-0000-0000-0000-000000000001";
        MockHttpServletResponse response = mvc.perform(get("/error")
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            return request;
                        })
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, statusCode)
                        .requestAttr(RequestDispatcher.ERROR_MESSAGE, "synthetic-private-message")
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new Exception("synthetic-private-exception"))
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/v1/synthetic-private-path")
                        .header("X-Correlation-Id", suppliedId))
                .andExpect(status().is(statusCode))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();

        JsonNode problem = mapper.readTree(response.getContentAsByteArray());
        assertThat(problem.size()).isEqualTo(3);
        assertThat(problem.path("code").asText()).isEqualTo(HttpStatus.valueOf(statusCode).name());
        assertThat(problem.path("message").asText()).isEqualTo(message);
        String correlationId = problem.path("correlationId").asText();
        assertThat(UUID.fromString(correlationId).toString()).isEqualTo(correlationId).isNotEqualTo(suppliedId);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(correlationId);
    }

    @Test
    void serverErrorLogCorrelatesFailureWithoutLoggingTheExceptionMessage(CapturedOutput output) throws Exception {
        String privateMessage = "synthetic-private-failure";
        MockHttpServletResponse response = mvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException(privateMessage)))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse();

        assertThat(output.getOut())
                .contains("correlationId=" + response.getHeader("X-Correlation-Id")
                        + " exceptionType=java.lang.IllegalStateException")
                .doesNotContain(privateMessage);
    }

    @Test
    void directErrorRouteReturnsNotFound() throws Exception {
        mvc.perform(get("/error").param("status", "200"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
