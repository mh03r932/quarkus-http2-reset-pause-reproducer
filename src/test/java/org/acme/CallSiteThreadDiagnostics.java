package org.acme;

import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;

/** Prints which thread each pause/resume call site runs on, per scenario. */
@QuarkusTest
public class CallSiteThreadDiagnostics {

    @Inject
    Vertx vertx;

    @TestHTTPResource("/")
    URL root;

    @Test
    public void allScenarios() throws Exception {
        banner("plain GET /hello, HTTP/2");
        get("/hello", HttpVersion.HTTP_2);

        banner("cancelled GET /hello, HTTP/2");
        cancel("/hello", HttpVersion.HTTP_2);

        banner("POST /echo through the handover route, HTTP/2");
        post("/echo", HttpVersion.HTTP_2, "juan", "text/plain");

        banner("POST /form, route consumed the body, HTTP/1.1");
        post("/form", HttpVersion.HTTP_1_1, "name=juan", "application/x-www-form-urlencoded");

        banner("POST /blocking, 4k body with input-buffer-size=100, HTTP/1.1");
        post("/blocking", HttpVersion.HTTP_1_1, "x".repeat(4096), "text/plain");

        banner("POST /multipart, non-blocking, HTTP/1.1");
        String boundary = "----b0undary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\njuan\r\n--"
                + boundary + "--\r\n";
        post("/multipart", HttpVersion.HTTP_1_1, body, "multipart/form-data; boundary=" + boundary);

        banner("POST /multipart, 1 MB field, many chunks, HTTP/1.1");
        String bigBody = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n"
                + "x".repeat(1024 * 1024) + "\r\n--" + boundary + "--\r\n";
        post("/multipart", HttpVersion.HTTP_1_1, bigBody, "multipart/form-data; boundary=" + boundary);

        banner("done");
    }

    private void banner(String what) throws Exception {
        Thread.sleep(600);
        System.out.println("\n===== " + what + " =====");
    }

    private HttpClient client(HttpVersion version) {
        HttpClientOptions options = new HttpClientOptions().setProtocolVersion(version);
        if (version == HttpVersion.HTTP_2) {
            options.setHttp2ClearTextUpgrade(false);
        }
        return vertx.createHttpClient(options);
    }

    private void get(String path, HttpVersion version) throws Exception {
        HttpClient client = client(version);
        try {
            CompletableFuture<String> result = new CompletableFuture<>();
            client.request(HttpMethod.GET, root.getPort(), root.getHost(), path)
                    .compose(request -> request.send())
                    .compose(response -> response.body().map(b -> response.statusCode() + " " + b))
                    .onSuccess(result::complete)
                    .onFailure(result::completeExceptionally);
            System.out.println("[response] " + result.get(15, TimeUnit.SECONDS));
        } finally {
            client.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    private void post(String path, HttpVersion version, String body, String contentType) throws Exception {
        HttpClient client = client(version);
        try {
            CompletableFuture<String> result = new CompletableFuture<>();
            client.request(HttpMethod.POST, root.getPort(), root.getHost(), path)
                    .compose(request -> {
                        request.putHeader("content-type", contentType);
                        return request.send(Buffer.buffer(body));
                    })
                    .compose(response -> response.body().map(b -> response.statusCode() + " " + b))
                    .onSuccess(result::complete)
                    .onFailure(result::completeExceptionally);
            System.out.println("[response] " + result.get(15, TimeUnit.SECONDS));
        } catch (java.util.concurrent.TimeoutException e) {
            System.out.println("[response] no response within 15s");
        } finally {
            client.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    private void cancel(String path, HttpVersion version) throws Exception {
        HttpClient client = client(version);
        try {
            CompletableFuture<Void> done = new CompletableFuture<>();
            client.request(HttpMethod.GET, root.getPort(), root.getHost(), path)
                    .onSuccess(request -> {
                        request.send();
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
