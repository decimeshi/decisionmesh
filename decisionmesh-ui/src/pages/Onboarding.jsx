import { useState } from 'react';
import { Building2, User, ChevronRight, Loader2 } from 'lucide-react';
import { setupTenant } from '../utils/api';

// FIX: added onComplete to props — was missing entirely.
// main.jsx passes onComplete={onOnboardingComplete} which handles
// signinSilent token refresh. Without it, the token refresh in
// main.jsx never ran and the old roleless token caused 403s on
// every API call after onboarding.
export default function Onboarding({ keycloak, onComplete }) {
  const [step,        setStep]        = useState('pick');   // pick | org-details | submitting
  const [accountType, setAccountType] = useState(null);
  const [companyName, setCompanyName] = useState('');
  const [companySize, setCompanySize] = useState('');
  const [error,       setError]       = useState('');
  const [loading,     setLoading]     = useState(false);

  async function handleSubmit() {
    setError('');
    setLoading(true);

    try {
      const body = { accountType };
      if (accountType === 'ORGANIZATION') {
        if (!companyName.trim()) {
          setError('Company name is required');
          setLoading(false);
          return;
        }
        body.companyName = companyName.trim();
        body.companySize = companySize || null;
      }

      // FIX: use the setupTenant helper from api.js instead of raw request()
      await setupTenant(keycloak, body);

      // FIX: removed keycloak.updateToken(-1) + window.location.reload().
      //
      // The reload was the direct cause of all 403s:
      //   reload → page restarts → old roleless token still in sessionStorage
      //   → Dashboard renders → all API calls fire with roleless token → 403
      //
      // Instead: call onComplete() which runs onOnboardingComplete() in main.jsx.
      // That function does: removeUser() → signinSilent() → gets fresh token
      // WITH the tenant_user role → setNeedsOnboard(false) → Dashboard renders
      // with a valid token → all API calls succeed.
      await onComplete();

    } catch (err) {
      const msg = err?.message ?? '';

      // 409 = tenant already exists — treat as success and proceed.
      // The role was assigned in a previous attempt; just get a fresh token.
      if (msg.includes('409') || msg.includes('already') || msg.includes('Conflict')) {
        await onComplete();
        return;
      }

      setError(msg || 'Setup failed — please try again');
      setLoading(false);
    }
  }

  // ── Pick account type ──────────────────────────────────────────────────────
  if (step === 'pick') {
    return (
      <div style={styles.overlay}>
        <div style={styles.card}>
          <div style={styles.logo}>
            <img src="/decimeshi-icon.svg" alt="DecisionMesh" style={{ width: 40, height: 40 }} />
          </div>
          <h1 style={styles.title}>Welcome to DecisionMesh</h1>
          <p style={styles.subtitle}>How will you be using the platform?</p>

          <div style={styles.options}>
            <button
              style={{
                ...styles.option,
                ...(accountType === 'INDIVIDUAL' ? styles.optionActive : {}),
              }}
              onClick={() => setAccountType('INDIVIDUAL')}
            >
              <User size={28} color={accountType === 'INDIVIDUAL' ? '#2563eb' : '#64748b'} />
              <div>
                <p style={styles.optionTitle}>Individual</p>
                <p style={styles.optionDesc}>Personal projects and experimentation</p>
              </div>
            </button>

            <button
              style={{
                ...styles.option,
                ...(accountType === 'ORGANIZATION' ? styles.optionActive : {}),
              }}
              onClick={() => { setAccountType('ORGANIZATION'); setStep('org-details'); }}
            >
              <Building2 size={28} color={accountType === 'ORGANIZATION' ? '#2563eb' : '#64748b'} />
              <div>
                <p style={styles.optionTitle}>Organisation</p>
                <p style={styles.optionDesc}>Team collaboration and enterprise features</p>
              </div>
            </button>
          </div>

          {error && <p style={styles.error}>{error}</p>}

          <button
            style={{
              ...styles.btn,
              opacity: accountType ? 1 : 0.4,
              cursor:  accountType ? 'pointer' : 'not-allowed',
            }}
            disabled={!accountType || loading}
            onClick={handleSubmit}
          >
            {loading
              ? <Loader2 size={16} style={{ animation: 'spin 0.8s linear infinite' }} />
              : <><span>Continue</span><ChevronRight size={16} /></>
            }
          </button>
        </div>
        <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
      </div>
    );
  }

  // ── Organisation details ───────────────────────────────────────────────────
  return (
    <div style={styles.overlay}>
      <div style={styles.card}>
        <div style={styles.logo}>
          <img src="/decimeshi-icon.svg" alt="DecisionMesh" style={{ width: 40, height: 40 }} />
        </div>
        <h1 style={styles.title}>Tell us about your organisation</h1>
        <p style={styles.subtitle}>This helps us tailor your experience</p>

        <div style={styles.field}>
          <label style={styles.label}>Company name *</label>
          <input
            style={styles.input}
            placeholder="Acme Corporation"
            value={companyName}
            onChange={e => setCompanyName(e.target.value)}
            autoFocus
          />
        </div>

        <div style={styles.field}>
          <label style={styles.label}>Company size</label>
          <select
            style={styles.input}
            value={companySize}
            onChange={e => setCompanySize(e.target.value)}
          >
            <option value="">Select size</option>
            <option value="1-10">1–10 employees</option>
            <option value="11-50">11–50 employees</option>
            <option value="51-200">51–200 employees</option>
            <option value="201-1000">201–1000 employees</option>
            <option value="1000+">1000+ employees</option>
          </select>
        </div>

        {error && <p style={styles.error}>{error}</p>}

        <div style={{ display: 'flex', gap: 10 }}>
          <button
            style={{ ...styles.btn, background: '#f1f5f9', color: '#475569', flex: '0 0 auto' }}
            onClick={() => { setStep('pick'); setAccountType(null); setError(''); }}
          >
            Back
          </button>
          <button
            style={{ ...styles.btn, flex: 1 }}
            disabled={loading}
            onClick={handleSubmit}
          >
            {loading
              ? <Loader2 size={16} style={{ animation: 'spin 0.8s linear infinite' }} />
              : <><span>Create workspace</span><ChevronRight size={16} /></>
            }
          </button>
        </div>
        <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
      </div>
    </div>
  );
}

