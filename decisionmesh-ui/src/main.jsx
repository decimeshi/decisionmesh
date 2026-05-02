import React, { useMemo, useState, useEffect, useCallback } from 'react';
import ReactDOM from 'react-dom/client';
import { AuthProvider, useAuth } from 'react-oidc-context';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { ProjectProvider }  from './context/ProjectContext';
import { BrandingProvider } from './context/BrandingContext';
import { CreditProvider }   from './context/CreditContext';
import App          from './App';
import LandingPage  from './pages/LandingPage';
import Onboarding   from './pages/Onboarding';
import { getMe }    from './utils/api';
import { oidcConfig, createKeycloakShim, debugToken } from './auth/zitadel';
import './index.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (count, err) => {
        const status = err?.response?.status;
        if (status >= 400 && status < 500) return false;
        return count < 2;
      },
    },
  },
});

function FullScreenSpinner() {
  return (
    <div style={{
      height: '100vh', display: 'flex', alignItems: 'center',
      justifyContent: 'center', background: '#f8fafc',
    }}>
      <div style={{
        width: 32, height: 32,
        border: '2px solid rgba(37,99,235,0.3)',
        borderTopColor: '#2563eb',
        borderRadius: '50%',
        animation: 'spin 0.8s linear infinite',
      }} />
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}

function AppWrapper() {
  const auth = useAuth();

  const [provisioned,     setProvisioned]     = useState(false);
  const [needsOnboard,    setNeedsOnboard]    = useState(false);
  const [refreshingToken, setRefreshingToken] = useState(false);

  // FIX 1: was createKeycloakShim(auth.user, auth) — wrong 2-param signature.
  // Updated shim takes only `auth` and reads auth.user live via getter.
  //
  // FIX 5: was [auth.user, auth.isAuthenticated] — auth.user is an object
  // with a new reference on every render, causing the shim to rebuild
  // constantly. Keying on access_token string means it only rebuilds
  // when the actual token changes (login, refresh, logout).
  const keycloak = useMemo(
    () => createKeycloakShim(auth),
    [auth.user?.access_token, auth.isAuthenticated]
  );

  // ── Provision user on login ────────────────────────────────
  // Calls getMe to check if the user has completed onboarding.
  // Runs whenever the access token changes (covers initial login
  // and post-refresh re-checks).
  useEffect(() => {
    if (!auth.isAuthenticated || !auth.user?.access_token) return;

    debugToken(auth.user.access_token);

    getMe(keycloak)
      .then(meData => {
        const onboarded = meData?.onboarded === true;
        setNeedsOnboard(!onboarded);
        setProvisioned(true);
      })
      .catch(() => {
        // getMe failed — don't block the user, treat as not onboarded
        // so they can complete the onboarding flow
        setNeedsOnboard(true);
        setProvisioned(true);
      });

  }, [auth.isAuthenticated, auth.user?.access_token]);

  // ── Called by Onboarding after POST /setup-tenant succeeds ─
  //
  // Flow:
  //   1. setupTenant() assigned the role in Zitadel
  //   2. We must get a NEW token — the current one has no role
  //   3. signinSilent() exchanges a fresh PKCE code via hidden
  //      iframe — Zitadel returns a token with the role included
  //   4. We mark onboarding done and let the useEffect above
  //      re-run with the new access_token to re-confirm via getMe
  //
  // FIX 2: was `await auth.signinSilent()` with return value ignored,
  // then `auth.user` read immediately. auth.user is React state that
  // hasn't re-rendered yet — it's still the OLD token. signinSilent()
  // returns the new user directly; capture and use that instead.
  //
  // FIX 3+4: was `createKeycloakShim(newUser, auth)` then `getMe(freshKeycloak)`.
  // The freshKeycloak was built from stale auth.user, so getMe was called
  // with the old no-role token and returned onboarded=false — causing the
  // screen to stay stuck on onboarding forever.
  //
  // Fix: don't call getMe again here. setupTenant succeeded — onboarding
  // is definitively complete. Set needsOnboard(false) directly.
  // The useEffect above will re-run automatically when auth.user?.access_token
  // changes after signinSilent, re-confirming via getMe in the background.
  const onOnboardingComplete = useCallback(async () => {
    try {
      setRefreshingToken(true);
      console.log('[Auth] Onboarding complete — fetching fresh token with role...');

      // Step 1: evict stale no-role token from sessionStorage
      await auth.removeUser();

      // Step 2: silently re-authenticate — Zitadel browser session is
      // still active so no login UI is shown. Returns new user directly.
      const newUser = await auth.signinSilent();

      if (newUser?.access_token) {
        debugToken(newUser.access_token);
      } else {
        // signinSilent returned null — likely silent_redirect_uri is not
        // registered in Zitadel Console or the page doesn't exist yet.
        // Fallback below will handle this via redirect.
        throw new Error('signinSilent returned no user — check silent_redirect_uri config');
      }

      // Onboarding is done — setupTenant succeeded and we have a fresh token.
      // Don't call getMe again here: it would use auth.user (React state)
      // which hasn't updated yet, returning the stale token and onboarded=false.
      // The useEffect will re-run once auth.user?.access_token updates and
      // will call getMe with the correct fresh token at that point.
      setNeedsOnboard(false);

    } catch (err) {
      console.error('[Auth] Token refresh failed:', err.message);

      // Fallback: full redirect login — Zitadel always issues a fresh
      // token with roles on a new redirect flow. prompt=login in
      // extraQueryParams forces a new session even if one exists.
      console.warn('[Auth] Falling back to redirect login...');
      sessionStorage.setItem('post_onboard_redirect', 'true');
      await auth.signinRedirect({ extraQueryParams: { prompt: 'login' } });
    } finally {
      setRefreshingToken(false);
    }
  }, [auth]);

  // ── Handle post-onboard redirect fallback ──────────────────
  // If forceTokenRefresh fell back to a full redirect, the app
  // reloads after login. Restore the correct state here.
  useEffect(() => {
    if (!auth.isAuthenticated) return;
    const wasRedirected = sessionStorage.getItem('post_onboard_redirect');
    if (wasRedirected) {
      sessionStorage.removeItem('post_onboard_redirect');
      // getMe will run via the main useEffect above — no extra action needed
      console.log('[Auth] Returned from post-onboard redirect — getMe will re-check onboarded state');
    }
  }, [auth.isAuthenticated]);

  // ── Render ─────────────────────────────────────────────────
  if (auth.isLoading)        return <FullScreenSpinner />;
  if (auth.error)            return <LandingPage />;
  if (!auth.isAuthenticated) return <LandingPage />;
  if (!provisioned)          return <FullScreenSpinner />;
  if (refreshingToken)       return <FullScreenSpinner />;

  if (needsOnboard) return (
    <Onboarding
      keycloak={keycloak}
      onComplete={onOnboardingComplete}
    />
  );

  return (
    <BrandingProvider keycloak={keycloak}>
      <ProjectProvider keycloak={keycloak}>
        <CreditProvider keycloak={keycloak}>
          <App keycloak={keycloak} />
        </CreditProvider>
      </ProjectProvider>
    </BrandingProvider>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <AuthProvider
    {...oidcConfig}
    onSigninCallback={() => {
      // react-oidc-context has already exchanged the PKCE code internally
      // before calling this. Just clean the URL — AppWrapper's useEffect
      // will handle getMe, role detection, and onboarding routing.
      // The old window.location.href = '/' was throwing away the token
      // by triggering a full page reload before it was stored.
      window.history.replaceState({}, '', '/');
    }}
    onRemoveUser={() => {
      console.warn('[OIDC] Session removed — user stays on current page');
    }}
  >
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AppWrapper />
      </BrowserRouter>
    </QueryClientProvider>
  </AuthProvider>
);
