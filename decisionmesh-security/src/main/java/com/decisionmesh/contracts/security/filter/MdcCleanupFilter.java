package com.decisionmesh.contracts.security.filter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.MDC;

import java.io.IOException;

/**
 * Response filter to clean up MDC (Mapped Diagnostic Context) after request completes.
 */
@Provider
public class MdcCleanupFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        // Clear MDC to prevent leaking context between requests
        MDC.clear();
    }
}