// ============================================================
// routes/setup-tenant.js
// POST /api/setup-tenant
// Called ONCE after first Zitadel login — sets up tenant context
// ============================================================

const express = require("express");
const axios   = require("axios");
const router  = express.Router();

// ── Zitadel config (replaces Keycloak) ───────────────────────
const ZITADEL_URL         = process.env.ZITADEL_URL;         // https://decisionmesh-1pgrry.eu1.zitadel.cloud
const ZITADEL_CLIENT_ID   = process.env.ZITADEL_CLIENT_ID;   // 368134611768783581
const ZITADEL_ORG_ID      = process.env.ZITADEL_ORG_ID;      // 368134337511633629
const ZITADEL_PROJECT_ID  = process.env.ZITADEL_PROJECT_ID;  // 368134576352038839
const ZITADEL_PAT         = process.env.ZITADEL_PAT;         // service account PAT token

// ─── HELPERS ─────────────────────────────────────────────────

// ── FIX: Replaced Keycloak token refresh with Zitadel ────────
async function refreshZitadelToken(refreshToken) {
  const params = new URLSearchParams({
    grant_type:    "refresh_token",
    refresh_token: refreshToken,
    client_id:     ZITADEL_CLIENT_ID,
  });

  const res = await axios.post(
      `${ZITADEL_URL}/oauth/v2/token`,
      params,
      { headers: { "Content-Type": "application/x-www-form-urlencoded" } }
  );

  return {
    accessToken:  res.data.access_token,
    refreshToken: res.data.refresh_token,
    idToken:      res.data.id_token,
    expiresIn:    res.data.expires_in,
  };
}

// ── Assign role via Zitadel Management API ────────────────────
async function assignZitadelRole(userId, role) {
  const res = await axios.post(
      `${ZITADEL_URL}/management/v1/users/${userId}/grants`,
      {
        projectId: ZITADEL_PROJECT_ID,
        roleKeys:  [role],
      },
      {
        headers: {
          Authorization:      `Bearer ${ZITADEL_PAT}`,
          "Content-Type":     "application/json",
          "x-zitadel-orgid":  ZITADEL_ORG_ID,
        },
      }
  );
  return res.data;
}

// ── Write Zitadel metadata ────────────────────────────────────
async function writeZitadelMetadata(userId, key, value) {
  const encoded = Buffer.from(value).toString("base64");

  await axios.post(
      `${ZITADEL_URL}/management/v1/users/${userId}/metadata/${key}`,
      { value: encoded },
      {
        headers: {
          Authorization:      `Bearer ${ZITADEL_PAT}`,
          "Content-Type":     "application/json",
          "x-zitadel-orgid":  ZITADEL_ORG_ID,
        },
      }
  );
}

// ─── MAIN ROUTE: POST /api/setup-tenant ──────────────────────
router.post("/setup-tenant", async (req, res) => {
  const { accountType, companyName, companySize } = req.body;
  const userId = req.user.sub;   // from decoded Zitadel JWT
  const email  = req.user.email;

  // ── Validation ───────────────────────────────────────────────
  if (!["INDIVIDUAL", "ORGANIZATION"].includes(accountType)) {
    return res.status(400).json({ message: "Invalid accountType" });
  }

  if (accountType === "ORGANIZATION" && !companyName?.trim()) {
    return res.status(400).json({ message: "companyName is required for organizations" });
  }

  // ── Check: already set up? ───────────────────────────────────
  const existingTenant = await db.tenant.findOne({ where: { ownerId: userId } });
  if (existingTenant) {
    return res.status(409).json({ message: "Tenant already set up for this user" });
  }

  try {
    let tenantId;

    // ════════════════════════════════════════════════════════
    // INDIVIDUAL FLOW
    // ════════════════════════════════════════════════════════
    if (accountType === "INDIVIDUAL") {
      tenantId = userId;

      // ── FIX: Write metadata to Zitadel (not Keycloak) ────
      await writeZitadelMetadata(userId, "tenantId",    tenantId);
      await writeZitadelMetadata(userId, "accountType", "INDIVIDUAL");

      // Save to DB
      await db.tenant.create({
        data: {
          id:      tenantId,
          type:    "INDIVIDUAL",
          name:    email,
          ownerId: userId,
        },
      });

      await db.user.upsert({
        where:  { id: userId },
        update: { tenantId, role: "OWNER" },
        create: { id: userId, email, tenantId, role: "OWNER" },
      });

      await db.billing.create({
        data: { tenantId, plan: "FREE", seats: 1 },
      });

      console.log(`✅ Individual tenant set up: ${userId}`);
    }

        // ════════════════════════════════════════════════════════
        // ORGANIZATION FLOW
    // ════════════════════════════════════════════════════════
    else if (accountType === "ORGANIZATION") {
      tenantId = `org-${Date.now()}`;

      // ── FIX: Write metadata to Zitadel (not Keycloak) ────
      await writeZitadelMetadata(userId, "tenantId",    tenantId);
      await writeZitadelMetadata(userId, "accountType", "ORGANIZATION");

      await db.tenant.create({
        data: {
          id:          tenantId,
          type:        "ORGANIZATION",
          name:        companyName.trim(),
          companySize: companySize || null,
          ownerId:     userId,
        },
      });

      await db.user.upsert({
        where:  { id: userId },
        update: { tenantId, role: "OWNER" },
        create: { id: userId, email, tenantId, role: "OWNER" },
      });

      await db.billing.create({
        data: { tenantId, plan: "FREE", seats: 1 },
      });

      console.log(`✅ Org tenant set up: ${companyName} → tenantId: ${tenantId}`);
    }

    // ── FIX: Assign Zitadel role after tenant setup ───────────
    await assignZitadelRole(userId, "tenant_user");
    console.log(`✅ Role 'tenant_user' assigned to user: ${userId}`);

    // ── Response — tell frontend to refresh token ─────────────
    return res.status(201).json({
      message:              "Tenant setup complete",
      tenantId,
      accountType,
      requiresTokenRefresh: true,   // ← frontend must call /api/refresh-token
    });

  } catch (err) {
    console.error("setup-tenant error:", err.response?.data || err.message);
    return res.status(500).json({ message: "Failed to set up tenant", error: err.message });
  }
});

// ─── TOKEN REFRESH ROUTE: POST /api/refresh-token ────────────
// ── FIX: New endpoint — frontend calls this after setup-tenant
// to get a new token that includes the tenant_user role
router.post("/refresh-token", async (req, res) => {
  const { refreshToken } = req.body;

  if (!refreshToken) {
    return res.status(400).json({ message: "refreshToken is required" });
  }

  try {
    const tokens = await refreshZitadelToken(refreshToken);

    console.log(`✅ Token refreshed successfully`);

    return res.status(200).json({
      accessToken:  tokens.accessToken,
      refreshToken: tokens.refreshToken,
      idToken:      tokens.idToken,
      expiresIn:    tokens.expiresIn,
    });

  } catch (err) {
    console.error("Token refresh error:", err.response?.data || err.message);
    return res.status(401).json({ message: "Token refresh failed", error: err.message });
  }
});

module.exports = router;


// ============================================================
// middleware/check-onboarding.js
// Redirects to /onboarding if user has no tenantId in JWT
// ============================================================

async function checkOnboarding(req, res, next) {
  const skipRoutes = ["/onboarding", "/api/setup-tenant", "/api/refresh-token", "/health"];
  if (skipRoutes.some(r => req.path.startsWith(r))) return next();

  const tenantId = req.user?.tenantId;

  if (!tenantId) {
    return res.redirect("/onboarding");
  }

  next();
}

module.exports = { checkOnboarding };

