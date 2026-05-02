/**
 * Playground.jsx — DecisionMesh Control Plane Test Bench
 *
 * Purpose: test budget enforcement, policy rules, adapter routing
 *          and execution governance — NOT document extraction.
 *
 * Document extraction / BYOM intelligence is an adapter concern.
 * Users who want extraction connect their own adapter (BYOK/BYOM)
 * and submit via the standard intent pipeline.
 */
import { useState } from 'react';
import {
  Send, RefreshCw, Copy, ExternalLink, Zap,
  Shield, Clock, RotateCcw, BookOpen, Key, Cpu,
  ChevronDown, ChevronUp, AlertTriangle,
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { v4 as uuidv4 } from 'uuid';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Button } from '../components/shared';
import ExecutionTimeline from '../components/timeline/ExecutionTimeline';
import { submitIntent } from '../utils/api';
import { useCredits, MODEL_TIERS } from '../context/CreditContext';

// ── Intent type suggestions ───────────────────────────────────────────────────
// intentType is a free-form string in the backend — no enum validation.
// These are common suggestions only. Users can type anything.
const INTENT_SUGGESTIONS = [
  { id: 'chat',              label: 'Chat',              category: 'General'  },
  { id: 'summarization',     label: 'Summarization',     category: 'General'  },
  { id: 'classification',    label: 'Classification',    category: 'General'  },
  { id: 'sentiment_analysis',label: 'Sentiment analysis',category: 'General'  },
  { id: 'fraud_detection',   label: 'Fraud detection',   category: 'Fintech'  },
  { id: 'compliance_check',  label: 'Compliance check',  category: 'Fintech'  },
  { id: 'custom',            label: 'Custom',            category: 'General'  },
];

const INTENT_CATEGORIES = ['All', 'General', 'Fintech'];

// ── Default payload — control plane focused ───────────────────────────────────
// Shows budget ceiling, policy rules, and constraints — the actual product.
const DEFAULT = JSON.stringify({
  intentType: 'chat',
  objective: {
    description: 'Answer the user question accurately and concisely',
  },
  constraints: {
    maxRetries:    3,
    timeoutSeconds: 30,
    maxLatencyMs:  2000,
  },
  budget: {
    ceilingUsd: 0.05,
    currency:   'USD',
  },
  policy: {
    allowedModels:      ['gpt-4o-mini', 'claude-haiku-3'],
    blockTopics:        [],
    requireHumanReview: false,
  },
}, null, 2);

// ── Example intents — SATISFIED and VIOLATED ─────────────────────────────────
// These two payloads populate every card on the IntentDetail page.
// SATISFIED: all constraints are achievable — intent completes cleanly.
// VIOLATED:  budget ceiling is set below realistic cost — will be exceeded.

const EXAMPLE_SATISFIED = {
  intentType: 'fraud_detection',
  objective: {
    description:     'Analyse this payment transaction for fraud signals and return a risk score with reasoning',
    fintechCategory: 'FRAUD',
    riskLevel:       'HIGH',
    sourceSystem:    'payment-gateway-v3',
    transactionRef:  'TXN-20260420-084521',
  },
  constraints: {
    maxRetries:          2,
    timeoutSeconds:      30,
    maxLatencyMs:        2000,
    maxDriftThreshold:   0.20,
  },
  budget: {
    ceilingUsd: 0.50,
    currency:   'USD',
  },
  policy: {
    allowedModels:      ['gpt-4o-mini', 'claude-haiku-3'],
    blockTopics:        ['personal_data_retention'],
    requireHumanReview: false,
    auditLevel:         'STANDARD',
    driftThreshold:     0.20,
    alertOnDrift:       true,
  },
};

const EXAMPLE_VIOLATED = {
  intentType: 'fraud_detection',
  objective: {
    description:     'Analyse this payment transaction for fraud signals and return a risk score with reasoning',
    fintechCategory: 'FRAUD',
    riskLevel:       'HIGH',
    sourceSystem:    'payment-gateway-v3',
    transactionRef:  'TXN-20260420-084521',
  },
  constraints: {
    maxRetries:          0,
    timeoutSeconds:      1,
    maxLatencyMs:        50,
    maxDriftThreshold:   0.01,
  },
  budget: {
    ceilingUsd: 0.00001,
    currency:   'USD',
  },
  policy: {
    allowedModels:      ['gpt-4o-mini'],
    blockTopics:        ['personal_data_retention', 'fraud', 'risk', 'transaction'],
    requireHumanReview: false,
    auditLevel:         'IMMUTABLE',
    driftThreshold:     0.01,
    alertOnDrift:       true,
  },
};

