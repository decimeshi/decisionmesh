package com.decisionmesh.contracts.security.filter;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CorsPreflightFilter {

    private static final Logger LOG = Logger.getLogger(CorsPreflightFilter.class);

    @RouteFilter(500)
    void handleCors(RoutingContext rc) {
        String origin = rc.request().getHeader("Origin");

        if (origin != null) {
            // addHeadersEndHandler fires just before bytes are written
            // — catches ALL responses including 401s from security layer
            rc.addHeadersEndHandler(v ->
                    rc.response()
                            .putHeader("Access-Control-Allow-Origin", origin)
                            .putHeader("Access-Control-Allow-Methods",
                                    "GET,POST,PUT,DELETE,PATCH,OPTIONS")
                            .putHeader("Access-Control-Allow-Headers",
                                    "Accept,Authorization,Content-Type,X-Requested-With,Idempotency-Key")
                            .putHeader("Access-Control-Allow-Credentials", "true")
                            .putHeader("Access-Control-Max-Age", "86400")
            );
        }

        // Short-circuit OPTIONS preflight
        if ("OPTIONS".equalsIgnoreCase(rc.request().method().name())) {
            LOG.debugf(" OPTIONS preflight - origin: %s", origin);
            rc.response().setStatusCode(200).end();
            return;
        }

        rc.next();
    }
}