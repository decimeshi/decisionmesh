package com.decisionmesh.bootstrap.service;

import com.decisionmesh.billing.service.CreditLedgerService;
import com.decisionmesh.bootstrap.resource.OnboardingResource.SetupTenantRequest;
import com.decisionmesh.contracts.security.entity.*;
import com.decisionmesh.contracts.security.repository.*;
import com.decisionmesh.persistence.entity.OrgBrandingEntity;
import com.decisionmesh.persistence.repository.OrgBrandingRepository;
import com.decisionmesh.persistence.repository.TenantRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class OnboardingService {

    private static final Logger LOG = Logger.getLogger(OnboardingService.class);

    @Inject UserRepository userRepository;
    @Inject TenantRepository tenantRepository;
    @Inject OrganizationRepository organizationRepository;
    @Inject OrgBrandingRepository orgBrandingRepository;
    @Inject ProjectRepository projectRepository;
    @Inject UserOrganizationRepository userOrgRepository;
    @Inject MemberShipRepository memberRepository;
    @Inject CreditLedgerService  creditLedgerService;

    @Inject Vertx vertx;

    private WebClient client;

    @PostConstruct
    void init() {
        this.client = WebClient.create(vertx);
    }

    @ConfigProperty(name = "zitadel.url")
    Optional<String> zitadelUrl;

    @ConfigProperty(name = "zitadel.service-account-token")
    Optional<String> serviceToken;

    @ConfigProperty(name = "zitadel.organization-id")
    Optional<String> organizationId;

    @ConfigProperty(name = "zitadel.project-id")
    Optional<String> zitadelProjectId;

    // =========================================================
    // Result: returned from setupTenant so the API layer can tell
    // the frontend it must refresh its token before proceeding.
    //
    // Why: role is assigned AFTER the registration token is issued
    // (we only know the account type once the user answers the
    // onboarding question). The existing token has no role claim.
    // The frontend must call forceTokenRefresh() after receiving
    // requiresTokenRefresh=true, then retry its first operation.
    // =========================================================
    public record SetupTenantResult(UUID tenantId, boolean requiresTokenRefresh) {}

    // =========================================================
    // 1. Provision user (idempotent)
    // =========================================================

    @WithTransaction
    public Uni<UUID> provisionUser(String userSub, String email, String name) {

        UUID userId = toUserId(userSub);

        return UserEntity.findByKeycloakSub(userSub)
                .chain(existing -> {
                    if (existing != null) {
                        LOG.infof("User exists: %s tenant=%s",
                                existing.userId, existing.tenantId);
                        return Uni.createFrom().item(existing.tenantId);
                    }

                    UserEntity user = new UserEntity();
                    user.userId = userId;
                    user.email = email;
                    user.name = name;
                    user.isActive = true;

                    return userRepository.persist(user)
                            .invoke(u -> LOG.infof("Created user: %s", u.userId))
                            .replaceWith((UUID) null);
                });
    }

    // =========================================================
    // Result record for webhook provisioning path
    // =========================================================
    public record ProvisionResult(boolean isNew, boolean requiresTokenRefresh) {}

    // =========================================================
    // 2. Provision + assign role — Zitadel webhook path
    //
    // Called by POST /webhooks/zitadel/user-created.
    // Runs before the user's first login so the very first token
    // Zitadel issues already carries the tenant_user role claim.
    //
    // Idempotent — safe to call more than once (webhook retry,
    // local dev re-run). requiresTokenRefresh is always false here
    // because the role is set before login, not after.
    // =========================================================

    @WithTransaction
    public Uni<ProvisionResult> provisionAndAssignRole(String userSub,
                                                       String email,
                                                       String name) {
        UUID userId = toUserId(userSub);

        return UserEntity.<UserEntity>findByKeycloakSub(userSub)
                .chain(existing -> {
                    if (existing != null) {
                        LOG.infof("[WebhookProvision] User already in DB: %s — skipping insert",
                                existing.userId);
                        return Uni.createFrom().item(Boolean.FALSE);
                    }

                    LOG.infof("[WebhookProvision] New user — inserting: sub=%s email=%s",
                            userSub, email);
                    UserEntity user = new UserEntity();
                    user.userId   = userId;
                    user.email    = email;
                    user.name     = name;
                    user.isActive = true;

                    return userRepository.persist(user)
                            .invoke(u -> LOG.infof("[WebhookProvision] Inserted: %s", u.userId))
                            .replaceWith(Boolean.TRUE);
                })
                .chain(isNew -> assignZitadelRole(userSub, "tenant_user")
                        .map(roleAssigned -> {
                            if (!roleAssigned) {
                                LOG.errorf("[WebhookProvision] Role assignment FAILED for sub=%s — " +
                                        "user is in DB but has no role. " +
                                        "Call POST /api/onboard/repair-attributes to retry.", userSub);
                            }
                            // requiresTokenRefresh=false: role is set before the user's
                            // first login, so the first token will already carry it.
                            return new ProvisionResult(isNew, false);
                        }));
    }

    // =========================================================
    // 3. Ensure user exists in DB — post-login DB guard
    //
    // Called by POST /api/onboard/ensure on every OIDC callback.
    // Safety net for cases where the webhook was not delivered
    // (local dev without a tunnel, Zitadel Action misconfigured,
    // transient network error on registration).
    //
    // Returns the full UserEntity so the resource layer can send
    // userId, tenantId, and onboarding status to the frontend.
    // =========================================================

    @WithTransaction
    public Uni<UserEntity> ensureUser(String userSub, String email, String name) {

        UUID userId = toUserId(userSub);

        return UserEntity.<UserEntity>findByKeycloakSub(userSub)
                .chain(existing -> {
                    if (existing != null) {
                        LOG.debugf("[EnsureUser] User present: %s tenantId=%s",
                                existing.userId, existing.tenantId);
                        return Uni.createFrom().item(existing);
                    }

                    // Fallback insert — only reached if webhook was not delivered
                    LOG.warnf("[EnsureUser] User not found — fallback insert: sub=%s", userSub);
                    UserEntity user = new UserEntity();
                    user.userId   = userId;
                    user.email    = email;
                    user.name     = name;
                    user.isActive = true;

                    return userRepository.persist(user)
                            .invoke(u -> LOG.warnf("[EnsureUser] Fallback insert done: %s", u.userId));
                });
    }

    // =========================================================
    // 4. Setup tenant
    //
    // Returns SetupTenantResult instead of bare UUID so the
    // resource layer can forward requiresTokenRefresh=true to
    // the frontend. The frontend must call forceTokenRefresh()
    // on receiving that flag — the role was just added to Zitadel
    // and the existing token does not yet contain it.
    // =========================================================

    @WithTransaction
    public Uni<SetupTenantResult> setupTenant(String userSub, String name, SetupTenantRequest req) {

        return UserEntity.<UserEntity>findByKeycloakSub(userSub)
                .chain(found -> {
                    // Safety net: if the Zitadel webhook was missed (local dev),
                    // insert the user here rather than throwing, so setup can proceed.
                    if (found != null) return Uni.createFrom().item(found);

                    LOG.warnf("[SetupTenant] User not found for sub=%s — inserting fallback", userSub);
                    UserEntity fallback = new UserEntity();
                    fallback.userId   = toUserId(userSub);
                    fallback.isActive = true;
                    return userRepository.persist(fallback);
                })
                .chain(user -> {

                    if (user.tenantId != null)
                        return Uni.createFrom().failure(
                                new IllegalStateException("Tenant already exists"));

                    return "ORGANIZATION".equals(req.accountType)
                            ? createOrgTenant(user, userSub, req)
                            : createIndividualTenant(user, userSub, name);
                });
    }

    // =========================================================
    // Tenant builders
    // =========================================================

    private Uni<SetupTenantResult> createIndividualTenant(UserEntity user,
                                                          String sub,
                                                          String name) {

        String safeName = resolveDisplayName(name, user.email, sub);

        TenantEntity tenant = new TenantEntity();
        tenant.externalId = sub;
        tenant.name = safeName + "'s Workspace";
        tenant.accountType = "INDIVIDUAL";
        tenant.status = "ACTIVE";

        return tenantRepository.persist(tenant)
                .chain(t -> buildWorkspace(user, t, safeName, null, null))
                // ── Step 1: write metadata (non-critical, failure is logged but not fatal) ──
                .chain(t -> writeMetadata(sub, t.id, "INDIVIDUAL").replaceWith(t))
                // ── Step 2: assign Zitadel role (critical — failure surfaces to caller) ──
                // FIX: was combined with metadata in Uni.combine(), masking failures.
                // Now sequential and separated so role failure is visible.
                .chain(t -> assignZitadelRole(sub, "tenant_user")
                        .flatMap(roleAssigned ->
                                creditLedgerService.grantRegistrationGift(t.id)
                                        .onFailure().invoke(e -> LOG.warnf(
                                                "[Onboarding] Registration gift failed for tenant=%s: %s",
                                                t.id, e.getMessage()))
                                        .onFailure().recoverWithNull()
                                        .replaceWith(new SetupTenantResult(t.id, roleAssigned))));
    }

    private Uni<SetupTenantResult> createOrgTenant(UserEntity user,
                                                   String sub,
                                                   SetupTenantRequest req) {

        TenantEntity tenant = new TenantEntity();
        tenant.externalId = sub;
        tenant.name = req.companyName;
        tenant.accountType = "ORGANIZATION";
        tenant.status = "ACTIVE";

        return tenantRepository.persist(tenant)
                .chain(t -> buildWorkspace(user, t,
                        req.companyName, req.companyName, req.companySize))
                .chain(t -> writeMetadata(sub, t.id, "ORGANIZATION").replaceWith(t))
                .chain(t -> assignZitadelRole(sub, "tenant_user")
                        .flatMap(roleAssigned ->
                                creditLedgerService.grantRegistrationGift(t.id)
                                        .onFailure().invoke(e -> LOG.warnf(
                                                "[Onboarding] Registration gift failed for tenant=%s: %s",
                                                t.id, e.getMessage()))
                                        .onFailure().recoverWithNull()
                                        .replaceWith(new SetupTenantResult(t.id, roleAssigned))));
    }

    // =========================================================
    // Workspace builder
    // =========================================================

    private Uni<TenantEntity> buildWorkspace(UserEntity user,
                                             TenantEntity tenant,
                                             String name,
                                             String companyName,
                                             String companySize) {

        OrganizationEntity org = new OrganizationEntity();
        org.name = companyName != null ? companyName : name + "'s Org";
        org.tenantId = tenant.id;
        org.companySize = companySize;
        org.isActive = true;

        OrgBrandingEntity branding = new OrgBrandingEntity();
        branding.tenantId = tenant.id;
        branding.orgName = org.name;
        branding.primaryColor = "#2563eb";
        branding.updatedAt = Instant.now();

        ProjectEntity project = new ProjectEntity();
        project.tenantId = tenant.id;
        project.name = "Default Project";
        project.environment = "Production";
        project.isDefault = true;

        return Uni.combine().all()
                .unis(
                        organizationRepository.persist(org),
                        orgBrandingRepository.persist(branding),
                        projectRepository.persist(project)
                )
                .asTuple()
                .chain(tuple -> {

                    user.tenantId = tenant.id;

                    UserOrganizationEntity mapping = new UserOrganizationEntity();
                    mapping.userId = user.userId;
                    mapping.organizationId = tuple.getItem1().id;
                    mapping.tenantId = tenant.id;
                    mapping.role = "OWNER";
                    mapping.permissions = List.of("ALL");

                    MemberShipEntity member = new MemberShipEntity();
                    member.userId = user.userId;
                    member.tenantId = tenant.id;
                    member.projectId = tuple.getItem3().id;
                    member.role = "ADMIN";
                    member.lastActiveAt = Instant.now();

                    return Uni.combine().all()
                            .unis(
                                    userRepository.persist(user),
                                    userOrgRepository.persist(mapping),
                                    memberRepository.persist(member)
                            )
                            .asTuple().replaceWith(tenant);
                });
    }

    // =========================================================
    // Zitadel Metadata writer (non-critical)
    //
    // Metadata is supplementary (tenantId, accountType). A failure
    // here is logged but must not block the user — the DB is the
    // source of truth for tenantId.
    // =========================================================

    private Uni<Void> writeMetadata(String userId, UUID tenantId, String type) {
        return Uni.combine().all()
                .unis(
                        writeKey(userId, "tenantId", tenantId.toString()),
                        writeKey(userId, "accountType", type)
                )
                .discardItems()
                .onFailure().invoke(e ->
                        LOG.warnf("[Onboarding] Zitadel metadata write failed for %s: %s — " +
                                        "tenant created, DB fallback will resolve tenantId",
                                userId, e.getMessage())
                )
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    // =========================================================
    // Assign Zitadel Role (User Grant) — CRITICAL path
    //
    // FIX (was): onFailure().recoverWithNull() silently swallowed
    // all Zitadel errors. The tenant was created and 200 returned
    // to the frontend, but the role was never assigned. The user's
    // token had no role claim and all operations were blocked with
    // no visible error.
    //
    // FIX (now): returns Uni<Boolean> where true = role assigned,
    // false = assignment failed. The caller (createIndividualTenant /
    // createOrgTenant) surfaces this as requiresTokenRefresh in the
    // response, and also logs a clear warning so the broken state is
    // visible in server logs. On true, the frontend must call
    // forceTokenRefresh() to get a token that includes the new role.
    // =========================================================

    private Uni<Boolean> assignZitadelRole(String userId, String role) {

        String url       = zitadelUrl.orElse(null);
        String token     = serviceToken.orElse(null);
        String orgId     = organizationId.orElse(null);
        String projectId = zitadelProjectId.orElse("368134576352038839");

        if (url == null || token == null || orgId == null) {
            LOG.errorf("[Onboarding] Zitadel config missing — role '%s' NOT assigned for user=%s. " +
                    "Check zitadel.url, zitadel.service-account-token, zitadel.organization-id", role, userId);
            return Uni.createFrom().item(false);
        }

        JsonObject body = new JsonObject()
                .put("projectId", projectId)
                .put("roleKeys", List.of(role));

        LOG.infof("[Onboarding] Assigning role '%s' to user=%s projectId=%s", role, userId, projectId);

        return client.postAbs(url + "/management/v1/users/" + userId + "/grants")
                .bearerTokenAuthentication(token)
                .putHeader("x-zitadel-orgid", orgId)
                .sendJsonObject(body)
                .onItem().transform(res -> {
                    int status = res.statusCode();
                    if (status == 200 || status == 201) {
                        LOG.infof("[Onboarding] Role '%s' assigned to user=%s — " +
                                "frontend must refresh token to pick up new role claim", role, userId);
                        return true;
                    }
                    // ── FIX: was silently swallowed, now logged as ERROR ──────────
                    // This is why operations were blocked: role call returned non-2xx
                    // but the service returned 200 to the frontend anyway.
                    LOG.errorf("[Onboarding] Role assignment FAILED for user=%s: HTTP %d — %s. " +
                                    "User will be unable to perform operations until role is assigned. " +
                                    "Run /repair endpoint to retry.",
                            userId, status, res.bodyAsString());
                    return false;
                })
                .onFailure().invoke(e ->
                        LOG.errorf("[Onboarding] Role assignment exception for user=%s: %s — " +
                                        "User will be blocked. Run /repair endpoint to retry.",
                                userId, e.getMessage())
                )
                // ── FIX: recover to false instead of null so caller knows it failed ──
                .onFailure().recoverWithItem(false);
    }

    // =========================================================
    // Repair Zitadel metadata + role for existing users
    //
    // Use this to recover users stuck in a broken state (tenant
    // created but role not assigned). After this succeeds, the
    // frontend must call forceTokenRefresh() to get a token with
    // the newly assigned role.
    // =========================================================

    public Uni<Boolean> repairZitadelMetadata(String userSub) {

        LOG.infof("[Repair] Rewriting metadata and role for sub=%s", userSub);

        return UserEntity.findByKeycloakSub(userSub)
                .chain(user -> {

                    if (user == null)
                        return Uni.createFrom().failure(
                                new IllegalStateException("User not found: " + userSub));

                    if (user.tenantId == null)
                        return Uni.createFrom().failure(
                                new IllegalStateException("Tenant not initialized for user: " + userSub));

                    return TenantEntity.<TenantEntity>findById(user.tenantId)
                            .chain(tenant -> {

                                if (tenant == null)
                                    return Uni.createFrom().failure(
                                            new IllegalStateException("Tenant not found: " + user.tenantId));

                                String type = tenant.accountType != null
                                        ? tenant.accountType
                                        : "INDIVIDUAL";

                                // metadata is non-critical — write it but don't block on failure
                                return writeMetadata(userSub, user.tenantId, type)
                                        .chain(() -> assignZitadelRole(userSub, "tenant_user"));
                            });
                });
    }

    // =========================================================
    // Helpers
    // =========================================================

    private Uni<Void> writeKey(String userId, String key, String value) {

        if (zitadelUrl.isEmpty() || serviceToken.isEmpty() || organizationId.isEmpty()) {
            LOG.warnf("[Onboarding] Zitadel config missing — skipping metadata write for %s", key);
            return Uni.createFrom().voidItem();
        }

        String encoded = java.util.Base64.getEncoder()
                .encodeToString(value.getBytes());

        return client.putAbs(zitadelUrl.get() + "/management/v1/users/" + userId + "/metadata/" + key)
                .bearerTokenAuthentication(serviceToken.get())
                .putHeader("x-zitadel-orgid", organizationId.get())
                .sendJsonObject(new JsonObject().put("value", encoded))
                .onItem().invoke(res -> {
                    // FIX: Zitadel returns 201 on metadata create, 200 on update — accept both
                    if (res.statusCode() == 200 || res.statusCode() == 201) {
                        LOG.debugf("[Onboarding] Metadata written: %s=%s for user=%s",
                                key, value, userId);
                    } else {
                        LOG.warnf("[Onboarding] Metadata write returned %d for key=%s user=%s: %s",
                                res.statusCode(), key, userId, res.bodyAsString());
                    }
                })
                .onFailure().invoke(e ->
                        LOG.warnf("[Onboarding] Metadata write exception for key=%s user=%s: %s",
                                key, userId, e.getMessage())
                )
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    private String resolveDisplayName(String name, String email, String sub) {
        if (name != null && !name.isBlank() && !"null".equalsIgnoreCase(name.trim())) {
            return name.trim();
        }
        if (email != null && email.contains("@")) {
            String prefix = email.split("@")[0];
            if (!prefix.isBlank()) return prefix;
        }
        return sub.length() > 8 ? sub.substring(0, 8) : sub;
    }

    private UUID toUserId(String sub) {
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(
                    sub.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}