// ── Payload templates ─────────────────────────────────────────────────────────
const TEMPLATES = {
  budget_enforcement: {
    label: 'Budget enforcement',
    icon:  '💰',
    description: 'Hard ceiling — intent fails rather than overruns budget',
    payload: {
      intentType: 'chat',
      objective:  { description: 'Summarise the quarterly earnings report' },
      constraints:{ maxRetries: 2, timeoutSeconds: 15 },
      budget:     { ceilingUsd: 0.01, currency: 'USD' },
      policy:     { allowedModels: ['gpt-4o-mini', 'claude-haiku-3'] },
    },
  },
  policy_block: {
    label: 'Policy + topic block',
    icon:  '🛡️',
    description: 'Blocks response if blocked topic is detected in output',
    payload: {
      intentType: 'chat',
      objective:  { description: 'Help the user with their account query' },
      constraints:{ maxRetries: 3, timeoutSeconds: 30 },
      budget:     { ceilingUsd: 0.05, currency: 'USD' },
      policy: {
        allowedModels:      ['gpt-4o-mini'],
        blockTopics:        ['competitor_products', 'pricing_promises'],
        requireHumanReview: false,
        driftThreshold:     0.15,
      },
    },
  },
  fraud_signal: {
    label: 'Fraud detection',
    icon:  '🔍',
    description: 'High-stakes intent — routes to premium, flags for review',
    payload: {
      intentType: 'fraud_detection',
      objective: {
        description:     'Analyse transaction pattern for fraud signals',
        fintechCategory: 'FRAUD',
        riskLevel:       'HIGH',
      },
      constraints:{ maxRetries: 1, timeoutSeconds: 6, maxLatencyMs: 400 },
      budget:     { ceilingUsd: 0.20, currency: 'USD' },
      policy: {
        requireHumanReview: true,
        auditLevel:         'IMMUTABLE',
        alertOnDrift:       true,
      },
    },
  },
  hitl_gate: {
    label: 'Human-in-the-loop',
    icon:  '👤',
    description: 'Pauses execution for human approval before completing',
    payload: {
      intentType: 'compliance_check',
      objective: {
        description:     'Review loan application for regulatory compliance',
        fintechCategory: 'COMPLIANCE',
      },
      constraints:{ maxRetries: 0, timeoutSeconds: 300 },
      budget:     { ceilingUsd: 0.50, currency: 'USD' },
      policy: {
        requireHumanReview: true,
        humanReviewTimeout: 3600,
        escalationEmail:    'compliance@yourcompany.com',
      },
    },
  },
  byok_routing: {
    label: 'BYOK routing',
    icon:  '🔑',
    description: 'Routes to your own API key — 1 credit orchestration only',
    payload: {
      intentType: 'chat',
      objective:  { description: 'Process using my Anthropic contract key' },
      constraints:{ maxRetries: 3, timeoutSeconds: 30 },
      budget:     { ceilingUsd: 0.10, currency: 'USD' },
      adapter: {
        type:     'byok',
        provider: 'anthropic',
        model:    'claude-haiku-3',
      },
    },
  },
  byom_routing: {
    label: 'BYOM routing',
    icon:  '⚙️',
    description: 'Routes to your self-hosted model — zero data egress',
    payload: {
      intentType: 'classification',
      objective:  { description: 'Classify document using on-prem model' },
      constraints:{ maxRetries: 2, timeoutSeconds: 15, maxLatencyMs: 500 },
      budget:     { ceilingUsd: 0.01, currency: 'USD' },
      adapter: {
        type:         'byom',
        endpointName: 'my-layoutlmv3-endpoint',
      },
    },
  },
};

