import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { AreaChart, Area, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import {
  Activity, DollarSign, Zap, CheckCircle, Clock, Cpu, Server,
  ShieldCheck, FileText, Globe, Lock, ArrowRight, Send,
} from 'lucide-react';
import { useCredits } from '../context/CreditContext';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, MetricCard, PhaseBadge, SatisfactionBadge, Spinner } from '../components/shared';
import { listIntents, getCostAnalytics } from '../utils/api';
import { formatCost, formatDate, formatRelative, shortId } from '../lib/utils';

function ChartTip({ active, payload, label, fmt }) {
  if (!active || !payload?.length) return null;
  return (
      <div className="bg-white border border-slate-200 rounded-lg shadow-md p-3 text-xs">
        <p className="text-slate-400 mb-1">{label}</p>
        <p className="font-semibold text-slate-800">{fmt ? fmt(payload[0].value) : payload[0].value}</p>
      </div>
  );
}

// ── 6-stage pipeline — shown when user has no intents yet ─────────────────────
const PIPELINE_STAGES = [
  { num: '1', label: 'Intent',   desc: 'App states what it wants AI to do',      color: '#2563eb' },
  { num: '2', label: 'Validate', desc: 'Check permissions, schema & rate limits', color: '#4f46e5' },
  { num: '3', label: 'Policy',   desc: 'Enforce cost, safety & compliance rules', color: '#7c3aed' },
  { num: '4', label: 'Decision', desc: 'Create immutable, signed record',         color: '#0d9488' },
  { num: '5', label: 'Execute',  desc: 'Run AI if approved, block if not',        color: '#16a34a' },
  { num: '6', label: 'Audit',    desc: 'Complete trail logged for compliance',    color: '#d97706' },
];

