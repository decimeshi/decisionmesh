import { useState, useEffect } from 'react';
import { Plus, Trash2, ShieldCheck, BookOpen, X } from 'lucide-react';
import { v4 as uuidv4 } from 'uuid';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Button, EmptyState, Spinner } from '../components/shared';
import { listPolicies, savePolicy, deletePolicy } from '../utils/api';

const METRICS   = ['cost', 'latency', 'risk'];
const OPERATORS = ['>', '<', '='];
const ACTIONS   = ['REJECT', 'FALLBACK', 'RETRY'];

const NEW_RULE   = () => ({ id: uuidv4(), metric: 'cost', operator: '>', value: 0.01, action: 'REJECT' });
const NEW_POLICY = () => ({ name: '', rules: [NEW_RULE()] });

// ── Industry starter templates from product site ──────────────────────────────
const TEMPLATES = [
  {
    id: 'tpl-cost-limit',
    category: 'Cost control',
    name: 'Cost limit enforcement',
    desc: 'Reject requests exceeding $0.10 per intent. Essential for production budget governance.',
    color: '#2563eb',
    rules: [{ id: uuidv4(), metric: 'cost', operator: '>', value: 0.10, action: 'REJECT' }],
  },
  {
    id: 'tpl-latency-fallback',
    category: 'Performance',
    name: 'Latency fallback',
    desc: 'Automatically fall back to a faster adapter when latency exceeds 5 000ms.',
    color: '#d97706',
    rules: [{ id: uuidv4(), metric: 'latency', operator: '>', value: 5000, action: 'FALLBACK' }],
  },
  {
    id: 'tpl-high-risk-reject',
    category: 'Safety',
    name: 'High-risk rejection',
    desc: 'Block any execution where the risk score exceeds 0.7. Recommended for regulated industries.',
    color: '#ef4444',
    rules: [{ id: uuidv4(), metric: 'risk', operator: '>', value: 0.7, action: 'REJECT' }],
  },
  {
    id: 'tpl-healthcare',
    category: 'Healthcare / HIPAA',
    name: 'PHI protection gate',
    desc: 'Reject high-risk, high-cost AI calls in healthcare contexts. HIPAA-aligned guardrail.',
    color: '#0d9488',
    rules: [
      { id: uuidv4(), metric: 'risk',    operator: '>', value: 0.5,  action: 'REJECT' },
      { id: uuidv4(), metric: 'cost',    operator: '>', value: 0.05, action: 'REJECT' },
    ],
  },
  {
    id: 'tpl-finserv',
    category: 'Financial services',
    name: 'Fair lending safeguard',
    desc: 'Retry on high-risk decisions and reject when cost exceeds credit-decision threshold.',
    color: '#7c3aed',
    rules: [
      { id: uuidv4(), metric: 'risk', operator: '>', value: 0.6,  action: 'RETRY' },
      { id: uuidv4(), metric: 'cost', operator: '>', value: 0.08, action: 'REJECT' },
    ],
  },
  {
    id: 'tpl-enterprise-retry',
    category: 'Reliability',
    name: 'Auto-retry on failure',
    desc: 'Retry low-cost failures automatically — improves success rate without breaking budget.',
    color: '#16a34a',
    rules: [
      { id: uuidv4(), metric: 'cost', operator: '<', value: 0.02, action: 'RETRY' },
      { id: uuidv4(), metric: 'risk', operator: '<', value: 0.3,  action: 'RETRY' },
    ],
  },
];