// ── Model tier selector ───────────────────────────────────────────────────────
function ModelTierSelector({ selected, onChange, navigate }) {
  const tiers = Object.entries(MODEL_TIERS);

  return (
    <Card>
      <CardHeader><CardTitle>Adapter tier</CardTitle></CardHeader>
      <CardContent>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2 mb-2">
          {tiers.map(([key, tier]) => {
            const isByok = key === 'byok';
            const isByom = key === 'byom';
            const isSpecial = isByok || isByom;

            return (
              <button key={key} type="button" onClick={() => onChange(key)}
                className="text-left p-3 rounded-xl border-2 transition-all relative"
                style={{
                  borderColor:     selected === key ? tier.color : '#e2e8f0',
                  backgroundColor: selected === key ? tier.bg    : 'white',
                }}>
                <div className="flex items-center justify-between mb-1">
                  <span className="text-xs font-bold" style={{ color: tier.color }}>
                    {isSpecial && (isByok ? <Key size={9} className="inline mr-1" /> : <Cpu size={9} className="inline mr-1" />)}
                    {tier.label}
                  </span>
                  <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded-full text-white"
                    style={{ backgroundColor: tier.color }}>
                    {tier.credits} cr
                  </span>
                </div>
                <p className="text-[10px] text-slate-500 leading-tight">{tier.models}</p>
                {isSpecial && (
                  <button
                    onClick={e => { e.stopPropagation(); navigate('/billing?tab=byok'); }}
                    className="mt-1.5 text-[10px] underline"
                    style={{ color: tier.color }}>
                    Configure →
                  </button>
                )}
              </button>
            );
          })}
        </div>
        <p className="text-xs text-slate-400">
          BYOK and BYOM charge 1 credit for orchestration only — your provider or model handles execution.
        </p>
      </CardContent>
    </Card>
  );
}

// ── Template browser ──────────────────────────────────────────────────────────
function TemplateBrowser({ onLoad }) {
  const [open, setOpen] = useState(false);

  return (
    <Card>
      <button className="w-full" onClick={() => setOpen(o => !o)}>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <BookOpen size={14} className="text-slate-500" />
              <CardTitle>Example intents</CardTitle>
              <span className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-blue-50 text-blue-600 border border-blue-100">
                {Object.keys(TEMPLATES).length + 2} examples
              </span>
            </div>
            {open
              ? <ChevronUp size={14} className="text-slate-400" />
              : <ChevronDown size={14} className="text-slate-400" />}
          </div>
        </CardHeader>
      </button>

      {open && (
        <CardContent className="pt-0 space-y-4">

          {/* ── Featured: SATISFIED + VIOLATED ── */}
          <div>
            <p className="text-[10px] font-semibold text-slate-500 uppercase tracking-wide mb-2">
              See positive &amp; negative results
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">

              {/* SATISFIED example */}
              <button type="button"
                onClick={() => { onLoad(EXAMPLE_SATISFIED); setOpen(false); }}
                className="text-left p-3 rounded-xl border-2 border-green-200 bg-green-50 hover:border-green-400 hover:bg-green-100 transition-all group">
                <div className="flex items-center gap-2 mb-1.5">
                  <span className="w-5 h-5 rounded-full bg-green-500 flex items-center justify-center flex-shrink-0">
                    <span className="text-white text-[10px] font-bold">✓</span>
                  </span>
                  <span className="text-xs font-bold text-green-800">SATISFIED example</span>
                </div>
                <p className="text-[10px] text-green-700 leading-snug mb-2">
                  Fraud detection intent with realistic budget ($0.50), generous constraints, and achievable policy rules — completes successfully.
                </p>
                <div className="flex flex-wrap gap-1">
                  {['budget: $0.50', 'retries: 2', 'timeout: 30s', 'drift: 0.20'].map(t => (
                    <span key={t} className="text-[9px] px-1.5 py-0.5 rounded bg-green-200 text-green-800 font-medium">{t}</span>
                  ))}
                </div>
              </button>

              {/* VIOLATED example */}
              <button type="button"
                onClick={() => { onLoad(EXAMPLE_VIOLATED); setOpen(false); }}
                className="text-left p-3 rounded-xl border-2 border-red-200 bg-red-50 hover:border-red-400 hover:bg-red-100 transition-all group">
                <div className="flex items-center gap-2 mb-1.5">
                  <span className="w-5 h-5 rounded-full bg-red-500 flex items-center justify-center flex-shrink-0">
                    <span className="text-white text-[10px] font-bold">✕</span>
                  </span>
                  <span className="text-xs font-bold text-red-800">VIOLATED example</span>
                </div>
                <p className="text-[10px] text-red-700 leading-snug mb-2">
                  Same intent — budget set to $0.00001, timeout 1s, topic blocks cover the actual content. Will breach constraints and fail.
                </p>
                <div className="flex flex-wrap gap-1">
                  {['budget: $0.00001', 'retries: 0', 'timeout: 1s', 'drift: 0.01'].map(t => (
                    <span key={t} className="text-[9px] px-1.5 py-0.5 rounded bg-red-200 text-red-800 font-medium">{t}</span>
                  ))}
                </div>
              </button>

            </div>
          </div>

          {/* ── Divider ── */}
          <div className="border-t border-slate-100" />

          {/* ── Other templates ── */}
          <div>
            <p className="text-[10px] font-semibold text-slate-500 uppercase tracking-wide mb-2">
              Feature templates
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {Object.entries(TEMPLATES).map(([key, tmpl]) => (
                <button key={key} type="button"
                  onClick={() => { onLoad(tmpl.payload); setOpen(false); }}
                  className="text-left p-3 rounded-lg border border-slate-200 hover:border-blue-300 hover:bg-blue-50 transition-all group">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="text-base">{tmpl.icon}</span>
                    <span className="text-xs font-semibold text-slate-800 group-hover:text-blue-700">
                      {tmpl.label}
                    </span>
                  </div>
                  <p className="text-[10px] text-slate-500 leading-snug">{tmpl.description}</p>
                </button>
              ))}
            </div>
          </div>

          <p className="text-[10px] text-slate-400 text-center">
            For domain-specific intent templates →{' '}
            <button
              onClick={() => window.location.href = '/fintech-intents'}
              className="text-blue-500 underline">
              browse 264 fintech intents
            </button>
          </p>
        </CardContent>
      )}
    </Card>
  );
}

