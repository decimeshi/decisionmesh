package com.decisionmesh.llm.config;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;

/**
 * CDI producer for jakarta.ws.rs.client.Client.
 *
 * Quarkus does not auto-produce a JAX-RS Client bean — AnthropicAdapter
 * injects one via @Inject Client httpClient, so we must produce it here.
 *
 * The client is ApplicationScoped (one instance shared across all adapters)
 * and disposed cleanly on shutdown via @Disposes.
 *
 * Place this file in:
 *   decisionmesh-llm/src/main/java/com/decisionmesh/llm/config/JaxRsClientProducer.java
 */
@ApplicationScoped
public class JaxRsClientProducer {

    @Produces
    @ApplicationScoped
    public Client produceClient() {
        return ClientBuilder.newBuilder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    public void disposeClient(@Disposes Client client) {
        client.close();
    }
}