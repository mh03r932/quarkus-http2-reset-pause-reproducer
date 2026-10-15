package org.acme;

import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;

/** Cancels a paused request five times and lets the application report what the server makes of it. */
@QuarkusTest
public class ResetWhilePausedDiagnostics {

    @Inject
    Vertx vertx;

    @TestHTTPResource("/hello")
    URL url;

    @Test
    public void http2() throws Exception {
        for (int i = 0; i < 5; i++) {
            System.out.println("----- round " + i);
            cancel();
        }
    }

    private void cancel() throws Exception {
        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false));
        try {
            CompletableFuture<Void> done = new CompletableFuture<>();
            client.request(HttpMethod.GET, url.getPort(), url.getHost(), url.getPath())
                    .onSuccess(request -> {
                        request.send().onFailure(t -> System.out.println("[client] " + t.getClass().getSimpleName()));
                        vertx.setTimer(100, t -> {
                            request.reset();
                            done.complete(null);
                        });
                    })
                    .onFailure(done::completeExceptionally);
            done.get(15, TimeUnit.SECONDS);
            Thread.sleep(DelayingRouteFilter.DELAY_MS + 500);
        } finally {
            client.close().toCompletionStage().toCompletableFuture().join();
        }
    }
}