// ── Policy summary strip ──────────────────────────────────────────────────────
// Shows what governance rules are active in the current payload at a glance
function PolicyStrip({ json }) {
  let policy = null;
  let budget = null;
  let constraints = null;

  try {
    const parsed  = JSON.parse(json);
    policy        = parsed.policy;
    budget        = parsed.budget;
    constraints   = parsed.constraints;
  } catch { return null; }

  const rules = [];

  if (budget?.ceilingUsd)
    rules.push({ icon: '💰', label: `$${budget.ceilingUsd} ceiling`, color: '#16a34a' });

  if (constraints?.maxRetries !== undefined)
    rules.push({ icon: '🔁', label: `${constraints.maxRetries} retries`, color: '#2563eb' });

  if (constraints?.maxLatencyMs)
    rules.push({ icon: '⏱', label: `${constraints.maxLatencyMs}ms max`, color: '#0d9488' });

  if (policy?.requireHumanReview)
    rules.push({ icon: '👤', label: 'HITL gate', color: '#7c3aed' });

  if (policy?.blockTopics?.length)
    rules.push({ icon: '🚫', label: `${policy.blockTopics.length} blocked topics`, color: '#dc2626' });

  if (policy?.driftThreshold)
    rules.push({ icon: '📊', label: `Drift ≤ ${policy.driftThreshold}`, color: '#d97706' });

  if (!rules.length) return null;

  return (
    <div className="flex flex-wrap gap-1.5 px-1">
      {rules.map(r => (
        <span key={r.label}
          className="flex items-center gap-1 text-[10px] font-medium px-2 py-0.5 rounded-full border"
          style={{ color: r.color, borderColor: r.color + '40', backgroundColor: r.color + '10' }}>
          {r.icon} {r.label}
        </span>
      ))}
    </div>
  );
}

