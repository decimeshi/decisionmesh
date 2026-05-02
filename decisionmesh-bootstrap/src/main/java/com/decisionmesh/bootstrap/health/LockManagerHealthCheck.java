package com.decisionmesh.bootstrap.health;

import com.decisionmesh.application.lock.LockManager;
import io.quarkus.logging.Log;
import io.smallrye.health.api.AsyncHealthCheck;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import java.time.Duration;
import java.util.UUID;

@Liveness
@ApplicationScoped
public class LockManagerHealthCheck implements AsyncHealthCheck {

    @Inject
    LockManager lockManager;

    @Override
    public Uni<HealthCheckResponse> call() {
        String testKey = "healthcheck:" + UUID.randomUUID();

        return lockManager.acquire(testKey, Duration.ofSeconds(5))
                .flatMap(token -> lockManager.release(token)  // release immediately
                        .onTermination().invoke(() ->         // always release even if release() fails
                                lockManager.forceRelease(testKey).subscribe().with(
                                        ignored -> {},
                                        err -> Log.warnf("Health check lock cleanup failed: %s", err.getMessage())
                                )
                        )
                )
                .replaceWith(HealthCheckResponse.up("lock-manager"))
                .onFailure().recoverWithItem(e -> {
                    Log.errorf(e, "Lock manager health check failed");
                    return HealthCheckResponse.down("lock-manager");
                });
    }
}