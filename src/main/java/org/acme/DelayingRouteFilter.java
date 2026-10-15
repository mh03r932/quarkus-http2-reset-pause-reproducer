package org.acme;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

/**
 * Stands in for any handler that runs before Quarkus REST and does not call {@code next()} straight away: a blocking
 * filter, authentication, or the dev mode hot replacement scan. The request stays paused for the whole delay, which
 * gives the client time to cancel it.
 */
@ApplicationScoped
public class DelayingRouteFilter {

    static final long DELAY_MS = 500;

    @Inject
    Vertx vertx;

    @RouteFilter(400)
    void delay(RoutingContext rc) {
        if ("/hello".equals(rc.normalizedPath())) {
            vertx.setTimer(DELAY_MS, t -> rc.next());
        } else {
            rc.next();
        }
    }
}
