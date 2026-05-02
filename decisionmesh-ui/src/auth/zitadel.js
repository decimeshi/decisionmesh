// src/auth/zitadel.js
//
// Configures oidc-client-ts for Zitadel Cloud (PKCE, no client secret).
//
// .env.development:
//   VITE_ZITADEL_AUTHORITY=https://decisionmesh-1pgrry.eu1.zitadel.cloud
//   VITE_ZITADEL_CLIENT_ID=368134611768783581
//   VITE_ZITADEL_REDIRECT_URI=http://localhost:3000/auth/callback
//   VITE_ZITADEL_POST_LOGOUT_URI=http://localhost:3000
//   VITE_ZITADEL_SILENT_REDIRECT_URI=http://localhost:3000/auth/silent-callback
//
// Architecture — onboarding role flow:
//   1. User registers → Zitadel issues token (no role yet — correct,
//      account type is unknown until the user answers the onboarding question)
//   2. User picks Individual or Organization
//   3. Frontend calls POST /setup-tenant
//   4. Backend creates tenant, assigns 'tenant_user' role in Zitadel,
//      returns { tenantId, requiresTokenRefresh: true }
//   5. Frontend calls forceTokenRefresh(auth) — drops cached token,
//      silently re-authenticates via Zitadel iframe, gets fresh token
//      that now contains the role claim
//   6. User proceeds — all operations allowed ✅
//
// Why forceTokenRefresh is needed:
//   Zitadel bakes roles into the token at issuance. Assigning a role
//   after the fact does not update the existing token — a new token
//   must be obtained. signinSilent() does this without any login UI
//   as long as the Zitadel browser session is still active.

import { WebStorageStateStore } from 'oidc-client-ts';

const AUTHORITY = import.meta.env.VITE_ZITADEL_AUTHORITY
    ?? 'https://decisionmesh-1pgrry.eu1.zitadel.cloud';

const CLIENT_ID = import.meta.env.VITE_ZITADEL_CLIENT_ID
    ?? '368134611768783581';

const REDIRECT_URI = import.meta.env.VITE_ZITADEL_REDIRECT_URI
    ?? 'http://localhost:3000/auth/callback';

const POST_LOGOUT = import.meta.env.VITE_ZITADEL_POST_LOGOUT_URI
    ?? 'http://localhost:3000';

// FIX: added — required for signinSilent() to work via hidden iframe.
// Without this, forceTokenRefresh() fails in all browsers because
// oidc-client-ts has nowhere to deliver the silent renew response.
// Register this URI in Zitadel Console → Application → Redirect URIs.
const SILENT_REDIRECT_URI = import.meta.env.VITE_ZITADEL_SILENT_REDIRECT_URI
    ?? 'http://localhost:3000/auth/silent-callback';

// ── Zitadel role claim key ────────────────────────────────────
// Roles are nested under this key in the access token payload.
// e.g. { "urn:zitadel:iam:org:project:roles": { "tenant_user": { ... } } }
const ZITADEL_ROLES_CLAIM = 'urn:zitadel:iam:org:project:roles';


// ── OIDC config ───────────────────────────────────────────────
export const oidcConfig = {
    authority:                AUTHORITY,
    client_id:                CLIENT_ID,
    redirect_uri:             REDIRECT_URI,
    post_logout_redirect_uri: POST_LOGOUT,
    response_type:            'code',

    // Roles scope is required — without it Zitadel omits the roles
    // claim entirely from the JWT even if roles are correctly assigned.
    scope: 'openid profile email offline_access urn:zitadel:iam:org:project:roles',

    // ── Silent renew ──────────────────────────────────────────
    // FIX: added silent_redirect_uri — the iframe target page for
    // signinSilent(). Must exist at this path and call
    // signinSilentCallback(). See public/auth/silent-callback.html.
    silent_redirect_uri: SILENT_REDIRECT_URI,

    // Automatic renewal is safe: any token renewed after the initial
    // forceTokenRefresh() will also carry the role claim since the
    // role is already recorded in Zitadel from the onboarding step.
    automaticSilentRenew:                         true,
    accessTokenExpiringNotificationTimeInSeconds:  60, // renew 60s before expiry

    // ── Session monitoring ────────────────────────────────────
    // Kept off — Zitadel's iframe-based session check is unreliable
    // across cloud tenants and causes false logouts.
    monitorSession:              false,
    checkSessionInterval:        0,

    // ── Token handling ────────────────────────────────────────
    includeIdTokenInSilentRenew: false, // Zitadel: access_token renewal only
    filterProtocolClaims:        true,  // strips nonce/at_hash from profile

    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    loadUserInfo: true,
};


