# `IllegalStateException: Request has already been read` over HTTP/2

Reproducer for the unguarded `request.pause()` in
`org.jboss.resteasy.reactive.server.vertx.VertxResteasyReactiveRequestContext`.

Upstream issue: [quarkusio/quarkus#34393](https://github.com/quarkusio/quarkus/issues/34393).
Proposed fix: [quarkusio/quarkus#56595](https://github.com/quarkusio/quarkus/pull/56595).

Over HTTP/2, `Http2ServerRequest.pause()` calls `checkEnded()` and throws once the request has ended, while the
HTTP/1.1 implementation silently ignores it. The constructor of `VertxResteasyReactiveRequestContext` pauses the
request unconditionally, so anything that makes a request end before Quarkus REST takes over turns into a 500 and an
`ERROR` in the log.

## The two scenarios

`Http2StreamResetTest` — **a cancelled request**. `DelayingRouteFilter` defers `next()` by 500 ms for `/hello`,
standing in for any handler that runs before Quarkus REST without handing over straight away (a blocking filter,
authentication, or the dev mode hot replacement scan). The client cancels after 100 ms, which sends `RST_STREAM`.
`Http2ServerRequest.handleReset()` sets `ended = true` right away, even though the request is paused, and the
`pause()` in the constructor then throws. This is what a browser does when it aborts an in-flight request, so in a
dev mode app over TLS it shows up as unexplained `ERROR`s with an error id.

`RouteHandoverTest` — **a reactive route handing over**, the scenario of #34393. `ForwardingRoute` sits on the path
of `EchoResource` and calls `next()`. Its body handler has already read the request, so it has ended by the time
Quarkus REST creates its context. The client gets a 500.

In both cases HTTP/1.1 is fine; only HTTP/2 fails.

## Running it

```sh
mvn clean test                                      # 3.40.1, the default
mvn clean test -Dquarkus.platform.version=3.33.4    # the LTS
mvn clean test -Dquarkus.platform.version=4.0.0.Beta1
```

Use `clean` when switching versions: the test sources compile against the Vert.x version of the platform.

The tests assert that the server logs nothing at `WARNING` or above, and print what it did log when they fail. HTTP/2
is cleartext with prior knowledge, so no certificate is needed.

### What it does

| Quarkus | Vert.x | cancelled HTTP/2 | cancelled HTTP/1.1 | handover HTTP/2 | handover HTTP/1.1 |
| --- | --- | --- | --- | --- | --- |
| 3.33.4 (LTS) | 4.5.34 | fails | ok | fails | ok |
| 3.40.1 | 4.5.34 | fails | ok | fails | ok |
| 4.0.0.Beta1 | 5.1.8 | ok | ok | fails | ok |
| 3.33.4 / 3.40.1 with the #56595 guards | 4.5.34 | ok | ok | ok | ok |

The cancelled request stops failing on the Vert.x 5 line because `HttpServerRequestImpl.handleReset()` no longer sets
`ended`; the handover scenario still fails there.

### Against the proposed fix

`verify-fix.sh` applies the four guards of #56595 to the released `resteasy-reactive-vertx` artifact and installs the
result as `<version>-guarded`, which avoids building Quarkus from source:

```sh
./verify-fix.sh 3.40.1
mvn clean test -Dquarkus.platform.version=3.40.1 -Dresteasy-reactive-vertx.version=3.40.1-guarded
```

All five tests pass, the resource method is reached, and nothing is logged: the write to the reset stream fails
quietly.

### In prod mode

```sh
mvn clean package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```

and in another shell, either

```sh
curl --http2-prior-knowledge --max-time 0.2 http://localhost:8080/hello
```

or, for an explicit `RST_STREAM` instead of a client timeout,

```sh
mvn exec:java -Dexec.mainClass=org.acme.ResetClient -Dexec.args="h2c"
mvn exec:java -Dexec.mainClass=org.acme.ResetClient -Dexec.args="http1"
```

The server logs the exception for the HTTP/2 run and nothing for the HTTP/1.1 run. Dev mode is not needed; it only
widens the window, because `VertxHttpHotReplacementSetup` runs the changed-sources scan in `executeBlocking` before
calling `next()`.

## Environment used

- Darwin 24.6.0, arm64
- `openjdk version "21.0.3" 2024-04-16 LTS` (Temurin)
- Apache Maven 3.9.8
