package com.decisionmesh.contracts.security.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.Collections;
import java.util.UUID;

@ApplicationScoped
public class KeycloakProvisioningService {

    @Inject
    Keycloak keycloak;

    @ConfigProperty(name = "keycloak.realm")
    String realm;

    public void assignTenantAdminRole(String externalUserId, UUID tenantId) {

        var users = keycloak.realm(realm).users();
        var userList = users.search(externalUserId, true);

        if (userList.isEmpty()) {
            return;
        }

        UserRepresentation user = userList.get(0);

        //  Set tenantId attribute
        user.singleAttribute("tenantId", tenantId.toString());
        users.get(user.getId()).update(user);

        //  Assign role
        var role = keycloak.realm(realm)
                .roles()
                .get("tenant_admin")
                .toRepresentation();

        users.get(user.getId())
                .roles()
                .realmLevel()
                .add(Collections.singletonList(role));
    }
}