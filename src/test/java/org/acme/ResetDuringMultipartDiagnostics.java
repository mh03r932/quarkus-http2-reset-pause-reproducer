package org.acme;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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

/** Cancels an HTTP/2 multipart upload while the worker thread is parsing it. */
@QuarkusTest
public class ResetDuringMultipartDiagnostics {

    private static final int ROUNDS = 25;
    private static final String BOUNDARY = "----b0undary";

    @Inject
    Vertx vertx;

    @TestHTTPResource("/multipart")
    URL url;

    @Test
    public void resetWhileParsing() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        Logger root = LogManager.getLogManager().getLogger("");
        Handler collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue() && record.getThrown() != null
                        && String.valueOf(record.getThrown().getMessage()).contains("already been read")) {
                    seen.add(String.valueOf(record.getThrown().getStackTrace()[2]));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        root.addHandler(collector);
        Buffer body = Buffer.buffer("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n"
                + "x".repeat(1024 * 1024) + "\r\n--" + BOUNDARY + "--\r\n");
        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false));
        try {
            for (int round = 0; round < ROUNDS; round++) {
                long resetAfter = 5 + (round % 20);
                CompletableFuture<Void> done = new CompletableFuture<>();
                client.request(HttpMethod.POST, url.getPort(), url.getHost(), url.getPath())
                        .onSuccess(request -> {
                            request.putHeader("content-type", "multipart/form-data; boundary=" + BOUNDARY);
                            request.send(body);
                            vertx.setTimer(resetAfter, t -> {
                                request.reset();
                                done.complete(null);
                            });
                        })
                        .onFailure(done::completeExceptionally);
                done.get(15, TimeUnit.SECONDS);
                Thread.sleep(120);
            }
            Thread.sleep(1000);
        } finally {
            root.removeHandler(collector);
            client.close().toCompletionStage().toCompletableFuture().join();
        }
        System.out.println("[race] " + ROUNDS + " cancelled uploads, " + seen.size()
                + " IllegalStateException(s) logged");
        for (String where : seen) {
            System.out.println("[race]   thrown under " + where);
        }
    }
}
