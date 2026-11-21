package com.eurmad.api;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Small bridge that keeps integration test assertions in ResponseEntity form. */
public final class RestTestSupport {

    private RestTestSupport() {}

    public static <T> ResponseEntity<T> getForEntity(RestTestClient client, String path, Class<T> bodyType) {
        return exchange(client, path, HttpMethod.GET, HttpEntity.EMPTY, bodyType);
    }

    public static <T> ResponseEntity<T> postForEntity(
            RestTestClient client, String path, HttpEntity<?> request, Class<T> bodyType) {
        return exchange(client, path, HttpMethod.POST, request, bodyType);
    }

    public static <T> ResponseEntity<T> exchange(
            RestTestClient client, String path, HttpMethod method, HttpEntity<?> entity, Class<T> bodyType) {
        RestTestClient.RequestBodySpec request = client.method(method).uri(path);
        request.headers(headers -> headers.addAll(entity.getHeaders()));
        RestTestClient.ResponseSpec response = entity.getBody() == null
                ? request.exchange()
                : request.body(entity.getBody()).exchange();
        EntityExchangeResult<T> result = response.returnResult(bodyType);
        return ResponseEntity.status(result.getStatus())
                .headers(result.getResponseHeaders())
                .body(result.getResponseBody());
    }
}
