package org.acme;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

/**
 * Reports what the server makes of a cancelled request, without any patched Quarkus class: whether the exception
 * reaches the request, and whether the request counts as ended, right away and again later. Off unless
 * {@code -Dobserve.resets=true} is set, so it stays out of the way of the tests.
 */
@ApplicationScoped
public class ResetObserver {

    @Inject
    Vertx vertx;

    @RouteFilter(600)
    void observe(RoutingContext rc) {
        if (!Boolean.getBoolean("observe.resets")) {
            rc.next();
            return;
        }
        String path = rc.normalizedPath();
        rc.request().exceptionHandler(t -> {
            System.out.println("[reset-observed] " + path + " " + t.getClass().getSimpleName()
                    + " isEnded=" + rc.request().isEnded());
            vertx.setTimer(400, timer -> System.out.println(
                    "[reset-observed] " + path + " 400 ms later isEnded=" + rc.request().isEnded()));
        });
        rc.next();
    }
}
