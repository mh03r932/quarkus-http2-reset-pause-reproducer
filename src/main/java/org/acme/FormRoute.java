package org.acme;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * Reads the body itself, so the request ends without anything being buffered in the routing context. This is the
 * state that the setReadListener() early return of quarkusio/quarkus#56595 is about.
 */
@ApplicationScoped
public class FormRoute {

    @RouteFilter(500)
    void readBody(RoutingContext rc) {
        if ("/form".equals(rc.normalizedPath())) {
            Buffer collected = Buffer.buffer();
            rc.request().handler(collected::appendBuffer).endHandler(v -> rc.next());
        } else {
            rc.next();
        }
    }
}
