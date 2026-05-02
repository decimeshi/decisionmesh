import { createContext, useContext, useState, useEffect } from 'react';
import { getOrgBranding } from '../utils/api';

const DEFAULT_BRANDING = {
  orgName:      'DecisionMesh',
  primaryColor: '#2563eb',
  logoUrl:      null,
  logoInitial:  'D',
  favicon:      null,
};

const BrandingContext = createContext(null);

function hexToHsl(hex) {
  let r = parseInt(hex.slice(1, 3), 16) / 255;
  let g = parseInt(hex.slice(3, 5), 16) / 255;
  let b = parseInt(hex.slice(5, 7), 16) / 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  let h, s, l = (max + min) / 2;
  if (max === min) { h = s = 0; }
  else {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    switch (max) {
      case r: h = ((g - b) / d + (g < b ? 6 : 0)) / 6; break;
      case g: h = ((b - r) / d + 2) / 6; break;
      case b: h = ((r - g) / d + 4) / 6; break;
    }
  }
  return [Math.round(h * 360), Math.round(s * 100), Math.round(l * 100)];
}

function applyBrandingToDOM(branding) {
  const color = branding.primaryColor || DEFAULT_BRANDING.primaryColor;

  // Guard — only apply valid 6-digit hex colors
  if (!/^#[0-9a-fA-F]{6}$/.test(color)) return;

  const root = document.documentElement;
  const [h, s, l] = hexToHsl(color);

  root.style.setProperty('--brand-h',       h);
  root.style.setProperty('--brand-s',       `${s}%`);
  root.style.setProperty('--brand-l',       `${l}%`);
  root.style.setProperty('--brand-primary', color);
  root.style.setProperty('--brand-light',   `hsl(${h}, ${s}%, ${Math.min(l + 40, 95)}%)`);
  root.style.setProperty('--brand-dark',    `hsl(${h}, ${s}%, ${Math.max(l - 10, 10)}%)`);
  root.style.setProperty('--brand-text',    `hsl(${h}, ${Math.min(s + 10, 100)}%, ${Math.max(l - 20, 15)}%)`);

  if (branding.favicon) {
    let link = document.querySelector("link[rel~='icon']");
    if (!link) {
      link = document.createElement('link');
      link.rel = 'icon';
      document.head.appendChild(link);
    }
    link.href = branding.favicon;
  }

  if (branding.orgName) {
    document.title = `${branding.orgName} — AI Control Plane`;
  }
}

export function BrandingProvider({ keycloak, children }) {
  const [branding, setBranding] = useState(DEFAULT_BRANDING);
  const [loading,  setLoading]  = useState(true);

  // BrandingContext.jsx — replace the useEffect
  useEffect(() => {
    applyBrandingToDOM(DEFAULT_BRANDING);

    if (!keycloak?.authenticated) {
      setLoading(false);
      return;
    }

    // ── Wait for token to be available ────────────────────────────────────
    // request() silently returns null if token is missing — no network call made.
    // We use a direct fetch here so we always know exactly what's happening.
    const loadBranding = async () => {
      try {
        // Ensure fresh token
        await keycloak.updateToken(30).catch(() => {});

        const token = keycloak.token;
        if (!token) {
          console.warn('[Branding] token still missing after refresh');
          setLoading(false);
          return;
        }

        console.log('[Branding] fetching branding with token:', token.substring(0, 20) + '...');

        const res = await fetch('http://localhost:8080/api/org/branding', {
          headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type':  'application/json',
          }
        });

        console.log('[Branding] GET /api/org/branding status:', res.status);

        if (res.status === 403 || res.status === 401) {
          // ── Not onboarded yet or no tenant — use defaults silently ─────────
          // This happens on first login before setup-tenant completes.
          // BrandingProvider re-renders after reload so branding loads then.
          console.log('[Branding] No tenant yet (status:', res.status, ') — using defaults');
          return;
        }

        if (res.ok) {
          const data = await res.json();
          console.log('[Branding] raw response:', JSON.stringify(data));

          // Normalize — handles both camelCase and snake_case from backend
          const normalized = {
            primaryColor: data.primaryColor ?? data.primary_color ?? DEFAULT_BRANDING.primaryColor,
            orgName:      data.orgName      ?? data.org_name      ?? DEFAULT_BRANDING.orgName,
            logoUrl:      data.logoUrl      ?? data.logo_url      ?? null,
            favicon:      data.favicon      ?? null,
          };

          console.log('[Branding] applying primaryColor:', normalized.primaryColor);
          const merged = { ...DEFAULT_BRANDING, ...normalized };
          setBranding(merged);
          applyBrandingToDOM(merged);
        } else {
          console.warn('[Branding] GET failed:', res.status);
        }
      } catch (err) {
        console.error('[Branding] exception:', err.message);
      } finally {
        setLoading(false);
      }
    };

    loadBranding();

  }, [keycloak?.authenticated]);

  function updateBranding(updates) {
    const merged = { ...branding, ...updates };
    setBranding(merged);
    applyBrandingToDOM(merged);
  }

  return (
      <BrandingContext.Provider value={{ branding, updateBranding, loading }}>
        {children}
      </BrandingContext.Provider>
  );
}

export function useBranding() {
  const ctx = useContext(BrandingContext);
  if (!ctx) throw new Error('useBranding must be used inside BrandingProvider');
  return ctx;
}