// ── Token utilities ───────────────────────────────────────────

/**
 * Safely decodes the payload of a JWT without verifying the signature.
 * Verification is done server-side; this is for reading claims in the browser.
 *
 * @param {string} accessToken
 * @returns {object|null} decoded payload, or null on failure
 */
export function decodeToken(accessToken) {
    try {
        return JSON.parse(atob(accessToken.split('.')[1]));
    } catch {
        return null;
    }
}

/**
 * Extracts the project roles map from a decoded token payload.
 * Zitadel stores roles as: { "roleName": { "projectId": "orgId" } }
 *
 * @param {object} payload - decoded JWT payload from decodeToken()
 * @returns {Record<string, object>} role map, or empty object if none
 */
export function extractRoles(payload) {
    if (!payload) return {};
    return payload[ZITADEL_ROLES_CLAIM] ?? {};
}

/**
 * Checks whether a user has a specific role in their token.
 *
 * @param {object} payload - decoded JWT payload from decodeToken()
 * @param {string} role    - role key to check, e.g. "tenant_user", "admin"
 * @returns {boolean}
 */
export function hasRole(payload, role) {
    const roles = extractRoles(payload);
    return Object.prototype.hasOwnProperty.call(roles, role);
}

/**
 * Logs token diagnostics to the console. Use during development to
 * verify role assignment and token expiry without decoding manually.
 *
 * @param {string} accessToken
 */
export function debugToken(accessToken) {
    const payload = decodeToken(accessToken);
    if (!payload) {
        console.warn('[Zitadel] debugToken: could not decode token');
        return;
    }

    const roles    = extractRoles(payload);
    const exp      = payload.exp ? new Date(payload.exp * 1000).toISOString() : 'missing';
    const email    = payload.email ?? 'missing';
    const roleList = Object.keys(roles);

    console.group('[Zitadel] Token diagnostics');
    console.log('Email  :', email);
    console.log('Expires:', exp);
    // FIX: was "check PreUserCreation action" — roles come from post-onboarding
    // assignment. If empty here it means either: onboarding hasn't completed yet,
    // forceTokenRefresh() wasn't called after setupTenant, or the backend role
    // assignment failed (check server logs for "[Onboarding] Role assignment FAILED").
    console.log('Roles  :', roleList.length ? roleList : 'NONE — complete onboarding or check backend logs');
    console.log('Full roles claim:', roles);
    console.groupEnd();
}


// ── Keycloak-compatible shim ──────────────────────────────────
// Wraps react-oidc-context `auth` into a keycloak-like interface so
// all existing code (api.js, BrandingContext, pages) works unchanged.
//
// Receives `auth` directly instead of `(user, auth)` — the old
// approach closed over `user` at shim-creation time so after a silent
// renewal updateToken() would read a stale token. Using auth.user as
// a getter reads the live value from oidc-client-ts on every call.
export function createKeycloakShim(auth) {

    // Guard: auth itself is undefined on the very first render before
    // react-oidc-context's AuthProvider has finished initializing.
    // Treat it the same as an unauthenticated state — the component
    // will re-render once auth is ready and the shim will be rebuilt.
    if (!auth?.user) {
        return {
            authenticated: false,
            token:         null,
            tokenParsed:   null,
            login:         () => auth.signinRedirect(),
            logout:        () => auth.signoutRedirect(),
            updateToken:   () => Promise.resolve(false),
            hasRole:       () => false,
        };
    }

    return {
        authenticated: true,

        // Getters — always read live from auth.user so token stays
        // current after automatic silent renewal or forceTokenRefresh().
        get token()       { return auth.user?.access_token ?? null; },
        get tokenParsed() { return auth.user?.profile ?? null; },

        login:  () => auth.signinRedirect(),
        logout: () => auth.signoutRedirect(),

        /**
         * Ensures the access token is valid for at least `minValidity` seconds.
         * Returns true if valid (or successfully renewed), false if renewal failed.
         * Caller should handle false by redirecting to login.
         *
         * @param {number} minValidity seconds of validity required (default 30)
         */
        updateToken: (minValidity = 30) => {
            const exp   = auth.user?.expires_at ?? 0;
            const now   = Math.floor(Date.now() / 1000);
            const valid = exp - now > minValidity;

            if (valid) return Promise.resolve(true);

            return auth.signinSilent()
                .then(() => true)
                .catch((err) => {
                    console.warn('[Zitadel] Silent renew failed:', err?.message);
                    return false;
                });
        },

        /**
         * Convenience role check — reads live token on every call.
         * Usage: keycloak.hasRole('tenant_user')
         *
         * @param {string} role
         */
        hasRole: (role) => {
            const payload = decodeToken(auth.user?.access_token ?? '');
            return hasRole(payload, role);
        },
    };
}