// ── Intent type selector — free-form + suggestions ───────────────────────────
// intentType is a free-form string in the Quarkus backend (no enum).
// Chips are quick-select shortcuts only — user can type anything.
function IntentTypeSelector({ json, onSelect }) {
  const [catFilter, setCatFilter] = useState('All');
  const [customVal, setCustomVal] = useState('');

  const currentType = (() => {
    try { return JSON.parse(json)?.intentType ?? ''; }
    catch { return ''; }
  })();

  const filtered = catFilter === 'All'
    ? INTENT_SUGGESTIONS
    : INTENT_SUGGESTIONS.filter(t => t.category === catFilter);

  function handleCustomSubmit(e) {
    e.preventDefault();
    const val = customVal.trim().toLowerCase().replace(/\s+/g, '_');
    if (val) { onSelect(val); setCustomVal(''); }
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between flex-wrap gap-2">
          <div>
            <CardTitle>Intent type</CardTitle>
            <p className="text-[10px] text-slate-400 mt-0.5 font-normal">
              Free-form string — type anything or pick a suggestion
            </p>
          </div>
          <div className="flex gap-1">
            {INTENT_CATEGORIES.map(c => (
              <button key={c} onClick={() => setCatFilter(c)}
                className={`px-2 py-0.5 rounded-md text-[10px] font-medium transition-colors ${
                  catFilter === c
                    ? 'bg-blue-600 text-white'
                    : 'bg-slate-100 text-slate-500 hover:bg-slate-200'
                }`}>
                {c}
              </button>
            ))}
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {/* Suggestion chips */}
        <div className="flex gap-1.5 flex-wrap">
          {filtered.map(t => {
            const sel = currentType === t.id;
            return (
              <button key={t.id} type="button" onClick={() => onSelect(t.id)}
                className={`px-3 py-1.5 rounded-lg text-xs font-medium border transition-colors ${
                  sel
                    ? 'bg-blue-600 text-white border-blue-600'
                    : 'bg-white text-slate-600 border-slate-200 hover:border-blue-300'
                }`}>
                {t.label}
              </button>
            );
          })}
        </div>

        {/* Free-form input */}
        <form onSubmit={handleCustomSubmit} className="flex gap-2">
          <input
            type="text"
            value={customVal}
            onChange={e => setCustomVal(e.target.value)}
            placeholder={currentType ? `Current: ${currentType}` : 'or type any intent type…'}
            className="flex-1 text-xs font-mono border border-slate-200 rounded-lg px-3 py-1.5 bg-slate-50 focus:outline-none focus:border-blue-400 focus:bg-white transition-colors"
            style={{ fontFamily: "'JetBrains Mono', monospace" }}
          />
          <button type="submit"
            disabled={!customVal.trim()}
            className="text-xs px-3 py-1.5 rounded-lg border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 disabled:opacity-40 transition-colors">
            Set
          </button>
        </form>

        <p className="text-[10px] text-slate-400 leading-relaxed">
          The backend accepts any string. For domain-specific types used in your 264 fintech intents,{' '}
          <button
            onClick={() => window.location.href = '/fintech-intents'}
            className="text-blue-500 underline">
            browse the intent library →
          </button>
        </p>
      </CardContent>
    </Card>
  );
}

