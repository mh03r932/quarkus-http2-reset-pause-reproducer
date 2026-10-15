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
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;

/**
 * A client that cancels its request while the server still has it paused must not make the server log an error. This
 * is what a browser does over HTTP/2 when the user navigates away, or when a frontend aborts an in-flight XHR.
 */
@QuarkusTest
public class Http2StreamResetTest {

    @Inject
    Vertx vertx;

    @TestHTTPResource("/hello")
    URL url;

    @Test
    public void cancelledHttp2Request() throws Exception {
        List<String> logged = cancelDuringTheDelay(HttpVersion.HTTP_2);
        Assertions.assertTrue(logged.isEmpty(), () -> "the cancelled HTTP/2 request was logged: " + logged);
    }

    @Test
    public void cancelledHttp11Request() throws Exception {
        List<String> logged = cancelDuringTheDelay(HttpVersion.HTTP_1_1);
        Assertions.assertTrue(logged.isEmpty(), () -> "the cancelled HTTP/1.1 request was logged: " + logged);
    }

    @Test
    public void plainHttp2Request() throws Exception {
        HttpClient client = vertx.createHttpClient(options(HttpVersion.HTTP_2));
        try {
            CompletableFuture<String> body = new CompletableFuture<>();
            client.request(HttpMethod.GET, url.getPort(), url.getHost(), url.getPath())
                    .compose(HttpClientRequest::send)
                    .compose(response -> response.body().map(buffer -> buffer.toString()))
                    .onSuccess(body::complete)
                    .onFailure(body::completeExceptionally);
            Assertions.assertEquals("hello", body.get(15, TimeUnit.SECONDS));
        } finally {
            client.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    private List<String> cancelDuringTheDelay(HttpVersion version) throws Exception {
        HttpClient client = vertx.createHttpClient(options(version));
        try (ServerLog log = new ServerLog()) {
            CompletableFuture<Void> cancelled = new CompletableFuture<>();
            client.request(HttpMethod.GET, url.getPort(), url.getHost(), url.getPath())
                    .onSuccess(request -> {
                        request.send();
                        vertx.setTimer(100, t -> {
                            request.reset();
                            cancelled.complete(null);
                        });
                    })
                    .onFailure(cancelled::completeExceptionally);
            cancelled.get(15, TimeUnit.SECONDS);
            // the request only reaches Quarkus REST once the filter delay has elapsed
            Thread.sleep(DelayingRouteFilter.DELAY_MS + 1000);
            return log.records();
        } finally {
            client.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    private static HttpClientOptions options(HttpVersion version) {
        HttpClientOptions options = new HttpClientOptions().setProtocolVersion(version);
        if (version == HttpVersion.HTTP_2) {
            // cleartext HTTP/2 with prior knowledge, no upgrade handshake
            options.setHttp2ClearTextUpgrade(false);
        }
        return options;
    }
}
