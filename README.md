# `IllegalStateException: Request has already been read` over HTTP/2

Reproducer for the unguarded `request.pause()` in
`org.jboss.resteasy.reactive.server.vertx.VertxResteasyReactiveRequestContext`.

Upstream issue: [quarkusio/quarkus#34393](https://github.com/quarkusio/quarkus/issues/34393).
Proposed fix: [quarkusio/quarkus#56595](https://github.com/quarkusio/quarkus/pull/56595).

Over HTTP/2, `pause()` on the Vert.x server request calls `checkEnded()` and throws once the request has ended. The
HTTP/1.1 implementation ignores it. The constructor of `VertxResteasyReactiveRequestContext` pauses the request
unconditionally, so anything that ends a request before Quarkus REST takes over gives a 500 and an `ERROR` in the
log.

## The three scenarios

**A cancelled request, `Http2StreamResetTest`.** `DelayingRouteFilter` defers `next()` by 500 ms for `/hello`. It
stands for any handler that does not hand over right away, for example a blocking filter, authentication, or the dev
mode hot replacement scan. The client cancels after 100 ms and sends `RST_STREAM`.
`Http2ServerRequest.handleReset()` sets `ended = true` right away, although the request is paused, and the `pause()`
in the constructor then throws. A browser does the same when it cancels a request, which is how we ran into this in
dev mode over TLS.

**A reactive route that hands over, `RouteHandoverTest`.** This is the scenario of #34393. `ForwardingRoute` sits on
the path of `EchoResource` and calls `next()`. Its body handler has read the request already, so the request has
ended when Quarkus REST creates its context. The client gets a 500.

**A route that consumes the body itself, `FormParseTest`.** `FormRoute` reads `/form` with its own
`handler()`/`endHandler()`, so the request has ended and nothing is buffered in the routing context, and
`FormResource` parses form data on the non-blocking path. This is the state behind the `setReadListener()` branch of
#56595: `FormEncodedDataDefinition.parse()` registers a read listener on a request that has nothing left to deliver.

## Running it

```sh
mvn clean test                                      # 3.40.1, the default
mvn clean test -Dquarkus.platform.version=3.33.4    # the LTS
mvn clean test -Dquarkus.platform.version=4.0.0.Beta1
```

Use `clean` when you switch versions, because the test sources compile against the Vert.x version of the platform.

The tests check that the request is answered and that the server logs nothing at `WARNING` or above. When they fail
they print what it did log. HTTP/2 is cleartext with prior knowledge, so no certificate is needed.

The two `*Diagnostics` classes are not part of that run. They print numbers instead of asserting, they need the
instrumented build from `./verify-fix.sh <version> trace`, and they are described further down. `/blocking` and
`/multipart` exist only for them.

### What it does

Seven tests, of which three fail on the 3.x line:

| scenario | 3.33.4 / 3.40.1 (Vert.x 4.5.34) | 4.0.0.Beta1 (Vert.x 5.1.8) | 3.x with the #56595 guards |
| --- | --- | --- | --- |
| cancelled request, HTTP/2 | `ERROR`, no response | passes | passes |
| cancelled request, HTTP/1.1 | passes | passes | passes |
| route handover, HTTP/2 | 500 | 500 | passes |
| route handover, HTTP/1.1 | passes | passes | passes |
| route consumed the body, HTTP/2 | 500 | 500 | passes, empty form |
| route consumed the body, HTTP/1.1 | no response at all, nothing logged | 500 | passes, empty form |
| plain HTTP/2 request | passes | passes | passes |

The 2026-10-15 `main` snapshot behaves like 4.0.0.Beta1. The same three fail, and the file from the PR makes all
seven pass. See below.

Two details behind the table:

- The cancelled request stops failing on the Vert.x 5 line, because `HttpServerRequestImpl.handleReset()` no longer
  sets `ended`. I checked that in 5.1.8 and 5.1.10. The other two scenarios still fail there.
- HTTP/1.1 behaves differently because of timing and bookkeeping, not because a check is missing. In Vert.x 4.5.34,
  `Http1xServerRequest.isEnded()` is `ended && (pending == null || (!pending.isPaused() && pending.isEmpty()))`. The
  `pause()` in `setReadListener()` therefore takes `isEnded()` back to `false`, the `handler()` call that follows
  does not complain, and the read never completes. In Vert.x 5, `Http1ServerRequest.isEnded()` is plain `ended`, so
  the same path throws from `handler()` instead of hanging.

### Against the proposed fix

`verify-fix.sh` applies the four guards of #56595 to the released `resteasy-reactive-vertx` artifact and installs the
result as `<version>-guarded`. That way the fix can be checked without building Quarkus from source:

```sh
./verify-fix.sh 3.40.1
mvn clean test -Dquarkus.platform.version=3.40.1 -Dresteasy-reactive-vertx.version=3.40.1-guarded
```

All seven tests pass, on 3.33.4 as well. The cancelled request reaches the resource method and nothing is logged,
because the write to the reset stream fails quietly. The form scenario answers with an empty form, since the body is
gone.

A symmetric `if (!request.isEnded())` around the `pause()` in `setReadListener()` instead of the early return is not
enough. Both protocols then throw from the `request.handler()` call that follows, which checks `ended` too.

### Against main

The PR targets `main`, so this is the run that counts. Install the daily snapshot the way CONTRIBUTING.md describes
and point the reproducer at it:

```sh
curl -sL https://raw.githubusercontent.com/quarkusio/quarkus-ecosystem-ci/main/setup-quarkus | bash
mvn -nsu clean test -Dquarkus.platform.group-id=io.quarkus -Dquarkus.platform.version=999-SNAPSHOT
```

On the 2026-10-15 snapshot (Quarkus 999-SNAPSHOT, Vert.x 5.1.10) three of the seven fail: the form scenario over
both protocols and the handover over HTTP/2. The cancelled request passes, and the constructor sees `isEnded=false`
where 3.40.1 gives `true`. So the Vert.x 5 reset behaviour is measured here, not inferred from the sources.

To run the code of the PR against that snapshot, take its file, compile it against the snapshot classpath and
install it under a version of its own, the same way `verify-fix.sh` does it for releases:

```sh
git fetch origin pull/56595/head --depth=1
git show FETCH_HEAD:independent-projects/resteasy-reactive/server/vertx/src/main/java/org/jboss/resteasy/reactive/server/vertx/VertxResteasyReactiveRequestContext.java
```

With that file compiled in, all seven pass.

### Which thread each call site runs on

The review of #56595 asks whether the `isEnded()` checks are safe. The check and the `pause()`/`resume()` are two
operations under the Vert.x connection lock, so they are only atomic on the event loop that delivers the events of
that request. `CallSiteThreadDiagnostics` prints where each site runs, for seven scenarios:

```sh
./verify-fix.sh 3.40.1 trace
mvn clean test -Dtest=CallSiteThreadDiagnostics -Dresteasy-reactive-vertx.version=3.40.1-trace
```

The result is the same on 3.40.1 and on the `main` snapshot with the file from the PR compiled in:

| call site | thread |
| --- | --- |
| constructor | the connection's event loop, in all seven scenarios |
| `pauseRequestInput()` | the connection's event loop |
| `setReadListener()` | the connection's event loop |
| `resumeRequestInput()` | the event loop, except from the non-blocking multipart parser, where it is `executor-thread-N` |

That one call comes from `MultiPartParserDefinition.NonBlockingParseTask.data()`, which pauses on the event loop and
then resumes inside `executor.execute(...)`. A 1 MB non-blocking multipart upload goes through it 129 times on
3.40.1 and 130 times on `main`.

`ResetDuringMultipartDiagnostics` cancels 25 such uploads over HTTP/2 and counts what the guard catches:

```sh
mvn clean test -Dtest=ResetDuringMultipartDiagnostics -Dresteasy-reactive-vertx.version=3.40.1-trace
```

Three runs on each line, with the application counting the resets the server received:

| | resets seen, of 25 | worker-thread `resumeRequestInput()` calls | of those already ended |
| --- | --- | --- | --- |
| `main` snapshot, Vert.x 5.1.10 | 19, 20, 20 | 1005, 1082, 859 | 0, 0, 0 |
| 3.40.1, Vert.x 4.5.34 | 20, 17, 19 | 905, 955, 918 | 23, 28, 22 |

On 3.x each of those would be an `IllegalStateException` without the guard. On `main` I never saw one.

`ResetWhilePausedDiagnostics` shows the reason, and needs no patched class:

```sh
mvn clean test -Dtest=ResetWhilePausedDiagnostics -Dobserve.resets=true
```

It cancels a paused request five times and `ResetObserver` prints what the server makes of it. On 3.40.1 the request
exception handler gets a `StreamResetException` and `isEnded()` is already true, and still true 400 ms later. On
`main` the handler gets a `StreamResetException` and an `HttpClosedException`, and `isEnded()` is false in all five
rounds. It turns true only later, when something downstream resumes the request and the end event that was queued
behind the pause gets delivered. The constructor has paused long before that.

In no run did an exception slip through the gap between the check and the `resume()`, so that race is real but I
could not make it happen. Putting that one resume back onto the request context closes it, and the 1 MB upload still
works:

```java
if (((ConnectionBase) request.connection()).channel().eventLoop().inEventLoop()) {
    resumeIfNotEnded();
} else {
    contextExecutor.execute(new Runnable() {
        @Override
        public void run() {
            resumeIfNotEnded();
        }
    });
}
```

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

The server logs the exception for the HTTP/2 run and nothing for the HTTP/1.1 run. Dev mode is not needed. It only
widens the window, because `VertxHttpHotReplacementSetup` runs the changed-sources scan in `executeBlocking` before
it calls `next()`.

## Environment used

- Darwin 24.6.0, arm64
- `openjdk version "21.0.3" 2024-04-16 LTS` (Temurin)
- Apache Maven 3.9.8