// ── Force fresh token after onboarding role assignment ────────
//
// Called immediately after POST /setup-tenant returns
// { requiresTokenRefresh: true } from OnboardingService.
//
// Why this is needed:
//   The registration token is issued before the user answers the
//   onboarding question, so it contains no role. OnboardingService
//   assigns 'tenant_user' in Zitadel during setupTenant(), but that
//   does not update the existing token — Zitadel bakes roles at
//   issuance time. A brand-new token must be obtained.
//
// How it works:
//   1. auth.removeUser()   — evicts the stale no-role token from sessionStorage
//   2. auth.signinSilent() — opens a hidden iframe pointed at SILENT_REDIRECT_URI,
//                            completes a full PKCE flow invisibly (no login UI
//                            shown because the Zitadel browser session is active)
//   3. Zitadel issues a fresh access token that includes 'tenant_user' role
//
// Fallback: if signinSilent fails (Zitadel session expired or iframe
// blocked by browser), falls back to a full redirect login with
// prompt=login so Zitadel always issues a completely fresh token.
// ── Email helper ──────────────────────────────────────────────────────
// Zitadel does NOT put email in the access token by default.
// It appears in the userinfo endpoint, which oidc-client-ts loads into
// auth.user.profile when loadUserInfo=true (already set in oidcConfig).
// Always read email from profile — never from the decoded access token.
export function resolveEmail(auth) {
    return auth?.user?.profile?.email
        ?? auth?.user?.profile?.preferred_username
        ?? '';
}

// ── Force fresh token after role assignment ───────────────────────────
//
// Strategy:
//   1. signinSilent() — hidden iframe PKCE, no login UI, page stays put.
//      Works when the Zitadel browser session is still active.
//      Returns the new User object if successful, null on redirect fallback.
//
//   2. signinRedirect fallback — only if signinSilent throws (session
//      expired, iOS Safari blocks iframes, silent_redirect_uri not set).
//      Sets post_onboard_redirect flag so the caller can restore state.
//
// IMPORTANT: when signinSilent succeeds the page does NOT navigate.
// The caller must check the return value and call navigate() itself.
// Do NOT return { status: 'redirecting' } after a successful silent renew.
export async function forceTokenRefresh(auth) {
    try {
        console.log('[Zitadel] forceTokenRefresh — evicting stale token...');
        await auth.removeUser();

        console.log('[Zitadel] forceTokenRefresh — attempting signinSilent...');
        const newUser = await auth.signinSilent();

        if (newUser?.access_token) {
            debugToken(newUser.access_token);
            console.log('[Zitadel] forceTokenRefresh — silent renew succeeded.');
        } else {
            console.warn('[Zitadel] forceTokenRefresh — signinSilent returned no user. ' +
                'Ensure VITE_ZITADEL_SILENT_REDIRECT_URI is registered in ' +
                'Zitadel Console → Application → Redirect URIs.');
        }

        return newUser; // not null → page stayed, caller must navigate()

    } catch (err) {
        console.warn('[Zitadel] signinSilent failed:', err.message,
            '— falling back to full redirect.');
        sessionStorage.setItem('post_onboard_redirect', 'true');
        await auth.signinRedirect({ extraQueryParams: { prompt: 'login' } });
        return null; // null → redirect in progress, caller should NOT navigate()
    }
}