// ── Template library modal ────────────────────────────────────────────────────
function TemplateLibrary({ onSelect, onClose }) {
  const categories = [...new Set(TEMPLATES.map(t => t.category))];
  const [activeCategory, setActiveCategory] = useState('All');

  const filtered = activeCategory === 'All'
    ? TEMPLATES
    : TEMPLATES.filter(t => t.category === activeCategory);

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
      <Card className="w-full max-w-2xl max-h-[90vh] flex flex-col">
        <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between shrink-0">
          <div className="flex items-center gap-2">
            <BookOpen size={16} className="text-blue-600" />
            <h3 className="text-sm font-semibold text-slate-800">Policy template library</h3>
          </div>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-xl leading-none">×</button>
        </div>

        {/* Category filter */}
        <div className="px-5 py-3 border-b border-slate-100 flex gap-2 flex-wrap shrink-0">
          {['All', ...categories].map(cat => (
            <button key={cat}
              onClick={() => setActiveCategory(cat)}
              className={`px-2.5 py-1 rounded-full text-xs font-medium transition-colors ${
                activeCategory === cat ? 'bg-blue-600 text-white' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
              }`}>
              {cat}
            </button>
          ))}
        </div>

        {/* Templates grid */}
        <div className="overflow-y-auto p-5 grid grid-cols-1 sm:grid-cols-2 gap-3">
          {filtered.map(tpl => (
            <button key={tpl.id}
              onClick={() => { onSelect(tpl); onClose(); }}
              className="text-left p-4 rounded-xl border border-slate-200 hover:border-blue-300 hover:shadow-sm transition-all group">
              <div className="flex items-start justify-between mb-2">
                <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full"
                  style={{ backgroundColor: `${tpl.color}18`, color: tpl.color }}>
                  {tpl.category}
                </span>
                <Plus size={13} className="text-slate-300 group-hover:text-blue-500 transition-colors" />
              </div>
              <p className="text-sm font-semibold text-slate-800 mb-1">{tpl.name}</p>
              <p className="text-xs text-slate-500 leading-relaxed">{tpl.desc}</p>
              <div className="mt-3 flex flex-wrap gap-1">
                {tpl.rules.map((r, i) => (
                  <span key={i} className="text-[10px] bg-slate-100 text-slate-600 px-2 py-0.5 rounded font-mono">
                    {r.metric} {r.operator} {r.value} → {r.action}
                  </span>
                ))}
              </div>
            </button>
          ))}
        </div>
      </Card>
    </div>
  );
}

// ── Rule row ──────────────────────────────────────────────────────────────────
function RuleRow({ rule, onChange, onDelete }) {
  const sel = (key, opts) => (
    <select value={rule[key]} onChange={e => onChange({ ...rule, [key]: e.target.value })}
      className="text-xs border border-slate-200 rounded-lg px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white">
      {opts.map(o => <option key={o}>{o}</option>)}
    </select>
  );

  return (
    <div className="flex items-center gap-2 flex-wrap">
      {sel('metric',   METRICS)}
      {sel('operator', OPERATORS)}
      <input type="number" step="0.001" value={rule.value}
        onChange={e => onChange({ ...rule, value: parseFloat(e.target.value) })}
        className="w-24 text-xs border border-slate-200 rounded-lg px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-blue-500"/>
      <span className="text-xs text-slate-400">→</span>
      {sel('action', ACTIONS)}
      <button onClick={onDelete} className="p-1 text-slate-300 hover:text-red-500 transition-colors ml-auto">
        <Trash2 size={13}/>
      </button>
    </div>
  );
}

// ── Policy card ───────────────────────────────────────────────────────────────
function PolicyCard({ policy, onSave, onDelete }) {
  const [form, setForm]     = useState({ ...policy, rules: [...(policy.rules ?? [])] });
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty]   = useState(false);

  function updateRule(id, updated) { setForm(f => ({ ...f, rules: f.rules.map(r => r.id === id ? updated : r) })); setDirty(true); }
  function addRule()               { setForm(f => ({ ...f, rules: [...f.rules, NEW_RULE()] })); setDirty(true); }
  function removeRule(id)          { setForm(f => ({ ...f, rules: f.rules.filter(r => r.id !== id) })); setDirty(true); }

  async function handleSave() {
    setSaving(true);
    try { await onSave(form); setDirty(false); }
    finally { setSaving(false); }
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <input value={form.name} onChange={e => { setForm(f => ({...f, name: e.target.value})); setDirty(true); }}
          placeholder="Policy name…"
          className="text-sm font-semibold text-slate-800 bg-transparent focus:outline-none border-b border-transparent focus:border-blue-400 pb-0.5"/>
        <div className="flex gap-2">
          {dirty && <Button size="sm" loading={saving} onClick={handleSave}>Save</Button>}
          <Button variant="ghost" size="sm" onClick={() => onDelete(policy.policyId)}>
            <Trash2 size={13}/>
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-xs text-slate-400 font-medium uppercase tracking-wide">Rules (all apply)</p>
        {form.rules.map(r => (
          <RuleRow key={r.id} rule={r} onChange={u => updateRule(r.id, u)} onDelete={() => removeRule(r.id)}/>
        ))}
        <button onClick={addRule} className="text-xs text-blue-600 hover:text-blue-700 flex items-center gap-1 mt-1">
          <Plus size={12}/> Add rule
        </button>
      </CardContent>
    </Card>
  );
}

