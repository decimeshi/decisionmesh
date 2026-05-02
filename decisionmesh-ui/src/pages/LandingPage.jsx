import { useAuth } from 'react-oidc-context';
import { useState, useEffect } from 'react';

// ── Icons (inline SVG — no extra deps) ───────────────────────────────────────
const Icon = {
  Zap: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
    </svg>
  ),
  Shield: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
    </svg>
  ),
  DollarSign: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/>
    </svg>
  ),
  GitBranch: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <line x1="6" y1="3" x2="6" y2="15"/><circle cx="18" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><path d="M18 9a9 9 0 0 1-9 9"/>
    </svg>
  ),
  Activity: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>
    </svg>
  ),
  Users: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>
    </svg>
  ),
  TrendingUp: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="23 6 13.5 15.5 8.5 10.5 1 18"/><polyline points="17 6 23 6 23 12"/>
    </svg>
  ),
  ScrollText: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M8 21h12a2 2 0 0 0 2-2v-2H10v2a2 2 0 1 1-4 0V5a2 2 0 1 0-4 0v3h4"/><path d="M19 17V5a2 2 0 0 0-2-2H4"/><line x1="13" y1="9" x2="19" y2="9"/><line x1="13" y1="13" x2="19" y2="13"/>
    </svg>
  ),
  ArrowRight: () => (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/>
    </svg>
  ),
  Check: () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="20 6 9 17 4 12"/>
    </svg>
  ),
  Menu: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/>
    </svg>
  ),
  X: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
    </svg>
  ),
  RotateCcw: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 .49-3.5"/>
    </svg>
  ),
  UserCheck: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><polyline points="16 11 18 13 22 9"/>
    </svg>
  ),
  EyeOff: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/>
    </svg>
  ),
  Cpu: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="4" y="4" width="16" height="16" rx="2"/><rect x="9" y="9" width="6" height="6"/><line x1="9" y1="1" x2="9" y2="4"/><line x1="15" y1="1" x2="15" y2="4"/><line x1="9" y1="20" x2="9" y2="23"/><line x1="15" y1="20" x2="15" y2="23"/><line x1="20" y1="9" x2="23" y2="9"/><line x1="20" y1="14" x2="23" y2="14"/><line x1="1" y1="9" x2="4" y2="9"/><line x1="1" y1="14" x2="4" y2="14"/>
    </svg>
  ),
  AlertTriangle: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
    </svg>
  ),
  Lock: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>
    </svg>
  ),
  Star: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>
    </svg>
  ),
  Eye: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/>
    </svg>
  ),
};

// ── Data ──────────────────────────────────────────────────────────────────────
const PAIN_POINTS = [
  {
    emoji: '💸',
    title: 'Runaway AI costs',
    desc: 'A single prompt loop or misconfigured retry can drain thousands before anyone notices. There are no guardrails.',
  },
  {
    emoji: '🔇',
    title: 'Silent failures',
    desc: 'Your model returns garbage, times out, or hits rate limits. Your app fails. You find out from a user complaint.',
  },
  {
    emoji: '🕳️',
    title: 'Zero audit trail',
    desc: 'Who triggered that decision? What model answered? What did it cost? Nobody knows — and compliance asks anyway.',
  },
  {
    emoji: '🔁',
    title: "Can't reproduce AI failures",
    desc: "Something went wrong last Tuesday. You have no way to re-run it, compare outputs, or prove what happened to your team.",
  },
  {
    emoji: '🔓',
    title: 'Sensitive data leaking to models',
    desc: 'Customer emails, card numbers, health records — flowing raw into third-party AI APIs with no masking, no controls, no audit.',
  },
  {
    emoji: '🤥',
    title: 'AI that confidently lies',
    desc: 'Your model invents a legal citation, fabricates a drug dosage, or makes up a policy that does not exist. You shipped it to production.',
  },
  {
    emoji: '🕵️',
    title: 'Shadow AI everywhere',
    desc: 'Over 90% of employees use personal AI accounts for work. Your confidential data is in ChatGPT right now — and IT has no idea.',
  },
  {
    emoji: '💉',
    title: 'Prompt injection attacks',
    desc: 'A malicious input overrides your system prompt. The AI then follows the attacker instructions, not yours.',
  },
];

const FEATURES = [
  {
    icon: 'DollarSign',
    title: 'Budget enforcement',
    desc: 'Set hard cost ceilings per intent. Execution stops the moment spend exceeds your limit — before the bill arrives.',
    color: '#22c55e',
  },
  {
    icon: 'GitBranch',
    title: 'Multi-adapter routing',
    desc: 'Route to OpenAI, Anthropic, Google, or Azure. Automatic fallback to the next adapter on timeout or failure.',
    color: '#3b82f6',
  },
  {
    icon: 'Shield',
    title: 'Policy governance',
    desc: 'Write rules like "cost > $0.01 → REJECT" or "latency > 5s → FALLBACK". Policies run before every execution.',
    color: '#a855f7',
  },
  {
    icon: 'Activity',
    title: 'Execution timeline',
    desc: 'Every phase — CREATED → PLANNING → EXECUTING → COMPLETED — is recorded with cost, latency, and risk scores.',
    color: '#f59e0b',
  },
  {
    icon: 'RotateCcw',
    title: 'Decision replay',
    desc: 'Re-run any past intent with a single click. Debug failures, compare model outputs, and run regression tests against history.',
    color: '#10b981',
  },
  {
    icon: 'ScrollText',
    title: 'Compliance-ready audit trail',
    desc: 'Every AI decision is permanently logged — who triggered it, which model answered, what policy applied, and exactly what it cost. Built for SOC 2 and GDPR.',
    color: '#f43f5e',
  },
  {
    icon: 'UserCheck',
    title: 'Human-in-the-loop gates',
    desc: 'Pause any high-risk decision and require human approval before execution continues. Set thresholds by cost, risk score, or policy flag.',
    color: '#8b5cf6',
  },
  {
    icon: 'EyeOff',
    title: 'PII detection and masking',
    desc: 'Sensitive data in payloads is detected and masked before reaching AI models. Names, emails, card numbers — none leave your boundary unprotected.',
    color: '#f97316',
  },
  {
    icon: 'Cpu',
    title: 'Model version tracking',
    desc: 'Know exactly which model version made which decision, forever. Never lose that context after a provider update or silent model swap.',
    color: '#06b6d4',
  },
  {
    icon: 'TrendingUp',
    title: 'Drift detection',
    desc: 'Track model performance over time. Get alerted when an adapter starts behaving differently than expected.',
    color: '#ef4444',
  },
  {
    icon: 'Users',
    title: 'Multi-tenancy',
    desc: 'Full tenant isolation from day one. Each organisation has its own budget, policies, adapters, and audit log.',
    color: '#0ea5e9',
  },
  {
    icon: 'AlertTriangle',
    title: 'Hallucination detection',
    desc: 'Every AI response is scored for faithfulness and factual grounding before delivery. High-risk outputs trigger fallback or human review automatically.',
    color: '#f59e0b',
    badge: 'NEW',
  },
  {
    icon: 'Lock',
    title: 'Prompt injection protection',
    desc: 'Payloads are scanned for injection patterns before execution reaches any model. Attacks are blocked and logged — not silently swallowed.',
    color: '#ef4444',
    badge: 'NEW',
  },
  {
    icon: 'Star',
    title: 'Output quality scoring',
    desc: 'Every response gets an automated quality score — relevance, completeness, and tone. Set thresholds. Retry poor outputs. Track quality over time.',
    color: '#a78bfa',
    badge: 'NEW',
  },
  {
    icon: 'Eye',
    title: 'Shadow AI gateway',
    desc: 'Replace ungoverned employee AI usage with a single enterprise gateway. Full visibility, approved models only, and audit trails for every call.',
    color: '#14b8a6',
    badge: 'NEW',
  },
];

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Submit an intent',
    desc: 'Your app sends a goal — not a raw prompt. The control plane handles model selection, retries, and budget tracking.',
  },
  {
    step: '02',
    title: 'Govern the execution',
    desc: 'Policies evaluate cost, latency, and risk before and after execution. Violations trigger fallback or rejection automatically.',
  },
  {
    step: '03',
    title: 'Observe everything',
    desc: 'Every decision is logged — phase transitions, adapter calls, costs, drift scores. Full replay from any point in time.',
  },
];

