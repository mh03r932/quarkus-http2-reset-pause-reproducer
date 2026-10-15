package org.acme;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.Route.HttpMethod;
import io.vertx.ext.web.RoutingContext;

/**
 * A reactive route on the same path as a REST resource, which hands the request over. This is the scenario of
 * quarkusio/quarkus#34393: the body handler of the route reads the request before Quarkus REST takes over.
 */
@ApplicationScoped
public class ForwardingRoute {

    @Route(methods = HttpMethod.POST, path = "/echo")
    void forward(RoutingContext rc) {
        rc.next();
    }
}
