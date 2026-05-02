// src/pages/AuthCallback.jsx
//
// Handles the OIDC redirect that lands at /auth/callback after every
// Zitadel login. This is the component that was missing — without it
// the page spins forever because nobody completes the PKCE handshake
// or calls navigate() after authentication.
//
// Flow:
//   1. useAuth().signinRedirectCallback()  — exchanges the code for tokens
//   2. handlePostLoginGuard()              — DB guard + role check
//   3. navigate('/onboarding')             — new user, no tenant yet
//      navigate('/dashboard')             — returning user with tenant
//
// Mount this component at the route matching VITE_ZITADEL_REDIRECT_URI
// e.g. <Route path="/auth/callback" element={<AuthCallback />} />

import React, { useEffect, useRef } from 'react';
import { useNavigate }              from 'react-router-dom';
import { useAuth }                  from 'react-oidc-context';
import { handlePostLoginGuard }     from '../auth/zitadel';

export default function AuthCallback() {
    const auth     = useAuth();
    const navigate = useNavigate();
    const ran      = useRef(false); // strict-mode guard — prevents double-run

    useEffect(() => {
        if (ran.current) return;
        ran.current = true;

        (async () => {
            try {
                // ── Step 1: complete the PKCE code exchange ──────────────
                // signinRedirectCallback() reads the ?code=&state= params from
                // the URL, exchanges the code for tokens, stores the user in
                // sessionStorage, and clears the URL params.
                await auth.signinRedirectCallback();

                // ── Step 2: check if this was a post-onboarding redirect ──
                // forceTokenRefresh sets this flag when it falls back to a
                // full redirect (signinSilent failed). Restore the destination.
                const wasOnboarding =
                    sessionStorage.getItem('post_onboard_redirect') === 'true';
                if (wasOnboarding) {
                    sessionStorage.removeItem('post_onboard_redirect');
                }

                // ── Step 3: DB guard + role detection ────────────────────
                // handlePostLoginGuard:
                //   - calls POST /api/onboard/ensure (upserts DB row, returns tenantId)
                //   - detects missing role → repair → forceTokenRefresh if needed
                //   - returns { status, needsSetup, tenantId }
                const result = await handlePostLoginGuard(auth);

                switch (result.status) {
                    case 'redirecting':
                        // forceTokenRefresh fired a full signinRedirect.
                        // The page is already navigating to Zitadel — do nothing.
                        return;

                    case 'ready':
                        navigate(
                            result.needsSetup ? '/onboarding' : '/dashboard',
                            { replace: true }
                        );
                        return;

                    case 'unauthenticated':
                        // Token not available — should not normally reach here
                        // because signinRedirectCallback() just ran above.
                        console.error('[AuthCallback] Unauthenticated after callback — redirecting to login.');
                        navigate('/login', { replace: true });
                        return;

                    default:
                        console.error('[AuthCallback] Unexpected guard result:', result);
                        navigate('/login', { replace: true });
                }

            } catch (err) {
                console.error('[AuthCallback] OIDC callback failed:', err.message);
                // Common causes:
                //   - "No matching state found in storage" — user hit back/refresh mid-flow
                //   - "Invalid state" — double-run (strict mode) — the useRef guard above prevents this
                //   - Network error reaching Zitadel
                // In all cases, send the user back to login rather than spinning.
                navigate('/login', { replace: true });
            }
        })();
    }, []); // intentionally empty — runs once on mount

    // ── Loading UI ────────────────────────────────────────────────────
    // Visible only during the brief code-exchange + guard round-trip.
    // Replace with your design system's spinner if you have one.
    return (
        <div style={{
            display:        'flex',
            flexDirection:  'column',
            alignItems:     'center',
            justifyContent: 'center',
            height:         '100vh',
            gap:            '16px',
            color:          'var(--color-text-secondary, #666)',
            fontFamily:     'sans-serif',
        }}>
            <svg
                width="32" height="32" viewBox="0 0 32 32"
                style={{ animation: 'spin 0.9s linear infinite' }}
            >
                <circle cx="16" cy="16" r="13"
                        fill="none" stroke="currentColor"
                        strokeWidth="3" strokeOpacity="0.2" />
                <path
                    d="M16 3 A13 13 0 0 1 29 16"
                    fill="none" stroke="currentColor" strokeWidth="3"
                    strokeLinecap="round" />
                <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
            </svg>
            <span style={{ fontSize: '14px' }}>Signing you in…</span>
        </div>
    );
}