// ── Main page ─────────────────────────────────────────────────────────────────
export default function PolicyBuilder({ keycloak }) {
  const [policies, setPolicies]     = useState([]);
  const [loading, setLoading]       = useState(true);
  const [showTemplates, setShowTemplates] = useState(false);

  async function load() {
    try { const d = await listPolicies(keycloak); setPolicies(d ?? []); }
    finally { setLoading(false); }
  }

  useEffect(() => { load(); }, [keycloak]);

  async function handleSave(policy) { await savePolicy(keycloak, policy); load(); }
  async function handleDelete(id)   {
    if (!id) { load(); return; }
    await deletePolicy(keycloak, id); load();
  }

  function handleAdd() {
    setPolicies(p => [{ policyId: null, ...NEW_POLICY() }, ...p]);
  }

  function handleTemplateSelect(tpl) {
    setPolicies(p => [{
      policyId: null,
      name: tpl.name,
      rules: tpl.rules.map(r => ({ ...r, id: uuidv4() })),
    }, ...p]);
  }

  return (
    <Page title="Policy Builder" subtitle="Define rules that govern intent execution"
      action={
        <div className="flex gap-2">
          <Button variant="secondary" onClick={() => setShowTemplates(true)}>
            <BookOpen size={14}/> Templates
          </Button>
          <Button onClick={handleAdd}><Plus size={14}/> New policy</Button>
        </div>
      }>

      {/* Template library info banner — shown when empty */}
      {!loading && policies.length === 0 && (
        <div className="p-4 bg-blue-50 border border-blue-200 rounded-xl flex items-start gap-3">
          <BookOpen size={16} className="text-blue-600 shrink-0 mt-0.5"/>
          <div className="flex-1">
            <p className="text-sm font-medium text-blue-800">Start from a template</p>
            <p className="text-xs text-blue-700 mt-0.5">
              Pick from industry-specific policy templates for Healthcare, Financial Services, Cost Control, and more —
              or build from scratch with the Policy Builder.
            </p>
          </div>
          <Button size="sm" onClick={() => setShowTemplates(true)}>Browse templates</Button>
        </div>
      )}

      {loading ? (
        <div className="flex justify-center py-16"><Spinner className="w-8 h-8"/></div>
      ) : policies.length === 0 ? (
        <Card>
          <EmptyState icon={<ShieldCheck size={22}/>} title="No policies"
            description="Create a policy to enforce cost, latency, and risk rules on every execution"
            action={
              <div className="flex gap-2 justify-center">
                <Button variant="secondary" onClick={() => setShowTemplates(true)}><BookOpen size={14}/> Browse templates</Button>
                <Button onClick={handleAdd}><Plus size={14}/> New policy</Button>
              </div>
            }/>
        </Card>
      ) : (
        <div className="space-y-4">
          {policies.map((p, i) => (
            <PolicyCard key={p.policyId ?? i} policy={p} onSave={handleSave} onDelete={handleDelete}/>
          ))}
        </div>
      )}

      {showTemplates && (
        <TemplateLibrary onSelect={handleTemplateSelect} onClose={() => setShowTemplates(false)}/>
      )}
    </Page>
  );
}
