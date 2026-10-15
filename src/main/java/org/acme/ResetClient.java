package org.acme;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;

/**
 * Opens a request to {@code /hello} and cancels it while the server still has it paused.
 *
 * <pre>
 * mvn exec:java -Dexec.mainClass=org.acme.ResetClient -Dexec.args="h2c"
 * mvn exec:java -Dexec.mainClass=org.acme.ResetClient -Dexec.args="http1"
 * </pre>
 */
public class ResetClient {

    public static void main(String[] args) throws Exception {
        String protocol = args.length > 0 ? args[0] : "h2c";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        long resetAfterMs = args.length > 2 ? Long.parseLong(args[2]) : 100;

        HttpClientOptions options = new HttpClientOptions();
        if ("h2c".equals(protocol)) {
            // cleartext HTTP/2 with prior knowledge, no upgrade handshake
            options.setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false);
        } else {
            options.setProtocolVersion(HttpVersion.HTTP_1_1);
        }

        Vertx vertx = Vertx.vertx();
        HttpClient client = vertx.createHttpClient(options);
        CountDownLatch done = new CountDownLatch(1);

        System.out.println("[client] " + protocol + ": GET /hello, cancelling after " + resetAfterMs + " ms");
        client.request(HttpMethod.GET, port, "localhost", "/hello")
                .onSuccess(request -> {
                    request.send()
                            .onSuccess(response -> System.out.println("[client] response " + response.statusCode()))
                            .onFailure(t -> System.out.println("[client] request failed: " + t));
                    vertx.setTimer(resetAfterMs, t -> {
                        System.out.println("[client] cancelling the request");
                        request.reset();
                        vertx.setTimer(1500, x -> done.countDown());
                    });
                })
                .onFailure(t -> {
                    System.out.println("[client] could not connect: " + t);
                    done.countDown();
                });

        done.await(10, TimeUnit.SECONDS);
        client.close().toCompletionStage().toCompletableFuture().join();
        vertx.close().toCompletionStage().toCompletableFuture().join();
        System.out.println("[client] done, look at the server log");
    }
}
