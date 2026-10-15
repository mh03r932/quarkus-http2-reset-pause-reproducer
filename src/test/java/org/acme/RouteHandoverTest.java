package org.acme;

import java.net.URL;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
 * The second way into the same exception, reported in quarkusio/quarkus#34393: a reactive route on the path of a REST
 * resource hands the request over with {@code next()} after its body handler has read the request.
 */
@QuarkusTest
public class RouteHandoverTest {

    @Inject
    Vertx vertx;

    @TestHTTPResource("/echo")
    URL url;

    @Test
    public void http2() throws Exception {
        postThroughTheRoute(HttpVersion.HTTP_2);
    }

    @Test
    public void http11() throws Exception {
        postThroughTheRoute(HttpVersion.HTTP_1_1);
    }

    private void postThroughTheRoute(HttpVersion version) throws Exception {
        HttpClientOptions options = new HttpClientOptions().setProtocolVersion(version);
        if (version == HttpVersion.HTTP_2) {
            options.setHttp2ClearTextUpgrade(false);
        }
        HttpClient client = vertx.createHttpClient(options);
        try (ServerLog log = new ServerLog()) {
            CompletableFuture<Integer> status = new CompletableFuture<>();
            client.request(HttpMethod.POST, url.getPort(), url.getHost(), url.getPath())
                    .compose(request -> request.send(Buffer.buffer("juan")))
                    .onSuccess(response -> status.complete(response.statusCode()))
                    .onFailure(status::completeExceptionally);
            int code = status.get(15, TimeUnit.SECONDS);
            Assertions.assertEquals(200, code, () -> "server logged: " + log.records());
            Assertions.assertTrue(log.records().isEmpty(), () -> "server logged: " + log.records());
        } finally {
            client.close().toCompletionStage().toCompletableFuture().join();
        }
    }
}
