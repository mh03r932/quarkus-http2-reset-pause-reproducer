package org.acme;

import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;

/**
 * The third way in, and the one that reaches the {@code setReadListener()} branch of quarkusio/quarkus#56595:
 * {@link FormRoute} consumes the body itself, so the request has ended and nothing is buffered in the routing
 * context, and the resource parses form data on the non-blocking path.
 */
@QuarkusTest
public class FormParseTest {

    @Inject
    Vertx vertx;

    @TestHTTPResource("/form")
    URL url;

    @Test
    public void http2() throws Exception {
        post(HttpVersion.HTTP_2);
    }

    @Test
    public void http11() throws Exception {
        post(HttpVersion.HTTP_1_1);
    }

    private void post(HttpVersion version) throws Exception {
        HttpClientOptions options = new HttpClientOptions().setProtocolVersion(version);
        if (version == HttpVersion.HTTP_2) {
            options.setHttp2ClearTextUpgrade(false);
        }
        HttpClient client = vertx.createHttpClient(options);
        try (ServerLog log = new ServerLog()) {
            CompletableFuture<Integer> status = new CompletableFuture<>();
            client.request(HttpMethod.POST, url.getPort(), url.getHost(), url.getPath())
                    .compose(request -> {
                        request.putHeader("content-type", "application/x-www-form-urlencoded");
                        return request.send(Buffer.buffer("name=juan"));
                    })
                    .onSuccess(response -> status.complete(response.statusCode()))
                    .onFailure(status::completeExceptionally);
            int code;
            try {
                code = status.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError("no response within 10s, server logged: " + log.records());
            }
            Assertions.assertEquals(200, code, () -> "server logged: " + log.records());
            Assertions.assertTrue(log.records().isEmpty(), () -> "server logged: " + log.records());
        } finally {
            client.close().toCompletionStage().toCompletableFuture().join();
        }
    }
}
