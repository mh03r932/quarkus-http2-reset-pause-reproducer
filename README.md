# `IllegalStateException: Request has already been read` over HTTP/2

Reproducer for the unguarded `request.pause()` in
`org.jboss.resteasy.reactive.server.vertx.VertxResteasyReactiveRequestContext`.

Upstream issue: [quarkusio/quarkus#34393](https://github.com/quarkusio/quarkus/issues/34393).
Proposed fix: [quarkusio/quarkus#56595](https://github.com/quarkusio/quarkus/pull/56595).

Over HTTP/2, `pause()` on the Vert.x server request calls `checkEnded()` and throws once the request has ended, while
the HTTP/1.1 implementation ignores it. The constructor of `VertxResteasyReactiveRequestContext` pauses the request
unconditionally, so anything that makes a request end before Quarkus REST takes over turns into a 500 and an `ERROR`
in the log.

## The three scenarios

**`Http2StreamResetTest` — a cancelled request.** `DelayingRouteFilter` defers `next()` by 500 ms for `/hello`,
standing in for any handler that runs before Quarkus REST without handing over straight away (a blocking filter,
authentication, or the dev mode hot replacement scan). The client cancels after 100 ms, which sends `RST_STREAM`.
`Http2ServerRequest.handleReset()` sets `ended = true` right away, even though the request is paused, and the
`pause()` in the constructor then throws. This is what a browser does when it aborts an in-flight request, so in a dev
mode app over TLS it shows up as unexplained `ERROR`s with an error id.

**`RouteHandoverTest` — a reactive route handing over**, the scenario of #34393. `ForwardingRoute` sits on the path of
`EchoResource` and calls `next()`. Its body handler has already read the request, so it has ended by the time Quarkus
REST creates its context. The client gets a 500.

**`FormParseTest` — a route that consumes the body itself.** `FormRoute` reads `/form` with its own
`handler()`/`endHandler()`, so the request has ended *and* nothing is buffered in the routing context, and
`FormResource` parses form data on the non-blocking path. This is the state that the `setReadListener()` branch of
#56595 is about: `FormEncodedDataDefinition.parse()` registers a read listener on a request that has nothing left to
deliver.

## Running it

```sh
mvn clean test                                      # 3.40.1, the default
mvn clean test -Dquarkus.platform.version=3.33.4    # the LTS
mvn clean test -Dquarkus.platform.version=4.0.0.Beta1
```

Use `clean` when switching versions: the test sources compile against the Vert.x version of the platform.

The tests assert that the request is answered and that the server logs nothing at `WARNING` or above, and print what
it did log when they fail. HTTP/2 is cleartext with prior knowledge, so no certificate is needed.

The two `*Diagnostics` classes are not part of that run. They print evidence instead of asserting, they need the
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

The 2026-10-15 `main` snapshot behaves like 4.0.0.Beta1: the same three fail, and the PR's own file makes all seven
pass. See below.

Two details behind the table:

- The cancelled request stops failing on the Vert.x 5 line because `HttpServerRequestImpl.handleReset()` no longer
  sets `ended` (checked in 5.1.8 and 5.1.10). The other two scenarios still fail there.
- The HTTP/1.1 column is not a missing check but timing and bookkeeping. In Vert.x 4.5.34
  `Http1xServerRequest.isEnded()` is `ended && (pending == null || (!pending.isPaused() && pending.isEmpty()))`, so
  the `pause()` in `setReadListener()` makes `isEnded()` go back to `false`, the following `handler()` does not
  complain, and the read simply never completes. In Vert.x 5 `Http1ServerRequest.isEnded()` is plain `ended`, so the
  same path throws from `handler()` instead of hanging.

### Against the proposed fix

`verify-fix.sh` applies the four guards of #56595 to the released `resteasy-reactive-vertx` artifact and installs the
result as `<version>-guarded`, which avoids building Quarkus from source:

```sh
./verify-fix.sh 3.40.1
mvn clean test -Dquarkus.platform.version=3.40.1 -Dresteasy-reactive-vertx.version=3.40.1-guarded
```

All seven tests pass, on 3.33.4 as well. The cancelled request reaches the resource method and nothing is logged: the
write to the reset stream fails quietly. The form scenario answers with an empty form, which is all that is left of a
request someone else has consumed.

Replacing the early return of #56595 with a symmetric `if (!request.isEnded())` around the `pause()` in
`setReadListener()` is not enough: both protocols then throw from the `request.handler()` call that follows, since
that checks `ended` too.

### Against main

Releases are convenient, but the PR targets `main`, so this is the run that counts. Install the daily snapshot the
way CONTRIBUTING.md describes, then point the reproducer at it:

```sh
curl -sL https://raw.githubusercontent.com/quarkusio/quarkus-ecosystem-ci/main/setup-quarkus | bash
mvn -nsu clean test -Dquarkus.platform.group-id=io.quarkus -Dquarkus.platform.version=999-SNAPSHOT
```

On the 2026-10-15 snapshot (Quarkus 999-SNAPSHOT, Vert.x 5.1.10) three of the seven fail: the form scenario over
both protocols and the handover over HTTP/2. The cancelled request passes, and the constructor sees `isEnded=false`
there where 3.40.1 sees `true`, which is the Vert.x 5 reset behaviour measured rather than inferred.

To run the PR's own code against that snapshot, take the file from the PR head, compile it against the snapshot
classpath and install it under a version of its own, the same trick `verify-fix.sh` uses for releases:

```sh
git fetch origin pull/56595/head --depth=1
git show FETCH_HEAD:independent-projects/resteasy-reactive/server/vertx/src/main/java/org/jboss/resteasy/reactive/server/vertx/VertxResteasyReactiveRequestContext.java
```

With that file compiled in, all seven pass.

### Which thread each call site runs on

The review of #56595 asks whether the `isEnded()` checks are safe, since the check and the `pause()`/`resume()` are
two separate operations under the Vert.x connection lock. That is only safe on the event loop that delivers the
request's own events. `CallSiteThreadDiagnostics` prints where each site runs, for seven scenarios. The table below
came out the same on 3.40.1 and on the 2026-10-15 `main` snapshot with the file from the PR head compiled in:

```sh
./verify-fix.sh 3.40.1 trace
mvn clean test -Dtest=CallSiteThreadDiagnostics -Dresteasy-reactive-vertx.version=3.40.1-trace
```

| call site | thread |
| --- | --- |
| constructor | the connection's event loop, in all seven scenarios |
| `pauseRequestInput()` | the connection's event loop |
| `setReadListener()` | the connection's event loop |
| `resumeRequestInput()` | the event loop, **except** from the non-blocking multipart parser, where it runs on `executor-thread-N` |

The exception comes from `MultiPartParserDefinition.NonBlockingParseTask.data()`, which pauses on the event loop and
then resumes from inside `executor.execute(...)`. A 1 MB non-blocking multipart upload does that 129 times on 3.40.1
and 130 times on `main`.

`ResetDuringMultipartDiagnostics` cancels 25 such uploads over HTTP/2 and counts what the guard catches:

```sh
mvn clean test -Dtest=ResetDuringMultipartDiagnostics -Dresteasy-reactive-vertx.version=3.40.1-trace
```

| | worker-thread `resumeRequestInput()` calls | of those on a request that had already ended |
| --- | --- | --- |
| `main` snapshot, Vert.x 5.1.10 | 976 | 0 |
| 3.40.1, Vert.x 4.5.34 | 944 | 26 |

On 3.x each of those 26 would be an `IllegalStateException` without the guard. On `main` a reset no longer ends the
request, so that path never saw an ended request. No exception slipped through the gap between the check and the
`resume()` in any run, so the remaining race is real but I could not make it fire. Dispatching that one resume back
onto the request's context closes it, and the 1 MB upload still completes:

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

The server logs the exception for the HTTP/2 run and nothing for the HTTP/1.1 run. Dev mode is not needed; it only
widens the window, because `VertxHttpHotReplacementSetup` runs the changed-sources scan in `executeBlocking` before
calling `next()`.

## Environment used

- Darwin 24.6.0, arm64
- `openjdk version "21.0.3" 2024-04-16 LTS` (Temurin)
- Apache Maven 3.9.8
