import React, { useEffect, useState } from 'react';
import ReactDOM from 'react-dom/client';
import { ReactKeycloakProvider, useKeycloak } from '@react-keycloak/web';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import keycloakInstance from './auth/keycloak';
import { ProjectProvider } from './context/ProjectContext';
import { BrandingProvider } from './context/BrandingContext';
import { CreditProvider } from './context/CreditContext';
import App from './App';
import LandingPage from './pages/LandingPage';
import Onboarding from './pages/Onboarding';
import { getMe } from './utils/api';
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

const initOptions = {
  onLoad: 'check-sso',
  pkceMethod: 'S256',
  checkLoginIframe: false,
};

function FullScreenSpinner() {
  return (
    <div style={{
      height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: '#f8fafc',
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
  const { keycloak, initialized } = useKeycloak();
  const [provisioned,  setProvisioned]  = useState(false);
  const [needsOnboard, setNeedsOnboard] = useState(false);

  // ── Silent token refresh every 60s ─────────────────────────────────────────
  useEffect(() => {
    if (!initialized || !keycloak.authenticated) return;
    const interval = setInterval(() => {
      keycloak.updateToken(70).catch(() => {});
    }, 60_000);
    return () => clearInterval(interval);
  }, [initialized, keycloak.authenticated]);

  // ── On every login: call /me → check onboarding status from DB ─────────────
  //
  // KEY CHANGE: We now use the /me API response (onboarded: true/false) to
  // decide whether to show onboarding — NOT the JWT tenantId claim.
  //
  // Why: The JWT mapper may not have tenantId yet (Keycloak attribute write
  // is async / mapper config may be incomplete). The DB is always authoritative.
  //
  // Flow:
  //   1. Call GET /me → backend checks DB → returns { onboarded: true/false }
  //   2. If onboarded=false  → show Onboarding page
  //   3. If onboarded=true   → force token refresh (to pick up latest claims)
  //                          → render App
  // ───────────────────────────────────────────────────────────────────────────
  useEffect(() => {
    if (!initialized || !keycloak.authenticated || !keycloak.token) return;

    getMe(keycloak)
      .then(meData => {
        const onboarded = meData?.onboarded === true; // ← DB-based, not JWT-based

        if (!onboarded) {
          // New user — show onboarding immediately, no token refresh needed
          setNeedsOnboard(true);
          setProvisioned(true);
          return;
        }

        // Already onboarded — force token refresh to get latest claims
        // then render the app
        keycloak.tokenParsed.exp = 0;
        keycloak.updateToken(-1)
          .then(() => new Promise(resolve => setTimeout(resolve, 300)))
          .finally(() => {
            setNeedsOnboard(false);
            setProvisioned(true);
          });
      })
      .catch(() => {
        // /me failed — safe fallback: show app, let individual pages handle errors
        setNeedsOnboard(false);
        setProvisioned(true);
      });

  }, [initialized, keycloak.authenticated, keycloak.token]);

  // Keycloak still initialising
  if (!initialized) return <FullScreenSpinner />;

  // Not authenticated — show landing page
  if (!keycloak.authenticated) return <LandingPage />;

  // Authenticated but /me call not complete yet
  if (!provisioned) return <FullScreenSpinner />;

  // New user — show onboarding
  if (needsOnboard) return <Onboarding keycloak={keycloak} />;

  // Fully provisioned — render app
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
  <ReactKeycloakProvider authClient={keycloakInstance} initOptions={initOptions}>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AppWrapper />
      </BrowserRouter>
    </QueryClientProvider>
  </ReactKeycloakProvider>
);