const STATS = [
  { value: '< 50ms', label: 'orchestration overhead' },
  { value: '15', label: 'built-in features' },
  { value: '100%', label: 'audit coverage' },
  { value: '1-click', label: 'decision replay' },
  { value: 'SOC 2', label: 'ready architecture' },
  { value: 'GDPR', label: 'compliant by design' },
];

const PLANS = [
  {
    key:      'free',
    name:     'Free',
    price:    'Free',
    interval: null,
    note:     'One-time gift',
    credits:  null,
    color:    '#64748b',
    checkColor: '#22c55e',
    bg:       'rgba(255,255,255,0.04)',
    border:   'rgba(255,255,255,0.08)',
    cta:      'Get started free',
    ctaStyle: { background: 'rgba(255,255,255,0.08)', color: '#fff', border: '1px solid rgba(255,255,255,0.12)' },
    ctaHover: 'rgba(255,255,255,0.14)',
    features: [
      '500 credits (one-time)',
      '2 adapters',
      'Budget enforcement',
      'Basic audit (30 days)',
      'Community support',
    ],
  },
  {
    key:      'hobby',
    name:     'Hobby',
    price:    'Free',
    interval: null,
    note:     '2k credits/mo',
    credits:  '2k credits/mo',
    color:    '#94a3b8',
    checkColor: '#94a3b8',
    bg:       'rgba(255,255,255,0.04)',
    border:   'rgba(255,255,255,0.08)',
    cta:      'Start Hobby →',
    ctaStyle: { background: 'rgba(255,255,255,0.08)', color: '#fff', border: '1px solid rgba(255,255,255,0.12)' },
    ctaHover: 'rgba(255,255,255,0.14)',
    features: [
      '2,000 credits/month',
      '3 adapters',
      'Full audit (90 days)',
      'Email support',
    ],
  },
  {
    key:      'builder',
    name:     'Builder',
    price:    '$19',
    interval: '/month',
    note:     '15k credits/mo',
    credits:  '15k credits/mo',
    color:    '#2563eb',
    checkColor: '#60a5fa',
    popular:  true,
    bg:       'linear-gradient(135deg, rgba(37,99,235,0.15), rgba(124,58,237,0.15))',
    border:   'rgba(37,99,235,0.35)',
    cta:      'Upgrade to Builder →',
    ctaStyle: { background: '#2563eb', color: '#fff', border: 'none' },
    ctaHover: '#1d4ed8',
    features: [
      '15,000 credits/month',
      'All adapters',
      'Policy builder',
      'Decision replay',
      'Full audit + CSV export',
      'Drift detection',
      'Priority support',
      'Overage: $0.002/credit',
    ],
  },
  {
    key:      'pro',
    name:     'Pro',
    price:    '$49',
    interval: '/month',
    note:     '60k credits/mo',
    credits:  '60k credits/mo',
    color:    '#4f46e5',
    checkColor: '#818cf8',
    bg:       'rgba(255,255,255,0.04)',
    border:   'rgba(79,70,229,0.3)',
    cta:      'Upgrade to Pro →',
    ctaStyle: { background: 'rgba(79,70,229,0.15)', color: '#818cf8', border: '1px solid rgba(79,70,229,0.3)' },
    ctaHover: 'rgba(79,70,229,0.28)',
    features: [
      '60,000 credits/month',
      'Multi-tenancy',
      '5 team seats',
      'SSO / SAML',
      'Human-in-the-loop gates',
      'Priority support',
      'Overage: $0.001/credit',
    ],
  },
  {
    key:      'enterprise',
    name:     'Enterprise',
    price:    'Custom',
    interval: null,
    note:     'Unlimited',
    credits:  null,
    color:    '#7c3aed',
    checkColor: '#c4b5fd',
    bg:       'linear-gradient(135deg, rgba(139,92,246,0.12), rgba(244,63,94,0.08))',
    border:   'rgba(139,92,246,0.3)',
    topBar:   'linear-gradient(90deg, #8b5cf6, #f43f5e)',
    cta:      'Contact sales →',
    ctaHref:  'mailto:sales@decisionmesh.io',
    ctaStyle: { background: 'rgba(139,92,246,0.15)', color: '#c4b5fd', border: '1px solid rgba(139,92,246,0.3)' },
    ctaHover: 'rgba(139,92,246,0.28)',
    features: [
      'Unlimited credits',
      'PII detection & masking',
      'Model version tracking',
      'Immutable signed audit log',
      'GDPR data residency',
      'HIPAA / PCI-DSS templates',
      'BYOK (bring your own API key)',
      'Dedicated SLA',
    ],
  },
];