// ── Main page ─────────────────────────────────────────────────────────────────
export default function Playground({ keycloak }) {
  const navigate = useNavigate();
  const { balance, isEmpty, deductCredits, refundCredits, reload } = useCredits();

  const [json,       setJson]       = useState(DEFAULT);
  const [jsonErr,    setJsonErr]    = useState(null);
  const [iKey,       setIKey]       = useState(uuidv4);
  const [tier,       setTier]       = useState('economy');
  const [loading,    setLoading]    = useState(false);
  const [result,     setResult]     = useState(null);
  const [error,      setError]      = useState(null);
  const [copied,     setCopied]     = useState(false);
  const [creditCost, setCreditCost] = useState(null);
  const [showRaw,    setShowRaw]    = useState(false);

  // ── Helpers ─────────────────────────────────────────────────────────────

  function handleChange(e) {
    setJson(e.target.value);
    try   { JSON.parse(e.target.value); setJsonErr(null); }
    catch { setJsonErr('Invalid JSON'); }
  }

  function loadTemplate(payload) {
    // Detect best tier from adapter hint in template
    if (payload.adapter?.type === 'byok') setTier('byok');
    else if (payload.adapter?.type === 'byom') setTier('byom');
    else if (payload.policy?.requireHumanReview) setTier('premium');
    setJson(JSON.stringify(payload, null, 2));
    setJsonErr(null);
    setResult(null);
    setError(null);
  }

  function setIntentType(id) {
    try {
      const p = JSON.parse(json);
      p.intentType = id;
      setJson(JSON.stringify(p, null, 2));
      setJsonErr(null);
    } catch { /**/ }
  }

  // ── Submit ───────────────────────────────────────────────────────────────

  async function handleSubmit() {
    if (isEmpty) { setError('No credits remaining. Top up to continue.'); return; }
    setError(null); setResult(null); setCreditCost(null);

    let body;
    try   { body = JSON.parse(json); }
    catch { setError('Fix the JSON before submitting'); return; }

    body._modelTier = tier;
    setLoading(true);

    // Optimistic deduct for immediate UI feedback.
    // The delayed reload() below re-syncs from the real DB balance
    // once the intent pipeline has completed and written the ledger.
    // An immediate reload() would race with the async pipeline and
    // fetch the old balance, reverting the optimistic deduction.
    deductCredits(tier);

    try {
      const id = await submitIntent(keycloak, body);
      setResult(String(id));
      setCreditCost(MODEL_TIERS[tier].credits);
      // Reload after 8 s — covers typical LLM round-trip latency.
      // The ledger write happens when the intent reaches SATISFIED or
      // VIOLATED, not at submission time, so we wait for completion.
      setTimeout(reload, 8000);
    } catch (e) {
      refundCredits(tier);
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }

  function copy() {
    navigator.clipboard.writeText(result ?? '');
    setCopied(true); setTimeout(() => setCopied(false), 2000);
  }

  const tierData  = MODEL_TIERS[tier];
  const canSubmit = !jsonErr && !loading && !isEmpty && balance !== null;

  // ── Render ───────────────────────────────────────────────────────────────

  return (
    <Page
      title="Playground"
      subtitle="Test budget enforcement, policy rules and adapter routing in real time"
      action={result && (
        <Button variant="secondary" size="sm"
          onClick={() => { setResult(null); setCreditCost(null); setIKey(uuidv4()); }}>
          <RefreshCw size={13} /> New intent
        </Button>
      )}>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">

        {/* ── Left column ── */}
        <div className="space-y-4">

          {/* Adapter tier */}
          <ModelTierSelector selected={tier} onChange={setTier} navigate={navigate} />

          {/* Intent type */}
          <IntentTypeSelector json={json} onSelect={setIntentType} />

          {/* Template browser */}
          <TemplateBrowser onLoad={loadTemplate} />

          {/* Payload editor */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle>Intent payload</CardTitle>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-slate-400">JSON</span>
                  <button
                    onClick={() => setShowRaw(v => !v)}
                    className="text-[10px] text-slate-400 hover:text-slate-600 border border-slate-200 rounded px-1.5 py-0.5">
                    {showRaw ? 'Collapse' : 'Expand'}
                  </button>
                </div>
              </div>
            </CardHeader>
            <CardContent className="p-0">
              <textarea
                value={json}
                onChange={handleChange}
                rows={showRaw ? 24 : 13}
                className="w-full font-mono text-xs p-4 resize-none focus:outline-none rounded-b-xl text-slate-700 bg-slate-50"
                style={{ fontFamily: "'JetBrains Mono', monospace" }}
              />
              {jsonErr && <p className="px-4 pb-3 text-xs text-red-500">{jsonErr}</p>}
            </CardContent>
          </Card>

          {/* Active governance rules — live preview from payload */}
          <PolicyStrip json={json} />

          {/* Idempotency key */}
          <Card>
            <CardHeader><CardTitle>Request metadata</CardTitle></CardHeader>
            <CardContent>
              <div className="flex items-center justify-between mb-1.5">
                <label className="text-xs font-medium text-slate-600">Idempotency key</label>
                <button onClick={() => setIKey(uuidv4())}
                  className="text-xs text-blue-600 flex items-center gap-1">
                  <RefreshCw size={11} /> Regenerate
                </button>
              </div>
              <input readOnly value={iKey}
                className="w-full text-xs font-mono border border-slate-200 rounded-lg px-3 py-2 bg-slate-50 text-slate-500"
                style={{ fontFamily: "'JetBrains Mono', monospace" }} />
            </CardContent>
          </Card>

          {/* Governance summary strip */}
          <div className="rounded-xl border border-slate-200 bg-slate-50 p-3 space-y-2">
            <p className="text-[10px] font-semibold text-slate-500 uppercase tracking-wide">
              What DecisionMesh enforces on this intent
            </p>
            <div className="grid grid-cols-2 gap-2 text-[10px] text-slate-600">
              {[
                { icon: <Shield size={10} />,      label: 'Budget ceiling',       color: '#16a34a' },
                { icon: <RotateCcw size={10} />,   label: 'Retry policy',         color: '#2563eb' },
                { icon: <Clock size={10} />,        label: 'Latency constraint',   color: '#0d9488' },
                { icon: <Shield size={10} />,       label: 'Policy rules',         color: '#7c3aed' },
                { icon: <AlertTriangle size={10} />,label: 'Drift detection',      color: '#d97706' },
                { icon: <BookOpen size={10} />,     label: 'Immutable audit log',  color: '#475569' },
              ].map(({ icon, label, color }) => (
                <div key={label} className="flex items-center gap-1.5">
                  <span style={{ color }}>{icon}</span>
                  <span>{label}</span>
                </div>
              ))}
            </div>
          </div>

          {/* Credit cost + submit */}
          <div className="space-y-2">
            <div className="flex items-center justify-between px-1">
              <div className="flex items-center gap-2 text-sm">
                <Zap size={13} style={{ color: tierData.color }} />
                <span className="text-slate-600">
                  Cost:{' '}
                  <strong style={{ color: tierData.color }}>
                    {tierData.credits} credit{tierData.credits !== 1 ? 's' : ''}
                  </strong>
                  <span className="text-xs text-slate-400 ml-1">({tierData.label})</span>
                </span>
              </div>
              {balance !== null && (
                <span className="text-xs text-slate-400">
                  Balance:{' '}
                  <strong style={{
                    color: balance <= 0 ? '#dc2626' : balance < 50 ? '#d97706' : '#16a34a',
                  }}>
                    {balance?.toLocaleString()}
                  </strong>
                </span>
              )}
            </div>

            <Button className="w-full" size="lg" loading={loading} disabled={!canSubmit} onClick={handleSubmit}>
              <Send size={14} />
              {isEmpty ? 'No credits — top up to submit' : 'Submit intent'}
            </Button>

            {isEmpty && (
              <button onClick={() => navigate('/billing')}
                className="w-full text-xs text-blue-600 underline text-center">
                Buy credits or upgrade plan →
              </button>
            )}
          </div>

          {error && (
            <div className="p-3 bg-red-50 border border-red-200 rounded-lg text-xs text-red-700">
              {error}
            </div>
          )}
        </div>

        {/* ── Right column — execution result ── */}
        <div>
          {result ? (
            <Card>
              <CardHeader className="flex flex-row items-center justify-between">
                <div className="flex items-center gap-2">
                  <CardTitle>Intent submitted</CardTitle>
                  {creditCost && (
                    <span className="text-xs font-semibold px-2 py-0.5 rounded-full"
                      style={{ backgroundColor: tierData.bg, color: tierData.color }}>
                      -{creditCost} credit{creditCost !== 1 ? 's' : ''}
                    </span>
                  )}
                </div>
                <div className="flex gap-2">
                  <Button variant="secondary" size="sm" onClick={copy}>
                    <Copy size={12} />{copied ? 'Copied!' : 'Copy ID'}
                  </Button>
                  <Button variant="secondary" size="sm"
                    onClick={() => navigate(`/intents/${result}`)}>
                    <ExternalLink size={12} /> Detail
                  </Button>
                </div>
              </CardHeader>
              <CardContent>
                <div className="mb-4 p-3 rounded-lg bg-green-50 border border-green-200">
                  <p className="text-xs text-green-700 font-medium mb-1">Intent ID</p>
                  <p className="font-mono text-sm text-green-800 break-all"
                    style={{ fontFamily: "'JetBrains Mono', monospace" }}>
                    {result}
                  </p>
                </div>
                <p className="text-sm font-medium text-slate-700 mb-4">Execution timeline</p>
                <ExecutionTimeline
                  keycloak={keycloak} intentId={result}
                  currentPhase="CREATED" terminal={false} satisfied={false}
                />
              </CardContent>
            </Card>
          ) : (
            <div className="space-y-4">
              {/* Empty state */}
              <Card className="flex items-center justify-center min-h-64 border-dashed border-slate-200 bg-transparent shadow-none">
                <div className="text-center text-slate-400 p-8">
                  <Send size={28} className="mx-auto mb-3 opacity-20" />
                  <p className="text-sm font-medium">Submit an intent</p>
                  <p className="text-xs mt-1 text-slate-300">
                    The execution timeline appears here
                  </p>
                  <p className="text-xs mt-3 font-semibold" style={{ color: tierData.color }}>
                    {tierData.credits} credit{tierData.credits !== 1 ? 's' : ''} per execution · {tierData.label} tier
                  </p>
                </div>
              </Card>

              {/* What gets enforced — right panel explainer */}
              <Card>
                <CardHeader><CardTitle>What happens when you submit</CardTitle></CardHeader>
                <CardContent>
                  <div className="space-y-3">
                    {[
                      {
                        phase: 'Planning',
                        color: '#2563eb',
                        detail: 'DecisionMesh selects the adapter, validates budget ceiling, and builds the execution plan.',
                      },
                      {
                        phase: 'Policy check',
                        color: '#7c3aed',
                        detail: 'Blocked topics, model allow-list, and HITL gate rules are evaluated before any LLM call.',
                      },
                      {
                        phase: 'Execution',
                        color: '#0d9488',
                        detail: 'The intent is dispatched to the adapter. Budget is tracked live. Retries fire on failure.',
                      },
                      {
                        phase: 'Quality scoring',
                        color: '#d97706',
                        detail: 'Output is scored for quality, drift from baseline, and policy compliance.',
                      },
                      {
                        phase: 'Audit log',
                        color: '#475569',
                        detail: 'Every attempt, credit cost, policy outcome and response is written to the immutable ledger.',
                      },
                    ].map(({ phase, color, detail }) => (
                      <div key={phase} className="flex gap-3">
                        <div className="w-1 rounded-full flex-shrink-0 self-stretch" style={{ backgroundColor: color }} />
                        <div>
                          <p className="text-xs font-semibold" style={{ color }}>{phase}</p>
                          <p className="text-xs text-slate-500 leading-relaxed">{detail}</p>
                        </div>
                      </div>
                    ))}
                  </div>

                  <div className="mt-4 pt-4 border-t border-slate-100 flex items-start gap-2">
                    <Key size={12} className="text-amber-500 shrink-0 mt-0.5" />
                    <p className="text-[10px] text-slate-500 leading-relaxed">
                      <strong className="text-slate-700">Using BYOK or BYOM?</strong>{' '}
                      Your model or key handles execution — DecisionMesh enforces all of
                      the above governance on top of it for 1 credit.{' '}
                      <button onClick={() => navigate('/billing?tab=byok')}
                        className="text-blue-500 underline">
                        Configure keys →
                      </button>
                    </p>
                  </div>
                </CardContent>
              </Card>

              {/* Quick links */}
              <div className="grid grid-cols-2 gap-3">
                {[
                  { label: 'Browse 264 intent templates', icon: <BookOpen size={13} />, path: '/fintech-intents', color: '#2563eb' },
                  { label: 'Configure BYOK / BYOM',       icon: <Key size={13} />,      path: '/billing?tab=byok', color: '#d97706' },
                  { label: 'View execution history',      icon: <Clock size={13} />,    path: '/intents',          color: '#0d9488' },
                  { label: 'Policy builder',              icon: <Shield size={13} />,   path: '/policies',         color: '#7c3aed' },
                ].map(({ label, icon, path, color }) => (
                  <button key={label}
                    onClick={() => navigate(path)}
                    className="flex items-center gap-2 p-3 rounded-xl border border-slate-200 hover:border-slate-300 bg-white text-left transition-colors group">
                    <span className="p-1.5 rounded-lg flex-shrink-0"
                      style={{ background: color + '15', color }}>
                      {icon}
                    </span>
                    <span className="text-xs font-medium text-slate-600 group-hover:text-slate-900 leading-tight">
                      {label}
                    </span>
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>

      </div>
    </Page>
  );
}