// ── Post-login guard ──────────────────────────────────────────────────
//
// Wire this into your onSigninCallback (OIDC callback handler).
// It handles both paths:
//   A) Token already has tenant_user role (webhook ran) → DB guard → route
//   B) Token has no role (local dev, webhook missed)    → ensure → repair → refresh → route
//
// Usage in your callback component:
//   const result = await handlePostLoginGuard(auth);
//   if (result.status === 'redirecting') return;      // page navigating to Zitadel
//   navigate(result.needsSetup ? '/onboarding' : '/dashboard');
export async function handlePostLoginGuard(auth, apiBase = API_BASE) {

    if (!auth?.user?.access_token) {
        return { status: 'unauthenticated' };
    }

    const payload    = decodeToken(auth.user.access_token);
    const hasRoleNow = hasRole(payload, 'tenant_user');

    // ── Path A: role present — DB guard then route ────────────────────
    if (hasRoleNow) {
        return await _ensureAndRoute(auth, apiBase);
    }

    // ── Path B: no role — local dev fallback ──────────────────────────
    console.warn('[Zitadel] No tenant_user role in token — running fallback.');

    try {
        const email = auth.user?.profile?.email
            ?? auth.user?.profile?.preferred_username
            ?? '';
        const name  = auth.user?.profile?.name ?? '';

        const ensureRes = await fetch(`${apiBase}/api/onboard/ensure`, {
            method:  'POST',
            headers: {
                'Authorization': `Bearer ${auth.user.access_token}`,
                'Content-Type':  'application/json',
            },
            body: JSON.stringify({ email, name }),
        });

        // If ensure fails, fail open — send to onboarding to self-heal
        if (!ensureRes.ok) {
            console.warn('[Zitadel] /ensure returned', ensureRes.status, '— defaulting to onboarding.');
            return { status: 'ready', needsSetup: true, tenantId: null };
        }

        const userData = await ensureRes.json();

        if (!userData.onboarded) {
            // New user — role will be assigned on setup-tenant completion.
            return { status: 'ready', needsSetup: true, tenantId: null };
        }

        // Returning user with missing role — repair then refresh token.
        await fetch(`${apiBase}/api/onboard/repair-attributes`, {
            method:  'POST',
            headers: { 'Authorization': `Bearer ${auth.user.access_token}` },
        }).catch(e => console.warn('[Zitadel] repair-attributes error:', e.message));

        const newUser = await forceTokenRefresh(auth);

        if (newUser?.access_token) {
            // signinSilent succeeded — page did NOT navigate away.
            // Route now using the fresh token.
            return await _ensureAndRoute(auth, apiBase);
        }

        // signinRedirect fired — page is navigating to Zitadel login.
        return { status: 'redirecting' };

    } catch (err) {
        console.error('[Zitadel] handlePostLoginGuard error:', err.message);
        // Fail open — never hard-block the user
        return { status: 'ready', needsSetup: true, tenantId: null };
    }
}

async function _ensureAndRoute(auth, apiBase) {
    try {
        // Read email from userinfo profile — NOT from the access token.
        // Zitadel omits email from access tokens by default; oidc-client-ts
        // loads userinfo into auth.user.profile when loadUserInfo=true.
        const email = auth.user?.profile?.email
            ?? auth.user?.profile?.preferred_username
            ?? '';
        const name  = auth.user?.profile?.name ?? '';

        const res = await fetch(`${apiBase}/api/onboard/ensure`, {
            method:  'POST',
            headers: {
                'Authorization': `Bearer ${auth.user.access_token}`,
                'Content-Type':  'application/json',
            },
            body: JSON.stringify({ email, name }),
        });

        if (!res.ok) {
            // Fail open — transient backend error should not block navigation
            console.warn('[Zitadel] /ensure returned', res.status, '— defaulting to onboarding.');
            return { status: 'ready', needsSetup: true, tenantId: null };
        }

        const data = await res.json();
        console.log('[Zitadel] EnsureUser — tenantId:', data.tenantId,
            'onboarded:', data.onboarded);

        return {
            status:     'ready',
            tenantId:   data.tenantId ?? null,
            needsSetup: !data.onboarded,
        };

    } catch (err) {
        console.error('[Zitadel] _ensureAndRoute error:', err.message);
        return { status: 'ready', needsSetup: true, tenantId: null };
    }
}