// ── Components ────────────────────────────────────────────────────────────────
function NavBar({ onLogin, onRegister }) {
  const [open, setOpen] = useState(false);

  return (
    <nav style={{
      position: 'fixed', top: 0, left: 0, right: 0, zIndex: 50,
      borderBottom: '1px solid rgba(255,255,255,0.06)',
      backdropFilter: 'blur(12px)',
      backgroundColor: 'rgba(8,8,10,0.85)',
    }}>
      <div style={{ maxWidth: 1100, margin: '0 auto', padding: '0 24px', display: 'flex', alignItems: 'center', height: 60 }}>
        {/* Logo */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flex: 1 }}>
          <div style={{
            width: 32, height: 32, borderRadius: 8,
            background: 'linear-gradient(135deg, #2563eb, #7c3aed)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="white">
              <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
            </svg>
          </div>
          <span style={{ color: '#fff', fontWeight: 600, fontSize: 16, letterSpacing: '-0.3px' }}>
            DecisionMesh
          </span>
          <span style={{
            fontSize: 10, fontWeight: 600, color: '#2563eb',
            background: 'rgba(37,99,235,0.15)', border: '1px solid rgba(37,99,235,0.3)',
            borderRadius: 4, padding: '2px 6px', letterSpacing: '0.5px',
          }}>
            BETA
          </span>
        </div>

        {/* Desktop links */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }} className="hidden-mobile">
          {['Features', 'How it works', 'Pricing'].map(l => (
            <a key={l} href={`#${l.toLowerCase().replace(/\s+/g, '-')}`}
              style={{ color: '#9ca3af', fontSize: 14, textDecoration: 'none', padding: '6px 12px', borderRadius: 6 }}
              onMouseEnter={e => e.target.style.color = '#fff'}
              onMouseLeave={e => e.target.style.color = '#9ca3af'}
            >{l}</a>
          ))}
          <button onClick={onLogin}
            style={{
              color: '#d1d5db', fontSize: 14, fontWeight: 500,
              background: 'none', border: 'none', cursor: 'pointer', padding: '6px 14px',
              borderRadius: 6,
            }}
            onMouseEnter={e => e.target.style.color = '#fff'}
            onMouseLeave={e => e.target.style.color = '#d1d5db'}
          >
            Sign in
          </button>
          <button onClick={onRegister}
            style={{
              background: '#fff', color: '#0a0a0b', fontSize: 14, fontWeight: 600,
              border: 'none', borderRadius: 8, padding: '8px 18px', cursor: 'pointer',
              transition: 'background 0.15s',
            }}
            onMouseEnter={e => e.target.style.background = '#e5e7eb'}
            onMouseLeave={e => e.target.style.background = '#fff'}
          >
            Get started free
          </button>
        </div>

        {/* Mobile hamburger */}
        <button onClick={() => setOpen(o => !o)}
          style={{ background: 'none', border: 'none', color: '#9ca3af', cursor: 'pointer', display: 'none' }}
          className="show-mobile">
          {open ? <Icon.X /> : <Icon.Menu />}
        </button>
      </div>

      {/* Mobile menu */}
      {open && (
        <div style={{
          borderTop: '1px solid rgba(255,255,255,0.06)',
          padding: '16px 24px 24px',
          display: 'flex', flexDirection: 'column', gap: 12,
        }}>
          {['Features', 'How it works', 'Pricing'].map(l => (
            <a key={l} href={`#${l.toLowerCase().replace(/\s+/g, '-')}`}
              onClick={() => setOpen(false)}
              style={{ color: '#9ca3af', fontSize: 15, textDecoration: 'none' }}>
              {l}
            </a>
          ))}
          <button onClick={onLogin}
            style={{ background: 'rgba(255,255,255,0.06)', color: '#fff', border: 'none', borderRadius: 8, padding: '10px', cursor: 'pointer', fontSize: 15 }}>
            Sign in
          </button>
          <button onClick={onRegister}
            style={{ background: '#fff', color: '#0a0a0b', border: 'none', borderRadius: 8, padding: '10px', cursor: 'pointer', fontSize: 15, fontWeight: 600 }}>
            Get started free
          </button>
        </div>
      )}
    </nav>
  );
}

function MeshCanvas() {
  return (
    <canvas
      ref={el => {
        if (!el || el._init) return;
        el._init = true;
        const W = el.offsetWidth, H = el.offsetHeight;
        el.width = W; el.height = H;
        const ctx = el.getContext('2d');
        const COUNT = 55;
        const nodes = Array.from({ length: COUNT }, () => ({
          x: Math.random() * W, y: Math.random() * H,
          vx: (Math.random() - 0.5) * 0.4,
          vy: (Math.random() - 0.5) * 0.4,
          r: Math.random() * 2 + 1,
          pulse: Math.random() * Math.PI * 2,
        }));
        function draw() {
          ctx.clearRect(0, 0, W, H);
          nodes.forEach(n => {
            n.x += n.vx; n.y += n.vy; n.pulse += 0.02;
            if (n.x < 0 || n.x > W) n.vx *= -1;
            if (n.y < 0 || n.y > H) n.vy *= -1;
          });
          // Draw connections
          for (let i = 0; i < nodes.length; i++) {
            for (let j = i + 1; j < nodes.length; j++) {
              const dx = nodes[i].x - nodes[j].x;
              const dy = nodes[i].y - nodes[j].y;
              const dist = Math.sqrt(dx*dx + dy*dy);
              if (dist < 160) {
                const alpha = (1 - dist / 160) * 0.35;
                ctx.beginPath();
                ctx.moveTo(nodes[i].x, nodes[i].y);
                ctx.lineTo(nodes[j].x, nodes[j].y);
                ctx.strokeStyle = `rgba(99,131,235,${alpha})`;
                ctx.lineWidth = 0.8;
                ctx.stroke();
              }
            }
          }
          // Draw nodes
          nodes.forEach(n => {
            const glow = 0.6 + Math.sin(n.pulse) * 0.4;
            ctx.beginPath();
            ctx.arc(n.x, n.y, n.r * glow, 0, Math.PI * 2);
            ctx.fillStyle = `rgba(147,179,255,${0.5 + glow * 0.3})`;
            ctx.fill();
            // outer ring on some nodes
            if (n.r > 2) {
              ctx.beginPath();
              ctx.arc(n.x, n.y, n.r * 3, 0, Math.PI * 2);
              ctx.strokeStyle = `rgba(99,131,235,${0.12 * glow})`;
              ctx.lineWidth = 1;
              ctx.stroke();
            }
          });
          requestAnimationFrame(draw);
        }
        draw();
      }}
      style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', pointerEvents: 'none' }}
    />
  );
}

