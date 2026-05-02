import { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { getCreditBalance } from '../utils/api';

const CreditContext = createContext(null);

export const MODEL_TIERS = {
  economy: {
    label:       'Economy',
    credits:     1,
    color:       '#16a34a',
    bg:          '#f0fdf4',
    models:      'GPT-4o-mini · Claude Haiku',
    description: 'Fast & affordable — great for most tasks',
  },
  standard: {
    label:       'Standard',
    credits:     5,
    color:       '#2563eb',
    bg:          '#eff6ff',
    models:      'GPT-4o · Claude Sonnet',
    description: 'Best quality for complex reasoning',
  },
  premium: {
    label:       'Premium',
    credits:     25,
    color:       '#7c3aed',
    bg:          '#f5f3ff',
    models:      'Claude Opus · GPT-4 Turbo',
    description: 'Maximum capability for critical tasks',
  },
  byok: {
    label:       'BYOK',
    credits:     1,
    color:       '#d97706',
    bg:          '#fffbeb',
    models:      'Your Anthropic / OpenAI key',
    description: 'Orchestration only — 87% margin',
  },
  byom: {
    label:       'BYOM',
    credits:     1,
    color:       '#7c3aed',
    bg:          '#f5f3ff',
    models:      'Your own model endpoint',
    description: 'Zero data egress — on-prem governance',
  },
};

export function CreditProvider({ keycloak, children }) {
  const [balance,   setBalance]   = useState(null);
  const [allocated, setAllocated] = useState(null);
  const [plan,      setPlan]      = useState('free');
  const [loading,   setLoading]   = useState(true); // explicit loading flag

  const load = useCallback(async () => {
    if (!keycloak?.authenticated) return;
    try {
      // Uses the shared request() helper (token refresh + 401 handling)
      // instead of a duplicate fetch with a hardcoded URL.
      const data = await getCreditBalance(keycloak);
      if (data) {
        setBalance(data.balance ?? 500);
        setAllocated(data.monthlyAllocation ?? 500);
        setPlan(data.plan ?? 'free');
      } else {
        // API not ready — default to registration gift
        setBalance(500); setAllocated(500); setPlan('free');
      }
    } catch {
      setBalance(500); setAllocated(500); setPlan('free');
    } finally {
      setLoading(false);
    }
  }, [keycloak?.authenticated, keycloak?.token]);

  useEffect(() => { load(); }, [load]);

  // Optimistically deduct credits when an intent is submitted
  function deductCredits(tier = 'economy') {
    const cost = MODEL_TIERS[tier]?.credits ?? 1;
    setBalance(b => Math.max(0, (b ?? 0) - cost));
  }

  function refundCredits(tier = 'economy') {
    const cost = MODEL_TIERS[tier]?.credits ?? 1;
    setBalance(b => (b ?? 0) + cost);
  }

  const pct         = allocated ? Math.min(100, ((balance ?? 0) / allocated) * 100) : 100;
  const isLow       = pct < 20 && pct > 0;
  const isEmpty     = (balance ?? 1) <= 0;
  const statusColor = isEmpty ? '#dc2626' : isLow ? '#d97706' : '#16a34a';

  return (
    <CreditContext.Provider value={{
      balance, allocated, plan, pct,
      isLow, isEmpty, statusColor,
      loading,
      deductCredits, refundCredits,
      reload: load,
    }}>
      {children}
    </CreditContext.Provider>
  );
}

export function useCredits() {
  const ctx = useContext(CreditContext);
  if (!ctx) throw new Error('useCredits must be used inside CreditProvider');
  return ctx;
}
