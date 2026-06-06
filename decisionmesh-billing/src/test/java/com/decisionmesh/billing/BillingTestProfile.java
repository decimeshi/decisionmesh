package com.decisionmesh.billing;


import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class BillingTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.hibernate-orm.active",      "false",
                "quarkus.hibernate-reactive.active", "false"
        );
    }

}