function Hero({ onRegister, onLogin }) {
  return (
    <section style={{
      minHeight: '100vh',
      background: 'linear-gradient(160deg, #030712 0%, #0c1445 35%, #0f0a2e 60%, #030712 100%)',
      display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
      padding: '120px 24px 80px', textAlign: 'center',
      position: 'relative', overflow: 'hidden',
    }}>
      {/* Animated mesh background */}
      <MeshCanvas />

      {/* Radial glow behind headline */}
      <div style={{
        position: 'absolute', top: '30%', left: '50%', transform: 'translate(-50%,-50%)',
        width: 700, height: 500,
        background: 'radial-gradient(ellipse at center, rgba(37,99,235,0.22) 0%, rgba(124,58,237,0.1) 40%, transparent 70%)',
        pointerEvents: 'none',
      }} />

      {/* Subtle grid overlay */}
      <div style={{
        position: 'absolute', inset: 0, pointerEvents: 'none',
        backgroundImage: 'linear-gradient(rgba(99,131,235,0.04) 1px, transparent 1px), linear-gradient(90deg, rgba(99,131,235,0.04) 1px, transparent 1px)',
        backgroundSize: '60px 60px',
      }} />
      {/* Bottom fade-to-next-section */}
      <div style={{
        position: 'absolute', bottom: 0, left: 0, right: 0, height: 180,
        background: 'linear-gradient(to bottom, transparent, #08080a)',
        pointerEvents: 'none',
      }} />
      {/* Edge vignette */}
      <div style={{
        position: 'absolute', inset: 0, pointerEvents: 'none',
        background: 'radial-gradient(ellipse at center, transparent 40%, rgba(3,7,18,0.6) 100%)',
      }} />
      {/* Content wrapper — above canvas */}
      <div style={{ position: 'relative', zIndex: 1 }}>
      {/* Badge */}
      <div className="hero-content" style={{
        display: 'inline-flex', alignItems: 'center', gap: 8,
        background: 'rgba(37,99,235,0.15)', border: '1px solid rgba(37,99,235,0.3)',
        borderRadius: 999, padding: '6px 14px', marginBottom: 36,
      }}>
        <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#2563eb', display: 'inline-block', animation: 'pulse 2s infinite' }} />
        <span style={{ color: '#93c5fd', fontSize: 13, fontWeight: 500 }}>
          Now in beta — free for early adopters
        </span>
      </div>

      {/* Headline */}
      <h1 style={{
        fontSize: 'clamp(40px, 7vw, 72px)', fontWeight: 700,
        color: '#fff', lineHeight: 1.08, letterSpacing: '-2px',
        maxWidth: 820, margin: '0 auto 24px',
      }}>
        Your AI decisions,{' '}
        <span style={{
          background: 'linear-gradient(135deg, #60a5fa, #a78bfa)',
          WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent',
        }}>
          finally under control
        </span>
      </h1>

      {/* Subheadline */}
      <p style={{
        fontSize: 'clamp(16px, 2.5vw, 20px)', color: '#9ca3af', lineHeight: 1.65,
        maxWidth: 600, margin: '0 auto 48px', fontWeight: 400,
      }}>
        DecisionMesh is the AI control plane that enforces budgets, governs policies,
        routes across models, and logs every decision — before your costs spiral and your
        compliance team asks questions.
      </p>

      {/* CTAs */}
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', justifyContent: 'center', marginBottom: 64 }}>
        <button onClick={onRegister}
          style={{
            background: '#2563eb', color: '#fff', fontSize: 16, fontWeight: 600,
            border: 'none', borderRadius: 10, padding: '14px 28px', cursor: 'pointer',
            display: 'flex', alignItems: 'center', gap: 8,
            transition: 'background 0.15s, transform 0.1s',
          }}
          onMouseEnter={e => { e.currentTarget.style.background = '#1d4ed8'; e.currentTarget.style.transform = 'translateY(-1px)'; }}
          onMouseLeave={e => { e.currentTarget.style.background = '#2563eb'; e.currentTarget.style.transform = 'none'; }}
        >
          Start for free <Icon.ArrowRight />
        </button>
        <button onClick={onLogin}
          style={{
            background: 'rgba(255,255,255,0.06)', color: '#e5e7eb', fontSize: 16, fontWeight: 500,
            border: '1px solid rgba(255,255,255,0.1)', borderRadius: 10, padding: '14px 28px', cursor: 'pointer',
            transition: 'background 0.15s',
          }}
          onMouseEnter={e => e.currentTarget.style.background = 'rgba(255,255,255,0.1)'}
          onMouseLeave={e => e.currentTarget.style.background = 'rgba(255,255,255,0.06)'}
        >
          Sign in to dashboard
        </button>
      </div>

      </div>{/* /Content wrapper */}

      {/* Dashboard preview mockup */}
      <div style={{
        maxWidth: 900, width: '100%', margin: '0 auto',
        border: '1px solid rgba(255,255,255,0.08)', borderRadius: 16,
        overflow: 'hidden',
        boxShadow: '0 0 80px rgba(37,99,235,0.15), 0 32px 64px rgba(0,0,0,0.5)',
      }}>
        {/* Fake browser chrome */}
        <div style={{ background: '#111113', borderBottom: '1px solid rgba(255,255,255,0.06)', padding: '10px 16px', display: 'flex', alignItems: 'center', gap: 6 }}>
          {['#ff5f57','#febc2e','#28c840'].map(c => (
            <div key={c} style={{ width: 10, height: 10, borderRadius: '50%', background: c }} />
          ))}
          <div style={{ flex: 1, background: 'rgba(255,255,255,0.05)', borderRadius: 6, height: 22, marginLeft: 12, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <span style={{ color: '#6b7280', fontSize: 11 }}>app.decisionmesh.io/dashboard</span>
          </div>
        </div>
        {/* Dashboard preview content */}
        <div style={{ background: '#f8fafc', display: 'flex', minHeight: 360 }}>
          {/* Sidebar strip */}
          <div style={{ width: 52, background: '#fff', borderRight: '1px solid #e2e8f0', padding: '16px 0', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 16 }}>
            {['#2563eb','#94a3b8','#94a3b8','#94a3b8','#94a3b8'].map((c, i) => (
              <div key={i} style={{ width: 22, height: 22, borderRadius: 6, background: i === 0 ? '#eff6ff' : 'transparent', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <div style={{ width: 14, height: 2, background: c, borderRadius: 2 }} />
              </div>
            ))}
          </div>
          {/* Main area */}
          <div style={{ flex: 1, padding: 24 }}>
            {/* Metric cards */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 20 }}>
              {[
                { label: 'Total intents', value: '12,847' },
                { label: 'Total cost', value: '$24.3812' },
                { label: 'Success rate', value: '99.1%' },
                { label: 'Avg latency', value: '842ms' },
              ].map(({ label, value }) => (
                <div key={label} style={{ background: '#fff', border: '1px solid #e2e8f0', borderRadius: 10, padding: '14px 16px' }}>
                  <div style={{ fontSize: 9, color: '#94a3b8', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.5px', marginBottom: 6 }}>{label}</div>
                  <div style={{ fontSize: 18, fontWeight: 700, color: '#0f172a' }}>{value}</div>
                </div>
              ))}
            </div>
            {/* Chart placeholder */}
            <div style={{ background: '#fff', border: '1px solid #e2e8f0', borderRadius: 10, padding: 16, marginBottom: 12 }}>
              <div style={{ fontSize: 11, fontWeight: 600, color: '#475569', marginBottom: 12 }}>Cost over time</div>
              <div style={{ height: 80, display: 'flex', alignItems: 'flex-end', gap: 4 }}>
                {[30,45,38,52,48,65,58,72,55,68,80,75,88,92,85].map((h, i) => (
                  <div key={i} style={{ flex: 1, background: i === 14 ? '#2563eb' : `rgba(37,99,235,${0.15 + (h/100)*0.45})`, borderRadius: '3px 3px 0 0', height: `${h}%`, transition: 'height 0.3s' }} />
                ))}
              </div>
            </div>
            {/* Table rows */}
            {[
              { id: 'a8f3...', type: 'CHAT', phase: 'COMPLETED', cost: '$0.0024' },
              { id: 'b2e1...', type: 'SUMMARIZATION', phase: 'EXECUTING', cost: '$0.0011' },
            ].map(r => (
              <div key={r.id} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 0', borderBottom: '1px solid #f1f5f9' }}>
                <div style={{ fontFamily: 'monospace', fontSize: 11, color: '#94a3b8', background: '#f1f5f9', padding: '2px 6px', borderRadius: 4 }}>{r.id}</div>
                <div style={{ fontSize: 11, fontWeight: 500, color: '#334155', flex: 1 }}>{r.type}</div>
                <div style={{ fontSize: 10, fontWeight: 600, padding: '2px 8px', borderRadius: 999, background: r.phase === 'EXECUTING' ? '#fef3c7' : '#dcfce7', color: r.phase === 'EXECUTING' ? '#92400e' : '#166534' }}>{r.phase}</div>
                <div style={{ fontSize: 11, fontFamily: 'monospace', color: '#475569' }}>{r.cost}</div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}

function PainSection() {
  return (
    <section style={{ background: '#08080a', padding: '80px 24px' }}>
      <div style={{ maxWidth: 1000, margin: '0 auto', textAlign: 'center' }}>
        <h2 style={{ fontSize: 'clamp(28px, 5vw, 44px)', fontWeight: 700, color: '#fff', letterSpacing: '-1px', marginBottom: 16 }}>
          AI without governance is a liability
        </h2>
        <p style={{ color: '#6b7280', fontSize: 17, marginBottom: 56 }}>
          Every team shipping AI faces the same problems. Here are the most expensive ones.
        </p>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 20 }}>
          {PAIN_POINTS.map(({ emoji, title, desc }) => (
            <div key={title} style={{
              background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.07)',
              borderRadius: 14, padding: '32px 28px', textAlign: 'left',
            }}>
              <div style={{ fontSize: 36, marginBottom: 16 }}>{emoji}</div>
              <h3 style={{ color: '#fff', fontWeight: 600, fontSize: 18, marginBottom: 10 }}>{title}</h3>
              <p style={{ color: '#6b7280', fontSize: 15, lineHeight: 1.65 }}>{desc}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function HowItWorks() {
  return (
    <section id="how-it-works" style={{ background: '#0d0d10', padding: '80px 24px' }}>
      <div style={{ maxWidth: 1000, margin: '0 auto', textAlign: 'center' }}>
        <p style={{ color: '#2563eb', fontSize: 13, fontWeight: 600, letterSpacing: '1px', textTransform: 'uppercase', marginBottom: 12 }}>How it works</p>
        <h2 style={{ fontSize: 'clamp(28px, 5vw, 44px)', fontWeight: 700, color: '#fff', letterSpacing: '-1px', marginBottom: 60 }}>
          Three steps to AI you can trust
        </h2>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 0 }}>
          {HOW_IT_WORKS.map(({ step, title, desc }, i) => (
            <div key={step} style={{
              padding: '40px 32px', textAlign: 'left',
              borderRight: i < HOW_IT_WORKS.length - 1 ? '1px solid rgba(255,255,255,0.06)' : 'none',
              position: 'relative',
            }}>
              <div style={{
                fontSize: 48, fontWeight: 800, color: 'rgba(37,99,235,0.15)',
                letterSpacing: '-2px', marginBottom: 20, lineHeight: 1,
              }}>{step}</div>
              <h3 style={{ color: '#fff', fontWeight: 600, fontSize: 20, marginBottom: 12 }}>{title}</h3>
              <p style={{ color: '#6b7280', fontSize: 15, lineHeight: 1.65 }}>{desc}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function Features() {
  return (
    <section id="features" style={{ background: '#fff', padding: '80px 24px' }}>
      <div style={{ maxWidth: 1080, margin: '0 auto', textAlign: 'center' }}>
        <p style={{ color: '#2563eb', fontSize: 13, fontWeight: 600, letterSpacing: '1px', textTransform: 'uppercase', marginBottom: 12 }}>Features</p>
        <h2 style={{ fontSize: 'clamp(28px, 5vw, 44px)', fontWeight: 700, color: '#0f172a', letterSpacing: '-1px', marginBottom: 16 }}>
          Everything your AI needs to behave
        </h2>
        <p style={{ color: '#64748b', fontSize: 17, marginBottom: 56, maxWidth: 520, margin: '0 auto 56px' }}>
          Built for production — not prototypes.
        </p>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 20 }}>
          {FEATURES.map(({ icon, title, desc, color, badge }) => {
            const IconComponent = Icon[icon];
            return (
              <div key={title} style={{
                background: '#f8fafc', border: '1px solid #e2e8f0',
                borderRadius: 14, padding: '28px', textAlign: 'left',
                transition: 'box-shadow 0.2s, transform 0.2s',
              }}
                onMouseEnter={e => { e.currentTarget.style.boxShadow = '0 8px 30px rgba(0,0,0,0.08)'; e.currentTarget.style.transform = 'translateY(-2px)'; }}
                onMouseLeave={e => { e.currentTarget.style.boxShadow = 'none'; e.currentTarget.style.transform = 'none'; }}
              >
                <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 18 }}>
                  <div style={{
                    width: 44, height: 44, borderRadius: 10,
                    background: `${color}18`, display: 'flex', alignItems: 'center', justifyContent: 'center', color,
                  }}>
                    <IconComponent />
                  </div>
                  {badge && (
                    <span style={{
                      fontSize: 9, fontWeight: 700, letterSpacing: '0.8px',
                      color, background: `${color}18`, border: `1px solid ${color}40`,
                      borderRadius: 4, padding: '2px 7px',
                    }}>{badge}</span>
                  )}
                </div>
                <h3 style={{ color: '#0f172a', fontWeight: 600, fontSize: 17, marginBottom: 10 }}>{title}</h3>
                <p style={{ color: '#64748b', fontSize: 14, lineHeight: 1.65 }}>{desc}</p>
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}



function AudienceSection() {
  const audiences = [
    {
      role: 'For engineers',
      color: '#3b82f6',
      bg: 'rgba(59,130,246,0.08)',
      border: 'rgba(59,130,246,0.2)',
      headline: 'Build AI features without worrying about governance',
      points: [
        'Submit intents via REST API — one endpoint, full control plane',
        'Automatic retry, fallback, and model routing out of the box',
        'Real-time execution timeline for every intent',
        'SDK-ready event stream via Kafka or webhooks',
      ],
    },
    {
      role: 'For compliance teams',
      color: '#f43f5e',
      bg: 'rgba(244,63,94,0.08)',
      border: 'rgba(244,63,94,0.2)',
      headline: 'Every AI decision is explainable, exportable, and auditable',
      points: [
        'Immutable, signed audit log — tamper-proof by design',
        'PII detection and masking before data reaches any model',
        'GDPR data residency controls per tenant',
        'One-click CSV export for auditors and regulators',
      ],
    },
    {
      role: 'For executives',
      color: '#10b981',
      bg: 'rgba(16,185,129,0.08)',
      border: 'rgba(16,185,129,0.2)',
      headline: 'Full AI spend visibility and risk control across the organisation',
      points: [
        'Real-time cost dashboard — know what AI is spending today',
        'Policy engine prevents budget overruns before they happen',
        'Risk exposure scores across all AI decisions',
        'Multi-tenant isolation — each team, product, or client is separate',
      ],
    },
  ];

  return (
    <section style={{ background: '#08080a', padding: '80px 24px', borderTop: '1px solid rgba(255,255,255,0.06)' }}>
      <div style={{ maxWidth: 1080, margin: '0 auto', textAlign: 'center' }}>
        <p style={{ color: '#2563eb', fontSize: 13, fontWeight: 600, letterSpacing: '1px', textTransform: 'uppercase', marginBottom: 12 }}>Built for every stakeholder</p>
        <h2 style={{ fontSize: 'clamp(28px, 5vw, 44px)', fontWeight: 700, color: '#fff', letterSpacing: '-1px', marginBottom: 16 }}>
          One platform, three audiences
        </h2>
        <p style={{ color: '#6b7280', fontSize: 17, marginBottom: 56, maxWidth: 540, margin: '0 auto 56px' }}>
          Engineers ship faster. Compliance teams sleep easier. Executives see everything.
        </p>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: 20, textAlign: 'left' }}>
          {audiences.map(({ role, color, bg, border, headline, points }) => (
            <div key={role} style={{
              background: bg, border: `1px solid ${border}`,
              borderRadius: 16, padding: 32,
            }}>
              <div style={{
                display: 'inline-flex', alignItems: 'center', gap: 6,
                background: `${color}18`, border: `1px solid ${color}30`,
                borderRadius: 999, padding: '4px 12px', marginBottom: 20,
              }}>
                <div style={{ width: 6, height: 6, borderRadius: '50%', background: color }} />
                <span style={{ color, fontSize: 12, fontWeight: 600 }}>{role}</span>
              </div>
              <h3 style={{ color: '#fff', fontWeight: 600, fontSize: 18, lineHeight: 1.4, marginBottom: 20 }}>
                {headline}
              </h3>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {points.map(p => (
                  <div key={p} style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
                    <div style={{ width: 16, height: 16, borderRadius: '50%', background: `${color}20`, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, marginTop: 1 }}>
                      <svg width="8" height="8" viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="20 6 9 17 4 12"/>
                      </svg>
                    </div>
                    <span style={{ color: '#9ca3af', fontSize: 14, lineHeight: 1.55 }}>{p}</span>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function ReplayAudit({ onRegister }) {
  return (
    <section style={{ background: '#0d0d10', padding: '80px 24px', borderTop: '1px solid rgba(255,255,255,0.06)' }}>
      <div style={{ maxWidth: 1080, margin: '0 auto' }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(440px, 1fr))', gap: 40, alignItems: 'center' }}>

          {/* Decision Replay */}
          <div>
            <div style={{
              display: 'inline-flex', alignItems: 'center', gap: 8,
              background: 'rgba(16,185,129,0.1)', border: '1px solid rgba(16,185,129,0.2)',
              borderRadius: 999, padding: '5px 12px', marginBottom: 20,
            }}>
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#10b981" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 .49-3.5"/>
              </svg>
              <span style={{ color: '#10b981', fontSize: 12, fontWeight: 600 }}>Decision Replay</span>
            </div>
            <h2 style={{ fontSize: 'clamp(26px, 4vw, 38px)', fontWeight: 700, color: '#fff', letterSpacing: '-1px', lineHeight: 1.15, marginBottom: 16 }}>
              Re-run any AI decision,{' '}
              <span style={{ background: 'linear-gradient(135deg, #10b981, #06b6d4)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent' }}>
                instantly
              </span>
            </h2>
            <p style={{ color: '#6b7280', fontSize: 16, lineHeight: 1.7, marginBottom: 28, maxWidth: 440 }}>
              Every intent is stored with its full context — objective, constraints, adapter, budget. 
              Click replay and it runs again. Compare outputs across models, debug failures from last 
              week, or build regression test suites from production history.
            </p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 32 }}>
              {[
                'Replay any past intent with one click',
                'Compare outputs across different adapters',
                'Build regression tests from production history',
                'Debug failures with full execution context',
              ].map(item => (
                <div key={item} style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <div style={{ width: 18, height: 18, borderRadius: '50%', background: 'rgba(16,185,129,0.15)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                    <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="#10b981" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                      <polyline points="20 6 9 17 4 12"/>
                    </svg>
                  </div>
                  <span style={{ color: '#d1d5db', fontSize: 14 }}>{item}</span>
                </div>
              ))}
            </div>
          </div>

          {/* Replay UI mockup */}
          <div style={{
            background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)',
            borderRadius: 16, overflow: 'hidden',
          }}>
            <div style={{ background: 'rgba(255,255,255,0.04)', borderBottom: '1px solid rgba(255,255,255,0.06)', padding: '12px 16px', display: 'flex', alignItems: 'center', gap: 8 }}>
              <div style={{ fontSize: 11, color: '#6b7280', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.5px' }}>Intent history</div>
            </div>
            <div style={{ padding: '8px 0' }}>
              {[
                { id: 'a8f3d1', type: 'SUMMARIZATION', cost: '$0.0024', status: 'SATISFIED', time: '2h ago', active: true },
                { id: 'b2e9c4', type: 'CHAT', cost: '$0.0008', status: 'SATISFIED', time: '5h ago', active: false },
                { id: 'c7f2a8', type: 'CLASSIFICATION', cost: '$0.0031', status: 'VIOLATED', time: '1d ago', active: false },
              ].map(r => (
                <div key={r.id} style={{
                  display: 'flex', alignItems: 'center', gap: 12, padding: '10px 16px',
                  background: r.active ? 'rgba(16,185,129,0.05)' : 'transparent',
                  borderLeft: r.active ? '2px solid #10b981' : '2px solid transparent',
                  transition: 'background 0.2s',
                }}>
                  <div style={{ fontFamily: 'monospace', fontSize: 11, color: '#6b7280', background: 'rgba(255,255,255,0.06)', padding: '2px 6px', borderRadius: 4 }}>{r.id}</div>
                  <div style={{ fontSize: 11, color: '#9ca3af', flex: 1 }}>{r.type}</div>
                  <div style={{ fontSize: 10, padding: '2px 8px', borderRadius: 999, fontWeight: 600,
                    background: r.status === 'SATISFIED' ? 'rgba(34,197,94,0.12)' : 'rgba(239,68,68,0.12)',
                    color: r.status === 'SATISFIED' ? '#22c55e' : '#ef4444',
                  }}>{r.status}</div>
                  <div style={{ fontSize: 11, fontFamily: 'monospace', color: '#6b7280' }}>{r.cost}</div>
                  <div style={{ fontSize: 10, color: '#4b5563' }}>{r.time}</div>
                  <div style={{
                    display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, fontWeight: 600,
                    color: r.active ? '#10b981' : '#374151',
                    background: r.active ? 'rgba(16,185,129,0.12)' : 'rgba(255,255,255,0.06)',
                    border: r.active ? '1px solid rgba(16,185,129,0.25)' : '1px solid rgba(255,255,255,0.08)',
                    borderRadius: 6, padding: '3px 8px', cursor: 'pointer',
                  }}>
                    <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                      <polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 .49-3.5"/>
                    </svg>
                    {r.active ? 'Replaying…' : 'Replay'}
                  </div>
                </div>
              ))}
            </div>
            {/* Replay diff preview */}
            <div style={{ margin: '0 16px 16px', background: 'rgba(16,185,129,0.04)', border: '1px solid rgba(16,185,129,0.15)', borderRadius: 10, padding: 14 }}>
              <div style={{ fontSize: 10, color: '#10b981', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.5px', marginBottom: 10 }}>Replay result — a8f3d1</div>
              <div style={{ display: 'flex', gap: 16 }}>
                {[{ label: 'Original', cost: '$0.0024', latency: '1,240ms', model: 'gpt-4o' }, { label: 'Replayed', cost: '$0.0019', latency: '890ms', model: 'claude-3-5' }].map(col => (
                  <div key={col.label} style={{ flex: 1 }}>
                    <div style={{ fontSize: 10, color: '#6b7280', marginBottom: 6 }}>{col.label}</div>
                    <div style={{ fontSize: 11, color: '#d1d5db', marginBottom: 3 }}>Model: <span style={{ color: '#9ca3af', fontFamily: 'monospace' }}>{col.model}</span></div>
                    <div style={{ fontSize: 11, color: '#d1d5db', marginBottom: 3 }}>Cost: <span style={{ color: col.label === 'Replayed' ? '#10b981' : '#9ca3af', fontFamily: 'monospace' }}>{col.cost}</span></div>
                    <div style={{ fontSize: 11, color: '#d1d5db' }}>Latency: <span style={{ color: col.label === 'Replayed' ? '#10b981' : '#9ca3af', fontFamily: 'monospace' }}>{col.latency}</span></div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>

        {/* Divider */}
        <div style={{ height: 1, background: 'rgba(255,255,255,0.06)', margin: '72px 0' }} />

        {/* Audit Trail */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(440px, 1fr))', gap: 40, alignItems: 'center' }}>

          {/* Audit UI mockup — left on this row */}
          <div style={{
            background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)',
            borderRadius: 16, overflow: 'hidden',
          }}>
            <div style={{ background: 'rgba(255,255,255,0.04)', borderBottom: '1px solid rgba(255,255,255,0.06)', padding: '12px 16px' }}>
              <div style={{ fontSize: 11, color: '#6b7280', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.5px' }}>Audit log</div>
            </div>
            <div style={{ padding: '8px 0' }}>
              {[
                { action: 'intent.created',  user: 'alice@corp.com', entity: 'CHAT', time: '09:41:02', color: '#3b82f6' },
                { action: 'policy.blocked',  user: 'system',         entity: 'cost > $0.01', time: '09:41:03', color: '#f59e0b' },
                { action: 'adapter.fallback',user: 'system',         entity: 'gpt-4o → claude', time: '09:41:04', color: '#a855f7' },
                { action: 'intent.completed',user: 'system',         entity: 'SATISFIED', time: '09:41:06', color: '#22c55e' },
                { action: 'key.revoked',     user: 'bob@corp.com',   entity: 'sk-live-***', time: '09:40:01', color: '#ef4444' },
              ].map((e, i) => (
                <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '9px 16px', borderBottom: '1px solid rgba(255,255,255,0.04)' }}>
                  <div style={{ width: 6, height: 6, borderRadius: '50%', background: e.color, flexShrink: 0 }} />
                  <div style={{ fontFamily: 'monospace', fontSize: 11, color: '#9ca3af', flex: 1 }}>{e.action}</div>
                  <div style={{ fontSize: 11, color: '#6b7280', maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{e.user}</div>
                  <div style={{ fontSize: 10, color: '#4b5563', fontFamily: 'monospace' }}>{e.time}</div>
                </div>
              ))}
            </div>
            <div style={{ padding: '10px 16px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div style={{ fontSize: 11, color: '#4b5563' }}>Showing 5 of 48,291 events</div>
              <div style={{ fontSize: 11, color: '#f43f5e', fontWeight: 600, display: 'flex', alignItems: 'center', gap: 4 }}>
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><path d="M8 21h12a2 2 0 0 0 2-2v-2H10v2a2 2 0 1 1-4 0V5a2 2 0 1 0-4 0v3h4"/><path d="M19 17V5a2 2 0 0 0-2-2H4"/></svg>
                Export CSV
              </div>
            </div>
          </div>

          {/* Audit copy — right */}
          <div>
            <div style={{
              display: 'inline-flex', alignItems: 'center', gap: 8,
              background: 'rgba(244,63,94,0.1)', border: '1px solid rgba(244,63,94,0.2)',
              borderRadius: 999, padding: '5px 12px', marginBottom: 20,
            }}>
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#f43f5e" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="M8 21h12a2 2 0 0 0 2-2v-2H10v2a2 2 0 1 1-4 0V5a2 2 0 1 0-4 0v3h4"/><path d="M19 17V5a2 2 0 0 0-2-2H4"/><line x1="13" y1="9" x2="19" y2="9"/><line x1="13" y1="13" x2="19" y2="13"/>
              </svg>
              <span style={{ color: '#f43f5e', fontSize: 12, fontWeight: 600 }}>Compliance-ready audit trail</span>
            </div>
            <h2 style={{ fontSize: 'clamp(26px, 4vw, 38px)', fontWeight: 700, color: '#fff', letterSpacing: '-1px', lineHeight: 1.15, marginBottom: 16 }}>
              Every AI decision,{' '}
              <span style={{ background: 'linear-gradient(135deg, #f43f5e, #f59e0b)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent' }}>
                permanently logged
              </span>
            </h2>
            <p style={{ color: '#6b7280', fontSize: 16, lineHeight: 1.7, marginBottom: 28, maxWidth: 440 }}>
              Every intent, policy evaluation, adapter call, and cost is recorded with full context. 
              Filter by user, action, or entity. Export for compliance reviews. 
              Answer "what happened and why" in seconds — not hours.
            </p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 32 }}>
              {[
                'Who triggered every AI decision',
                'Which model was used and what it cost',
                'Which policies blocked or modified execution',
                'Full export for SOC 2, GDPR, and internal audits',
              ].map(item => (
                <div key={item} style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <div style={{ width: 18, height: 18, borderRadius: '50%', background: 'rgba(244,63,94,0.12)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                    <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="#f43f5e" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                      <polyline points="20 6 9 17 4 12"/>
                    </svg>
                  </div>
                  <span style={{ color: '#d1d5db', fontSize: 14 }}>{item}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

function Stats() {
  return (
    <section style={{ background: '#0d0d10', padding: '60px 24px', borderTop: '1px solid rgba(255,255,255,0.06)', borderBottom: '1px solid rgba(255,255,255,0.06)' }}>
      <div style={{ maxWidth: 900, margin: '0 auto', display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 40, textAlign: 'center' }}>
        {STATS.map(({ value, label }) => (
          <div key={label}>
            <div style={{ fontSize: 36, fontWeight: 700, color: '#fff', letterSpacing: '-1px', marginBottom: 6 }}>{value}</div>
            <div style={{ color: '#6b7280', fontSize: 14 }}>{label}</div>
          </div>
        ))}
      </div>
    </section>
  );
}

function Pricing({ onRegister }) {
  return (
    <section id="pricing" style={{ background: '#08080a', padding: '80px 24px' }}>
      <div style={{ maxWidth: 1240, margin: '0 auto', textAlign: 'center' }}>
        <p style={{ color: '#2563eb', fontSize: 13, fontWeight: 600, letterSpacing: '1px', textTransform: 'uppercase', marginBottom: 12 }}>Pricing</p>
        <h2 style={{ fontSize: 'clamp(28px, 5vw, 44px)', fontWeight: 700, color: '#fff', letterSpacing: '-1px', marginBottom: 16 }}>
          Simple, transparent pricing
        </h2>
        <p style={{ color: '#6b7280', fontSize: 17, marginBottom: 56 }}>Start free. Scale when you're ready.</p>

        {/* 5-column grid — matches billing page exactly */}
        <div style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
          gap: 16,
          alignItems: 'start',
        }}>
          {PLANS.map(plan => (
            <div key={plan.key} style={{
              background: plan.bg,
              border: `1px solid ${plan.border}`,
              borderRadius: 16,
              padding: '28px 24px',
              textAlign: 'left',
              position: 'relative',
              overflow: 'hidden',
              display: 'flex',
              flexDirection: 'column',
            }}>
              {/* Top accent bar — Enterprise only */}
              {plan.topBar && (
                <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: 2, background: plan.topBar }} />
              )}

              {/* Most popular badge — Builder */}
              {plan.popular && (
                <div style={{
                  position: 'absolute', top: -1, left: '50%', transform: 'translateX(-50%)',
                  background: '#2563eb', color: '#fff', fontSize: 10, fontWeight: 700,
                  padding: '4px 12px', borderRadius: '0 0 8px 8px', letterSpacing: '0.5px',
                  whiteSpace: 'nowrap',
                }}>★ Most popular</div>
              )}

              {/* Plan name + dot */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6, marginTop: plan.popular ? 12 : 0 }}>
                <div style={{ width: 8, height: 8, borderRadius: '50%', background: plan.color, flexShrink: 0 }} />
                <p style={{ color: plan.color, fontSize: 13, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.5px' }}>
                  {plan.name}
                </p>
              </div>

              {/* Price */}
              <div style={{ marginBottom: 4 }}>
                <span style={{ fontSize: 40, fontWeight: 700, color: '#fff', letterSpacing: '-2px', lineHeight: 1 }}>
                  {plan.price}
                </span>
                {plan.interval && (
                  <span style={{ color: '#6b7280', fontSize: 13, marginLeft: 2 }}>{plan.interval}</span>
                )}
              </div>

              {/* Credits note */}
              <p style={{ color: plan.popular ? '#60a5fa' : plan.key === 'pro' ? '#818cf8' : '#6b7280', fontSize: 12, fontWeight: 600, marginBottom: 20 }}>
                {plan.note}
              </p>

              {/* Feature list */}
              <div style={{ flex: 1, marginBottom: 24 }}>
                {plan.features.map(f => (
                  <div key={f} style={{ display: 'flex', alignItems: 'flex-start', gap: 8, marginBottom: 10 }}>
                    <div style={{ color: plan.checkColor, flexShrink: 0, marginTop: 1 }}><Icon.Check /></div>
                    <span style={{ color: '#d1d5db', fontSize: 13, lineHeight: 1.4 }}>{f}</span>
                  </div>
                ))}
              </div>

              {/* CTA button */}
              {plan.ctaHref ? (
                <a href={plan.ctaHref}
                  style={{
                    display: 'block', textAlign: 'center', borderRadius: 10,
                    padding: '11px', fontSize: 14, fontWeight: 600,
                    textDecoration: 'none', transition: 'background 0.15s',
                    ...plan.ctaStyle,
                  }}
                  onMouseEnter={e => e.currentTarget.style.background = plan.ctaHover}
                  onMouseLeave={e => e.currentTarget.style.background = plan.ctaStyle.background}
                >
                  {plan.cta}
                </a>
              ) : (
                <button onClick={onRegister}
                  style={{
                    width: '100%', borderRadius: 10, padding: '11px',
                    fontSize: 14, fontWeight: 600, cursor: 'pointer',
                    transition: 'background 0.15s',
                    ...plan.ctaStyle,
                  }}
                  onMouseEnter={e => e.currentTarget.style.background = plan.ctaHover}
                  onMouseLeave={e => e.currentTarget.style.background = plan.ctaStyle.background}
                >
                  {plan.cta}
                </button>
              )}
            </div>
          ))}
        </div>

        <p style={{ color: '#4b5563', fontSize: 13, marginTop: 32 }}>
          Payments processed securely by Stripe · Cancel anytime · No hidden fees
        </p>
      </div>
    </section>
  );
}

function FinalCTA({ onRegister }) {
  return (
    <section style={{
      background: 'linear-gradient(135deg, #1e3a8a 0%, #1e1b4b 50%, #0f172a 100%)',
      padding: '80px 24px', textAlign: 'center',
    }}>
      <div style={{ maxWidth: 600, margin: '0 auto' }}>
        <h2 style={{ fontSize: 'clamp(28px, 5vw, 44px)', fontWeight: 700, color: '#fff', letterSpacing: '-1px', marginBottom: 16 }}>
          Ship AI you can explain
        </h2>
        <p style={{ color: '#93c5fd', fontSize: 18, marginBottom: 40, lineHeight: 1.6 }}>
          Join teams who've stopped guessing and started governing their AI.
        </p>
        <button onClick={onRegister}
          style={{
            background: '#fff', color: '#0f172a', fontSize: 17, fontWeight: 700,
            border: 'none', borderRadius: 10, padding: '16px 36px', cursor: 'pointer',
            display: 'inline-flex', alignItems: 'center', gap: 10,
            transition: 'transform 0.1s, background 0.15s',
          }}
          onMouseEnter={e => { e.currentTarget.style.transform = 'translateY(-2px)'; e.currentTarget.style.background = '#f1f5f9'; }}
          onMouseLeave={e => { e.currentTarget.style.transform = 'none'; e.currentTarget.style.background = '#fff'; }}
        >
          Create your free account <Icon.ArrowRight />
        </button>
        <p style={{ color: '#4b5563', fontSize: 13, marginTop: 16 }}>No credit card required.</p>
      </div>
    </section>
  );
}

function Footer() {
  return (
    <footer style={{ background: '#08080a', borderTop: '1px solid rgba(255,255,255,0.06)', padding: '40px 24px' }}>
      <div style={{ maxWidth: 1000, margin: '0 auto', display: 'flex', flexWrap: 'wrap', gap: 24, alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{ width: 24, height: 24, borderRadius: 6, background: 'linear-gradient(135deg, #2563eb, #7c3aed)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <svg width="12" height="12" viewBox="0 0 24 24" fill="white"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
          </div>
          <span style={{ color: '#9ca3af', fontSize: 14 }}>© 2025 DecisionMesh. All rights reserved.</span>
        </div>
        <div style={{ display: 'flex', gap: 24 }}>
          {['Privacy', 'Terms', 'Docs', 'Status'].map(l => (
            <a key={l} href="#" style={{ color: '#6b7280', fontSize: 14, textDecoration: 'none' }}
              onMouseEnter={e => e.target.style.color = '#9ca3af'}
              onMouseLeave={e => e.target.style.color = '#6b7280'}
            >{l}</a>
          ))}
        </div>
      </div>
    </footer>
  );
}

// ── Main export ───────────────────────────────────────────────────────────────
export default function LandingPage() {
  const auth = useAuth();

  function handleRegister() {
    // Zitadel uses prompt=create to show registration form
    auth.signinRedirect({ extraQueryParams: { prompt: 'create' } });
  }

  function handleLogin() {
    auth.signinRedirect();
  }

  return (
    <>
      <style>{`
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: 'Inter', system-ui, sans-serif; }
        @keyframes pulse {
          0%, 100% { opacity: 1; }
          50% { opacity: 0.4; }
        }
        @keyframes fadeUp {
          from { opacity: 0; transform: translateY(20px); }
          to   { opacity: 1; transform: translateY(0); }
        }
        .hero-content { animation: fadeUp 0.9s ease both; }
        .hero-content-delay { animation: fadeUp 0.9s ease 0.15s both; }
        .hero-content-delay2 { animation: fadeUp 0.9s ease 0.3s both; }
        @media (max-width: 640px) {
          .hidden-mobile { display: none !important; }
          .show-mobile { display: block !important; }
        }
        @media (min-width: 641px) {
          .show-mobile { display: none !important; }
        }
      `}</style>
      <div style={{ minHeight: '100vh', background: '#08080a' }}>
        <NavBar onLogin={handleLogin} onRegister={handleRegister} />
        <Hero onRegister={handleRegister} onLogin={handleLogin} />
        <PainSection />
        <HowItWorks />
        <Features />
        <AudienceSection />
        <ReplayAudit onRegister={handleRegister} />
        <Stats />
        <Pricing onRegister={handleRegister} />
        <FinalCTA onRegister={handleRegister} />
        <Footer />
      </div>
    </>
  );
}