function EmptyOnboarding({ onPlayground }) {
  return (
      <Card className="p-8">
        <div className="text-center mb-8">
          <p className="text-xs font-semibold text-blue-600 uppercase tracking-wider mb-2">Getting started</p>
          <h2 className="text-xl font-bold text-slate-800 mb-2">Every AI decision, fully accountable</h2>
          <p className="text-sm text-slate-500 max-w-xl mx-auto leading-relaxed">
            DecisionMesh sits between your app and AI execution — transforming every AI call into a
            governed, traceable decision. Submit your first intent to see it in action.
          </p>
        </div>

        {/* 6-stage pipeline */}
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-4 mb-8">
          {PIPELINE_STAGES.map(({ num, label, desc, color }) => (
              <div key={label} className="text-center">
                <div className="w-10 h-10 rounded-full flex items-center justify-center mx-auto mb-2 text-white text-sm font-bold"
                     style={{ backgroundColor: color }}>
                  {num}
                </div>
                <p className="text-xs font-semibold text-slate-700 mb-1">{label}</p>
                <p className="text-[10px] text-slate-400 leading-tight">{desc}</p>
              </div>
          ))}
        </div>

        {/* Three guarantees */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mb-8">
          {[
            {
              icon: <FileText size={18} />,
              title: 'Explicit Decisions',
              desc: 'No more black boxes. Every AI call becomes a documented decision — who, what, when, why, and how.',
              color: '#2563eb',
            },
            {
              icon: <ShieldCheck size={18} />,
              title: 'Centralized Policy',
              desc: 'Define once, enforce everywhere. Cost limits, safety checks, compliance rules — written as code, applied automatically.',
              color: '#7c3aed',
            },
            {
              icon: <Clock size={18} />,
              title: 'Deterministic Replay',
              desc: 'Prove exactly what happened. Recreate any decision from months ago with perfect accuracy.',
              color: '#0d9488',
            },
          ].map(({ icon, title, desc, color }) => (
              <div key={title} className="rounded-xl border border-slate-100 p-4 bg-slate-50">
                <div className="w-8 h-8 rounded-lg flex items-center justify-center mb-3" style={{ backgroundColor: `${color}18`, color }}>
                  {icon}
                </div>
                <p className="text-sm font-semibold text-slate-800 mb-1">{title}</p>
                <p className="text-xs text-slate-500 leading-relaxed">{desc}</p>
              </div>
          ))}
        </div>

        <div className="text-center">
          <button
              onClick={onPlayground}
              className="inline-flex items-center gap-2 px-5 py-2.5 bg-blue-600 text-white text-sm font-semibold rounded-xl hover:bg-blue-700 transition-colors"
          >
            <Send size={14} /> Submit your first intent <ArrowRight size={14} />
          </button>
          <p className="text-xs text-slate-400 mt-2">Takes less than 30 seconds</p>
        </div>
      </Card>
  );
}

// ── Compliance health widget ──────────────────────────────────────────────────
const COMPLIANCE_ITEMS = [
  { icon: Globe,       label: 'EU AI Act', sub: 'Complete traceability',   status: 'ready', color: '#16a34a' },
  { icon: FileText,    label: 'SOC 2',     sub: 'Evidence generation',     status: 'ready', color: '#16a34a' },
  { icon: Lock,        label: 'GDPR',      sub: 'Data residency controls', status: 'ready', color: '#16a34a' },
  { icon: ShieldCheck, label: 'HIPAA',     sub: 'PHI detection & masking', status: 'pro',   color: '#7c3aed' },
];

function ComplianceHealth() {
  return (
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle>Governance health</CardTitle>
            <span className="text-[10px] font-semibold bg-green-50 text-green-700 border border-green-200 px-2 py-0.5 rounded-full">
            ● Operational
          </span>
          </div>
        </CardHeader>
        <CardContent className="space-y-2.5">
          {COMPLIANCE_ITEMS.map(({ icon: Icon, label, sub, status, color }) => (
              <div key={label} className="flex items-center gap-3">
                <div className="p-1.5 rounded-lg shrink-0" style={{ backgroundColor: `${color}18` }}>
                  <Icon size={13} style={{ color }} />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-xs font-medium text-slate-700">{label}</p>
                  <p className="text-[10px] text-slate-400">{sub}</p>
                </div>
                {status === 'ready' ? (
                    <span className="text-[10px] font-semibold text-green-600 bg-green-50 border border-green-200 px-2 py-0.5 rounded-full shrink-0">Ready</span>
                ) : (
                    <span className="text-[10px] font-semibold text-purple-600 bg-purple-50 border border-purple-200 px-2 py-0.5 rounded-full shrink-0">Pro</span>
                )}
              </div>
          ))}
          <p className="text-[10px] text-slate-400 pt-2 border-t border-slate-50 leading-relaxed">
            Audit trails are immutable and signed. Export CSV for auditors at any time from the Audit log.
          </p>
        </CardContent>
      </Card>
  );
}

// ── Main dashboard ─────────────────────────────────────────────────────────────
export default function Dashboard({ keycloak }) {
  const navigate = useNavigate();
  const { balance, allocated, pct, statusColor, isLow, isEmpty } = useCredits();
  const [intents,      setIntents]      = useState(null);
  const [costData,     setCostData]     = useState(null);
  const [loading,      setLoading]      = useState(true);

  useEffect(() => {
    let active = true;

    async function load() {
      try {
        const [i, c] = await Promise.allSettled([
          listIntents(keycloak, { size: 8, sort: 'createdAt,desc' }),
          getCostAnalytics(keycloak),
        ]);
        if (!active) return;
        if (i.status === 'fulfilled') setIntents(i.value);
        if (c.status === 'fulfilled') setCostData(c.value);
      } finally { if (active) setLoading(false); }
    }

    load();

    // Fix: depend on keycloak.authenticated (boolean) rather than the keycloak
    // object reference.  The Keycloak instance is stable but ESLint/React would
    // flag the object reference as potentially creating multiple intervals if the
    // prop ever changes.  Using the boolean is also more semantically correct —
    // we only want to poll while the user is authenticated.
    const t = setInterval(load, 30_000);
    return () => { active = false; clearInterval(t); };
  }, [keycloak?.authenticated]); // eslint-disable-line react-hooks/exhaustive-deps

  const total     = intents?.totalElements ?? 0;
  const content   = intents?.content ?? [];
  const satisfied = content.filter(i => i.satisfactionState === 'SATISFIED').length;
  const rate      = content.length ? ((satisfied / content.length) * 100).toFixed(0) : '—';
  const noData    = !loading && total === 0;

  return (
      <Page
          title="Dashboard"
          subtitle="System overview"
      >
        {/* KPIs */}
        <div className="grid grid-cols-2 lg:grid-cols-5 gap-4">
          <MetricCard label="Total intents"  value={total}                                       sub="All time"       icon={<Activity size={15}/>} />
          <MetricCard label="Total cost"     value={formatCost(costData?.totalCostUsd ?? 0)}     sub="All time"       icon={<DollarSign size={15}/>} />
          <MetricCard label="Success rate"   value={`${rate}%`}                                  sub="Recent intents" icon={<CheckCircle size={15}/>} />
          <MetricCard label="Avg cost"       value={formatCost(costData?.avgCostPerIntent ?? 0)} sub="Per intent"     icon={<Zap size={15}/>} />
          <div
              onClick={() => navigate('/billing?tab=credits')}
              className="bg-white rounded-xl border shadow-sm p-4 cursor-pointer hover:shadow-md transition-all"
              style={{ borderColor: isEmpty ? '#fca5a5' : isLow ? '#fcd34d' : '#e2e8f0' }}
          >
            <div className="flex items-center justify-between mb-2">
              <p className="text-xs font-medium text-slate-500">Credits</p>
              <Zap size={15} style={{ color: statusColor }} />
            </div>
            <p className="text-2xl font-bold" style={{ color: statusColor }}>
              {balance === null ? '—' : balance?.toLocaleString()}
            </p>
            <div className="mt-2 h-1.5 bg-slate-100 rounded-full overflow-hidden">
              <div className="h-full rounded-full transition-all" style={{ width: `${pct}%`, backgroundColor: statusColor }} />
            </div>
            <p className="text-xs text-slate-400 mt-1.5">
              {isEmpty ? 'Top up now' : isLow ? 'Running low' : `of ${allocated?.toLocaleString()} mo`}
            </p>
          </div>
        </div>

        {/* Onboarding empty state */}
        {noData && <EmptyOnboarding onPlayground={() => navigate('/playground')} />}

        {/* Charts */}
        {!noData && (
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
              <Card className="lg:col-span-2">
                <CardHeader><CardTitle>Cost over time</CardTitle></CardHeader>
                <CardContent>
                  <ResponsiveContainer width="100%" height={200}>
                    <AreaChart data={costData?.costOverTime ?? []}>
                      <defs>
                        <linearGradient id="cg" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%"  stopColor="#2563EB" stopOpacity={0.12}/>
                          <stop offset="95%" stopColor="#2563EB" stopOpacity={0}/>
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9"/>
                      <XAxis dataKey="date" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false}/>
                      <YAxis tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} tickFormatter={v => `$${v.toFixed(3)}`}/>
                      <Tooltip content={<ChartTip fmt={v => formatCost(v)}/>}/>
                      <Area type="monotone" dataKey="totalCost" stroke="#2563EB" strokeWidth={2} fill="url(#cg)"/>
                    </AreaChart>
                  </ResponsiveContainer>
                </CardContent>
              </Card>
              <Card>
                <CardHeader><CardTitle>Cost by adapter</CardTitle></CardHeader>
                <CardContent>
                  <ResponsiveContainer width="100%" height={200}>
                    <BarChart data={costData?.costByAdapter ?? []} layout="vertical">
                      <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" horizontal={false}/>
                      <XAxis type="number" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} tickFormatter={v => `$${v.toFixed(2)}`}/>
                      <YAxis type="category" dataKey="adapterName" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} width={72}/>
                      <Tooltip content={<ChartTip fmt={v => formatCost(v)}/>}/>
                      <Bar dataKey="totalCost" fill="#2563EB" radius={[0, 4, 4, 0]}/>
                    </BarChart>
                  </ResponsiveContainer>
                </CardContent>
              </Card>
            </div>
        )}

        {/* Recent intents + compliance health */}
        <div className={`grid gap-4 ${noData ? 'grid-cols-1 lg:grid-cols-3' : 'grid-cols-1 xl:grid-cols-4'}`}>
          {!noData && (
              <Card className="xl:col-span-3">
                <CardHeader className="flex flex-row items-center justify-between">
                  <CardTitle>Recent intents</CardTitle>
                  <button onClick={() => navigate('/intents')} className="text-xs text-blue-600 hover:text-blue-700 font-medium">View all →</button>
                </CardHeader>
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                    <tr className="border-b border-slate-100">
                      {['ID', 'Type', 'Phase', 'Satisfaction', 'Cost', 'Ver', 'Created'].map(h => (
                          <th key={h} className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide whitespace-nowrap">{h}</th>
                      ))}
                    </tr>
                    </thead>
                    <tbody>
                    {loading && <tr><td colSpan={7} className="py-12 text-center"><Spinner className="mx-auto"/></td></tr>}
                    {content.map(intent => (
                        <tr key={intent.id}
                            className="border-b border-slate-50 hover:bg-blue-50/30 cursor-pointer transition-colors"
                            onClick={() => navigate(`/intents/${intent.id}`)}>
                          <td className="px-5 py-3"><span className="font-mono text-xs bg-slate-100 px-1.5 py-0.5 rounded">{shortId(intent.id)}</span></td>
                          <td className="px-5 py-3 text-xs text-slate-700">{intent.intentType}</td>
                          <td className="px-5 py-3"><PhaseBadge phase={intent.phase}/></td>
                          <td className="px-5 py-3"><SatisfactionBadge state={intent.satisfactionState}/></td>
                          <td className="px-5 py-3 text-xs tabular-nums text-slate-700">{formatCost(intent.budget?.spentUsd ?? 0)}</td>
                          {/* version — IntentSummaryDto field per IntentResource list endpoint */}
                          <td className="px-5 py-3 text-xs text-slate-400">v{intent.version ?? 1}</td>
                          <td className="px-5 py-3 text-xs text-slate-400" title={formatDate(intent.createdAt)}>{formatRelative(intent.createdAt)}</td>
                        </tr>
                    ))}
                    </tbody>
                  </table>
                </div>
              </Card>
          )}
          <ComplianceHealth />
        </div>

        {/* System status */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
          {[
            { label: 'API Gateway',      icon: <Server size={14}/> },
            { label: 'Redis Cache',      icon: <Cpu size={14}/> },
            { label: 'Execution Engine', icon: <Clock size={14}/> },
          ].map(({ label, icon }) => (
              <Card key={label} className="p-4 flex items-center gap-3">
                <div className="p-2 rounded-lg bg-green-50 text-green-600">{icon}</div>
                <div>
                  <p className="text-sm font-medium text-slate-700">{label}</p>
                  <p className="text-xs text-green-600">Operational</p>
                </div>
                <div className="ml-auto w-2 h-2 rounded-full bg-green-500 animate-pulse"/>
              </Card>
          ))}
        </div>
      </Page>
  );
}