const styles = {
  overlay: {
    minHeight: '100vh',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    background: '#f8fafc',
    padding: 16,
  },
  card: {
    background: 'white',
    borderRadius: 16,
    padding: '40px 36px',
    width: '100%',
    maxWidth: 460,
    boxShadow: '0 4px 24px rgba(0,0,0,0.08)',
  },
  logo: {
    display: 'flex',
    justifyContent: 'center',
    marginBottom: 20,
  },
  title: {
    fontSize: 22,
    fontWeight: 700,
    color: '#0f172a',
    textAlign: 'center',
    margin: '0 0 8px',
  },
  subtitle: {
    fontSize: 14,
    color: '#64748b',
    textAlign: 'center',
    margin: '0 0 28px',
  },
  options: {
    display: 'flex',
    flexDirection: 'column',
    gap: 12,
    marginBottom: 24,
  },
  option: {
    display: 'flex',
    alignItems: 'center',
    gap: 16,
    padding: '16px 18px',
    border: '2px solid #e2e8f0',
    borderRadius: 12,
    background: 'white',
    cursor: 'pointer',
    textAlign: 'left',
    transition: 'all 0.15s',
    width: '100%',
  },
  optionActive: {
    border: '2px solid #2563eb',
    background: '#eff6ff',
  },
  optionTitle: {
    fontSize: 15,
    fontWeight: 600,
    color: '#0f172a',
    margin: '0 0 2px',
  },
  optionDesc: {
    fontSize: 13,
    color: '#64748b',
    margin: 0,
  },
  btn: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    width: '100%',
    padding: '12px 20px',
    background: '#2563eb',
    color: 'white',
    border: 'none',
    borderRadius: 10,
    fontSize: 15,
    fontWeight: 600,
    cursor: 'pointer',
  },
  field: {
    marginBottom: 16,
  },
  label: {
    display: 'block',
    fontSize: 13,
    fontWeight: 500,
    color: '#374151',
    marginBottom: 6,
  },
  input: {
    width: '100%',
    padding: '10px 12px',
    border: '1px solid #e2e8f0',
    borderRadius: 8,
    fontSize: 14,
    color: '#0f172a',
    outline: 'none',
    boxSizing: 'border-box',
  },
  error: {
    color: '#dc2626',
    fontSize: 13,
    margin: '0 0 12px',
    padding: '8px 12px',
    background: '#fef2f2',
    borderRadius: 8,
  },
};
