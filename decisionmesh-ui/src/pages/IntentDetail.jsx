import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ArrowLeft, RefreshCw, DollarSign, Target, Hash, Clock,
  Cpu, Shield, MessageSquare, AlertTriangle, CheckCircle,
  XCircle, ChevronDown, ChevronUp, Copy, Zap, BarChart2,
  Eye, EyeOff, RotateCcw,
} from 'lucide-react';
import Page from '../components/shared/Page';
import {
  Card, CardHeader, CardTitle, CardContent,
  Button, PhaseBadge, SatisfactionBadge, Spinner,
} from '../components/shared';
import ExecutionTimeline from '../components/timeline/ExecutionTimeline';
import { getIntent, getIntentEvents, getExecutionsByIntent, getPolicyEvaluations, listAdapters } from '../utils/api';
import { formatCost, formatDate, formatLatency, shortId, cn } from '../lib/utils';

// ── helpers ───────────────────────────────────────────────────────────────────

function Row({ label, value, mono = false }) {
  return (
    <div className="flex items-start py-2 border-b border-slate-50 last:border-0">
      <span className="text-sm text-slate-400 w-40 shrink-0 pt-0.5">{label}</span>
      <span className={cn('text-sm text-slate-700 font-medium flex-1 break-all',
        mono && 'font-mono bg-slate-100 px-1.5 py-0.5 rounded text-xs')}
        style={mono ? { fontFamily: "'JetBrains Mono', monospace" } : {}}>
        {value}
      </span>
    </div>
  );
}

function SectionHeader({ icon, title, badge }) {
  return (
    <div className="flex items-center gap-2">
      {icon}
      <CardTitle>{title}</CardTitle>
      {badge && (
        <span className="text-xs font-medium px-2 py-0.5 rounded-full bg-slate-100 text-slate-500">
          {badge}
        </span>
      )}
    </div>
  );
}

// ── Policy outcome card ───────────────────────────────────────────────────────
function PolicyOutcomeCard({ policyEvals, events, satisfactionState, violationReason, constraints }) {
  const isViolated = satisfactionState === 'VIOLATED';
  const isSatisfied = satisfactionState === 'SATISFIED';

  // Real data from policy_evaluations table
  const hasEvals   = policyEvals && policyEvals.length > 0;
  const blocked    = (policyEvals ?? []).filter(e => e.result === 'BLOCKED');
  const warnings   = (policyEvals ?? []).filter(e => e.result === 'WARNING');
  const allowed    = (policyEvals ?? []).filter(e => e.result === 'ALLOWED');
  const wasBlocked = blocked.length > 0;

  // Phase groupings
  const preEvals  = (policyEvals ?? []).filter(e =>
    e.phase === 'PRE_SUBMISSION' || e.phase === 'PRE_EXECUTION');
  const postEvals = (policyEvals ?? []).filter(e => e.phase === 'POST_EXECUTION');

  // Derive specific hints from violation + constraints (fallback when no evals yet)
  function deriveViolationHint() {
    const r = (violationReason ?? '').toLowerCase();
    const hints = [];
    if (r.includes('retry') || r.includes('sla')) {
      const max = constraints?.maxRetries;
      hints.push(max === 0
        ? 'maxRetries is 0 — set constraints.maxRetries ≥ 1 to allow retries'
        : `maxRetries = ${max} — retries were exhausted`);
    }
    if (r.includes('budget') || r.includes('ceiling') || r.includes('cost'))
      hints.push('budget.ceilingUsd is too low — increase it to cover the model cost');
    if (r.includes('timeout')) {
      const t = constraints?.timeoutSeconds;
      hints.push(t ? `timeoutSeconds = ${t}s — LLM calls typically need 5–30s`
        : 'timeoutSeconds is too low — increase constraints.timeoutSeconds');
    }
    if (r.includes('latency'))
      hints.push('maxLatencyMs is too tight — most LLM adapters need ≥ 400ms');
    if (r.includes('topic') || r.includes('block'))
      hints.push('A blocked topic appeared in the response — review policy.blockTopics');
    if (r.includes('model') || r.includes('allowed'))
      hints.push('The adapter model is not in policy.allowedModels');
    if (r.includes('drift'))
      hints.push('Output drift exceeded driftThreshold — raise the threshold or improve adapter quality');
    if (r.includes('inject'))
      hints.push('Prompt injection was detected — review the intent objective');
    if (hints.length === 0 && isViolated && constraints) {
      if (constraints.maxRetries === 0) hints.push('maxRetries is 0 — no retries allowed');
      if (constraints.timeoutSeconds <= 2) hints.push(`timeoutSeconds is only ${constraints.timeoutSeconds}s — too short for LLM execution`);
      if (constraints.maxLatencyMs && constraints.maxLatencyMs <= 100) hints.push(`maxLatencyMs is only ${constraints.maxLatencyMs}ms — too tight`);
    }
    return hints;
  }
  const specificHints = deriveViolationHint();

  const RESULT_COLORS = {
    BLOCKED: 'bg-red-50 text-red-700 border-red-200',
    WARNING: 'bg-amber-50 text-amber-700 border-amber-200',
    ALLOWED: 'bg-green-50 text-green-700 border-green-200',
  };
  const RESULT_ICONS = {
    BLOCKED: <XCircle size={12} />,
    WARNING: <AlertTriangle size={12} />,
    ALLOWED: <CheckCircle size={12} />,
  };

  return (
    <Card>
      <CardHeader>
        <SectionHeader
          icon={<Shield size={13} className="text-slate-400" />}
          title="Policy outcome"
          badge={
            !hasEvals && !isViolated ? 'No evaluations' :
            wasBlocked ? `${blocked.length} blocked` :
            warnings.length > 0 ? `${warnings.length} warning${warnings.length !== 1 ? 's' : ''}` :
            isSatisfied ? 'All passed' : undefined
          }
        />
      </CardHeader>
      <CardContent>

        {/* Overall verdict banner */}
        <div className={cn(
          'flex items-start gap-2.5 p-3 rounded-lg mb-3 border',
          isViolated   ? 'bg-red-50 text-red-800 border-red-200'
          : isSatisfied ? 'bg-green-50 text-green-800 border-green-200'
                        : 'bg-slate-50 text-slate-700 border-slate-200'
        )}>
          <div className="shrink-0 mt-0.5">
            {isViolated   ? <XCircle size={15} />
            : isSatisfied ? <CheckCircle size={15} />
                          : <Shield size={15} />}
          </div>
          <div className="flex-1">
            <p className="text-base font-semibold leading-tight">
              {isViolated
                ? (violationReason ?? (wasBlocked && blocked[0]?.blockReason)
                    ?? 'Intent violated — constraints not met')
                : isSatisfied ? 'All policy constraints satisfied'
                : 'Awaiting evaluation'}
            </p>
          </div>
        </div>

        {/* Specific actionable hints */}
        {isViolated && specificHints.length > 0 && (
          <div className="mb-3 space-y-1.5">
            {specificHints.map((hint, i) => (
              <div key={i} className="flex items-start gap-2 px-3 py-2 bg-amber-50 border border-amber-200 rounded-lg">
                <AlertTriangle size={11} className="text-amber-500 shrink-0 mt-0.5" />
                <p className="text-sm text-amber-800 leading-snug"><strong>Fix:</strong> {hint}</p>
              </div>
            ))}
          </div>
        )}

        {/* Real policy evaluation rows */}
        {hasEvals && (
          <div className="space-y-2 mb-3">
            {[
              { label: 'Pre-execution checks', evals: preEvals },
              { label: 'Post-execution checks', evals: postEvals },
            ].filter(g => g.evals.length > 0).map(group => (
              <div key={group.label}>
                <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-1.5">
                  {group.label}
                </p>
                {group.evals.map((pe, i) => (
                  <div key={pe.id ?? i}
                    className={cn(
                      'flex items-start justify-between py-2 px-3 rounded-lg border mb-1.5 text-xs',
                      RESULT_COLORS[pe.result] ?? 'bg-slate-50 text-slate-600 border-slate-100'
                    )}>
                    <div className="flex items-start gap-2">
                      <span className="mt-0.5 shrink-0">
                        {RESULT_ICONS[pe.result] ?? <Shield size={12} />}
                      </span>
                      <div>
                        <span className="font-medium">
                          {pe.result ?? 'UNKNOWN'}
                          {pe.enforcementMode && pe.enforcementMode !== 'ENFORCE' && (
                            <span className="ml-1.5 font-normal opacity-70">
                              ({pe.enforcementMode?.toLowerCase().replace(/_/g, ' ')})
                            </span>
                          )}
                        </span>
                        {pe.blockReason && (
                          <p className="mt-0.5 opacity-80 leading-snug">{pe.blockReason}</p>
                        )}
                      </div>
                    </div>
                    <div className="text-xs opacity-60 shrink-0 ml-2 text-right">
                      {pe.attemptNumber != null && <div>attempt #{pe.attemptNumber}</div>}
                      {pe.evaluatedAt && (
                        <div>{formatDate(pe.evaluatedAt)}</div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            ))}
          </div>
        )}

        {/* Constraints at-a-glance for violated intents */}
        {isViolated && constraints && (
          <div className="mt-3 pt-3 border-t border-slate-100">
            <p className="text-xs font-medium text-slate-500 uppercase tracking-wide mb-2">
              Submitted constraint values
            </p>
            <div className="grid grid-cols-2 gap-1.5">
              {[
                { label: 'maxRetries',     value: constraints.maxRetries,         warn: constraints.maxRetries === 0 },
                { label: 'timeoutSeconds', value: constraints.timeoutSeconds != null ? `${constraints.timeoutSeconds}s` : '—', warn: constraints.timeoutSeconds <= 2 },
                { label: 'maxLatencyMs',   value: constraints.maxLatencyMs != null ? `${constraints.maxLatencyMs}ms` : '—', warn: constraints.maxLatencyMs != null && constraints.maxLatencyMs <= 100 },
                { label: 'maxDrift',       value: constraints.maxDriftThreshold ?? '—', warn: constraints.maxDriftThreshold != null && constraints.maxDriftThreshold <= 0.01 },
              ].map(({ label, value, warn }) => (
                <div key={label}
                  className={cn(
                    'flex items-center justify-between px-2.5 py-1.5 rounded-lg text-xs border',
                    warn ? 'bg-red-50 border-red-200 text-red-700' : 'bg-slate-50 border-slate-100 text-slate-600'
                  )}>
                  <span className="font-mono">{label}</span>
                  <span className="font-semibold">{String(value)}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Empty state */}
        {!hasEvals && !isViolated && !isSatisfied && (
          <p className="text-sm text-slate-400 text-center py-3">
            Policy evaluations appear when the intent reaches EXECUTING phase.
          </p>
        )}

      </CardContent>
    </Card>
  );
}

// ── Adapter card ──────────────────────────────────────────────────────────────
function AdapterCard({ events, adapters, executions }) {
  // Try events first (adapterId from phase transition events)
  const execEvent = (events ?? []).find(e =>
    e.phaseTo === 'EXECUTING' || e.phaseFrom === 'EXECUTING' || e.adapterId
  );
  const eventAdapterId = execEvent?.adapterId;

  // Fall back to execution record — adapterId may be null if adapter wasn't
  // registered in DB, but adapterName is joined from the adapters table
  const execRecord = (executions ?? []).find(e =>
    e.status === 'SUCCESS' || e.status === 'COMPLETED'
  ) ?? executions?.[0];

  const adapterId   = eventAdapterId ?? execRecord?.adapterId;
  const adapterName = execRecord?.adapterName;

  // Look up full adapter from the adapters list.
  // Falls back to the only available adapter when the stored adapterId is stale
  // (e.g. intent ran before the real DB adapter was registered — execution engine
  // used a synthetic in-memory adapter whose UUID doesn't match any real row).
  // Only applies when exactly one adapter exists to avoid ambiguous matching.
  const adapter = adapters?.find(a => a.id === adapterId)
               ?? (adapters?.length === 1 ? adapters[0] : null);

  const allAttempts = (events ?? []).filter(e => e.attemptNumber != null);

  // Derive display info: prefer DB adapter record, fall back to execution record fields
  const displayName     = adapter?.name     ?? adapterName                                    ?? null;
  const displayProvider = adapter?.provider ?? execRecord?.provider ?? execRecord?.adapterProvider ?? null;
  const displayModel    = adapter?.modelId  ?? adapter?.modelName   ?? adapter?.model_id ?? adapter?.model ?? null;
  const displayRegion   = adapter?.region   ?? null;
  const displayActive   = adapter?.isActive ?? adapter?.is_active   ?? true;

  const hasAnyInfo = !!(displayName || adapterId || adapterName || execRecord);

  return (
    <Card>
      <CardHeader>
        <SectionHeader
          icon={<Cpu size={13} className="text-slate-400" />}
          title="Adapter"
        />
      </CardHeader>
      <CardContent>
        {hasAnyInfo ? (
          <>
            <div className="flex items-center gap-3 p-3 bg-slate-50 rounded-lg mb-3 border border-slate-200">
              <div className="p-2 rounded-lg bg-blue-50">
                <Cpu size={14} className="text-blue-600" />
              </div>
              <div>
                <p className="text-sm font-semibold text-slate-800">
                  {displayName ?? 'Adapter'}
                </p>
                {displayProvider && (
                  <p className="text-xs text-slate-500">
                    {displayProvider}{displayModel ? ` · ${displayModel}` : ''}
                  </p>
                )}
              </div>
              <span className={cn(
                'ml-auto text-xs font-medium px-2 py-0.5 rounded-full',
                displayActive
                  ? 'bg-green-50 text-green-700'
                  : 'bg-slate-100 text-slate-500'
              )}>
                {displayActive ? 'Active' : 'Inactive'}
              </span>
            </div>
            {adapterId && <Row label="Adapter ID" value={shortId(adapterId)} mono />}
            {displayProvider && <Row label="Provider" value={displayProvider} />}
            {displayModel    && <Row label="Model"    value={displayModel} />}
            {displayRegion   && <Row label="Region"   value={displayRegion} />}
            {!adapter && (
              <p className="text-xs text-slate-400 mt-2">
                Register this adapter at{' '}
                <a href="/adapters" className="text-blue-500 underline">Adapters →</a>{' '}
                to see full details.
              </p>
            )}
          </>
        ) : (
          <p className="text-sm text-slate-400">No adapter dispatched yet</p>
        )}

        {/* Attempt history */}
        {allAttempts.length > 0 && (
          <div className="mt-3 pt-3 border-t border-slate-100">
            <p className="text-xs font-medium text-slate-500 uppercase tracking-wide mb-2">
              Attempt history
            </p>
            {allAttempts.map((e, i) => (
              <div key={i} className="flex items-center justify-between py-1.5 text-xs border-b border-slate-50 last:border-0">
                <span className="text-slate-600">Attempt #{e.attemptNumber}</span>
                <div className="flex items-center gap-3 text-slate-400">
                  {e.costUsdSnapshot != null && (
                    <span className="flex items-center gap-1">
                      <DollarSign size={9} />{formatCost(e.costUsdSnapshot)}
                    </span>
                  )}
                  <span className="text-xs">{formatDate(e.occurredAt)}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

// ── Decision output card ──────────────────────────────────────────────────────
function DecisionOutputCard({ executions }) {
  const [showRaw,    setShowRaw]    = useState(false);
  const [copiedText, setCopiedText] = useState(false);

  // Use the most recent completed execution
  const exec = (executions ?? []).find(e => e.status === 'COMPLETED' || e.status === 'SUCCESS')
            ?? executions?.[0];

  if (!exec) {
    return (
      <Card>
        <CardHeader>
          <SectionHeader
            icon={<MessageSquare size={13} className="text-slate-400" />}
            title="Decision output"
          />
        </CardHeader>
        <CardContent>
          <p className="text-sm text-slate-400 text-center py-4">
            No execution record yet — output appears when execution completes.
          </p>
        </CardContent>
      </Card>
    );
  }

  const hasResponse = !!exec.responseText;
  const qScore      = exec.qualityScore ?? null;
  const hRisk       = exec.hallucinationRisk ?? null;
  const hDetected   = exec.hallucinationDetected ?? false;
  const reasoning   = exec.qualityReasoning ?? null;

  function copyResponse() {
    navigator.clipboard.writeText(exec.responseText ?? '');
    setCopiedText(true);
    setTimeout(() => setCopiedText(false), 2000);
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <SectionHeader
            icon={<MessageSquare size={13} className="text-slate-400" />}
            title="Decision output"
            badge={exec.status}
          />
          {hasResponse && (
            <button onClick={copyResponse}
              className="text-xs text-slate-400 hover:text-slate-600 flex items-center gap-1">
              <Copy size={10} />{copiedText ? 'Copied!' : 'Copy'}
            </button>
          )}
        </div>
      </CardHeader>
      <CardContent className="space-y-4">

        {/* Quality metrics row */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
          {[
            {
              label:  'Quality score',
              value:  qScore != null ? (qScore * 100).toFixed(0) + '%' : '—',
              color:  qScore >= 0.8 ? '#16a34a' : qScore >= 0.6 ? '#d97706' : '#dc2626',
              bg:     qScore >= 0.8 ? '#f0fdf4' : qScore >= 0.6 ? '#fffbeb' : '#fef2f2',
            },
            {
              label:  'Hallucination risk',
              value:  hRisk != null ? (hRisk * 100).toFixed(0) + '%' : '—',
              color:  hRisk <= 0.2 ? '#16a34a' : hRisk <= 0.5 ? '#d97706' : '#dc2626',
              bg:     hRisk <= 0.2 ? '#f0fdf4' : hRisk <= 0.5 ? '#fffbeb' : '#fef2f2',
            },
            {
              label:  'Latency',
              value:  formatLatency(exec.latencyMs),
              color:  '#2563eb',
              bg:     '#eff6ff',
            },
            {
              label:  'Cost',
              value:  exec.costUsd != null ? formatCost(exec.costUsd) : '—',
              color:  '#475569',
              bg:     '#f8fafc',
            },
          ].map(({ label, value, color, bg }) => (
            <div key={label} className="rounded-lg p-3 text-center border border-slate-100"
              style={{ backgroundColor: bg }}>
              <p className="text-xs text-slate-500 mb-1">{label}</p>
              <p className="text-base font-bold" style={{ color }}>{value}</p>
            </div>
          ))}
        </div>

        {/* Hallucination detected warning */}
        {hDetected && (
          <div className="flex items-start gap-2 p-3 bg-red-50 border border-red-200 rounded-lg">
            <AlertTriangle size={13} className="text-red-500 shrink-0 mt-0.5" />
            <div className="text-sm text-red-700">
              <p className="font-semibold mb-0.5">Hallucination detected</p>
              <p>The quality scorer flagged potential hallucination in this response.
                Review the output carefully before using it.</p>
            </div>
          </div>
        )}

        {/* Quality reasoning */}
        {reasoning && (
          <div className="p-3 bg-purple-50 border border-purple-100 rounded-lg">
            <p className="text-xs font-medium text-purple-700 mb-1.5 uppercase tracking-wide">
              Quality reasoning
            </p>
            <p className="text-sm text-purple-900 leading-relaxed">{reasoning}</p>
          </div>
        )}

        {/* AI response */}
        {hasResponse ? (
          <div>
            <div className="flex items-center justify-between mb-2">
              <p className="text-xs font-medium text-slate-500 uppercase tracking-wide">
                Adapter response
              </p>
              <button
                onClick={() => setShowRaw(v => !v)}
                className="text-xs text-slate-400 hover:text-slate-600 flex items-center gap-1">
                {showRaw ? <><EyeOff size={9} /> Collapse</> : <><Eye size={9} /> Expand</>}
              </button>
            </div>
            <div className={cn(
              'rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm text-slate-700 leading-relaxed overflow-hidden transition-all',
              showRaw ? '' : 'max-h-32'
            )}
              style={{ fontFamily: 'inherit' }}>
              {exec.responseText}
            </div>
            {!showRaw && exec.responseText?.length > 300 && (
              <button onClick={() => setShowRaw(true)}
                className="text-xs text-blue-500 mt-1 underline">
                Show full response
              </button>
            )}
          </div>
        ) : (
          <div className="text-center py-3">
            <p className="text-sm text-slate-400">
              Response text not available — adapter may be a mock or response was not stored.
            </p>
          </div>
        )}

        {/* Token counts if available */}
        {((exec.promptTokens > 0) || (exec.completionTokens > 0)) && (
          <div className="flex gap-4 pt-2 border-t border-slate-100 text-xs text-slate-400">
            {exec.promptTokens > 0 && (
              <span className="flex items-center gap-1">
                <Zap size={9} /> Prompt: {exec.promptTokens.toLocaleString()} tokens
              </span>
            )}
            {exec.completionTokens > 0 && (
              <span className="flex items-center gap-1">
                <Zap size={9} /> Completion: {exec.completionTokens.toLocaleString()} tokens
              </span>
            )}
            {exec.totalTokens > 0 && (
              <span className="flex items-center gap-1 font-medium text-slate-500">
                Total: {exec.totalTokens.toLocaleString()} tokens
              </span>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

// ── Main component ────────────────────────────────────────────────────────────
export default function IntentDetail({ keycloak }) {
  const { id }     = useParams();
  const navigate   = useNavigate();

  const [intent,       setIntent]       = useState(null);
  const [events,       setEvents]       = useState([]);
  const [executions,   setExecutions]   = useState([]);
  const [adapters,     setAdapters]     = useState([]);
  const [policyEvals,  setPolicyEvals]  = useState([]);
  const [loading,      setLoading]      = useState(true);
  const [fetching,     setFetching]     = useState(false);

  const load = useCallback(async () => {
    setFetching(true);
    try {
      const [intentData, eventsData, execData, adaptersData, policyData] =
        await Promise.allSettled([
          getIntent(keycloak, id),
          getIntentEvents(keycloak, id),
          getExecutionsByIntent(keycloak, id),
          listAdapters(keycloak),
          getPolicyEvaluations(keycloak, id),
        ]);
      if (intentData.value)   setIntent(intentData.value);
      if (eventsData.value)   setEvents(eventsData.value ?? []);
      if (execData.value)     setExecutions(execData.value ?? []);
      if (adaptersData.value) setAdapters(adaptersData.value ?? []);
      if (policyData.value)   setPolicyEvals(policyData.value ?? []);
    } finally {
      setLoading(false);
      setFetching(false);
    }
  }, [id, keycloak]);

  useEffect(() => {
    load();
  }, [load]);

  // Auto-refresh while non-terminal
  useEffect(() => {
    if (!intent || intent.terminal) return;
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [intent?.terminal, load]);

  if (loading) return (
    <Page title="Intent detail">
      <div className="flex justify-center py-24"><Spinner className="w-8 h-8" /></div>
    </Page>
  );

  if (!intent) return (
    <Page title="Intent detail">
      <Card className="p-12 text-center text-sm text-slate-500">Intent not found</Card>
    </Page>
  );

  const spentPct = (intent.budget?.ceilingUsd > 0)
    ? Math.min(100, (intent.budget.spentUsd / intent.budget.ceilingUsd) * 100)
    : 0;

  return (
    <Page
      title={`Intent ${shortId(intent.id)}`}
      subtitle={`Created ${formatDate(intent.createdAt)}`}
      action={
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={() => navigate('/intents')}>
            <ArrowLeft size={13} /> Back
          </Button>
          <Button variant="secondary" size="sm" loading={fetching} onClick={load}>
            <RefreshCw size={13} /> Refresh
          </Button>
        </div>
      }
    >
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">

        {/* ── Left column ── */}
        <div className="space-y-4">

          {/* Status */}
          <Card className="p-5">
            <div className="flex items-center gap-2 mb-4">
              <PhaseBadge phase={intent.phase} />
              <SatisfactionBadge state={intent.satisfactionState} />
              {!intent.terminal && (
                <span className="ml-auto flex items-center gap-1.5 text-xs text-blue-600">
                  <span className="w-1.5 h-1.5 rounded-full bg-blue-600 animate-pulse" />Live
                </span>
              )}
            </div>
            <Row label="Intent ID" value={
              <span className="font-mono text-xs bg-slate-100 px-1.5 py-0.5 rounded break-all"
                style={{ fontFamily: "'JetBrains Mono', monospace" }}>
                {intent.id}
              </span>
            } />
            <Row label="Type"     value={intent.intentType} />
            <Row label="Version"  value={`v${intent.version}`} />
            <Row label="Retries"  value={`${intent.retryCount ?? 0} / ${intent.maxRetries ?? 0}`} />
            <Row label="Terminal" value={intent.terminal ? 'Yes' : 'No'} />
            <Row label="Drift"    value={(intent.driftScore ?? 0).toFixed(4)} />
          </Card>

          {/* Budget — always shown */}
          <Card>
            <CardHeader>
              <SectionHeader icon={<DollarSign size={13} className="text-slate-400" />} title="Budget" />
            </CardHeader>
            <CardContent>
              {intent.budget ? (
                <>
                  <Row label="Ceiling"  value={formatCost(intent.budget.ceilingUsd)} />
                  <Row label="Spent"    value={formatCost(intent.budget.spentUsd)} />
                  <Row label="Currency" value={intent.budget.currency ?? 'USD'} />
                  <Row label="Exceeded" value={intent.budget.exceeded
                    ? <span className="text-red-600 font-semibold">Yes — ceiling hit</span>
                    : 'No'
                  } />
                  <div className="mt-3">
                    <div className="flex justify-between text-xs text-slate-400 mb-1">
                      <span>Spent</span>
                      <span className={spentPct >= 90 ? 'text-red-500 font-medium' : ''}>
                        {intent.budget?.ceilingUsd > 0 ? `${spentPct.toFixed(1)}%` : '—'}
                      </span>
                    </div>
                    <div className="h-1.5 bg-slate-100 rounded-full overflow-hidden">
                      <div className="h-full rounded-full transition-all"
                        style={{
                          width: `${spentPct}%`,
                          backgroundColor: spentPct >= 90 ? '#dc2626' : spentPct >= 70 ? '#d97706' : '#2563eb',
                        }} />
                    </div>
                  </div>
                </>
              ) : (
                <div className="flex flex-col items-center justify-center py-5 text-center">
                  <DollarSign size={20} className="text-slate-200 mb-2" />
                  <p className="text-sm text-slate-400">No budget defined</p>
                  <p className="text-xs text-slate-300 mt-0.5">
                    Add a <span className="font-mono">budget.ceilingUsd</span> to your payload to enforce spending limits
                  </p>
                </div>
              )}
            </CardContent>
          </Card>

          {/* Constraints — always shown */}
          <Card>
            <CardHeader>
              <SectionHeader icon={<Target size={13} className="text-slate-400" />} title="Constraints" />
            </CardHeader>
            <CardContent>
              {intent.constraints ? (
                <>
                  <Row label="Max latency"  value={intent.constraints.maxLatency
                    ? `${intent.constraints.maxLatency}ms` : '—'} />
                  <Row label="Max latency ms" value={intent.constraints.maxLatencyMs
                    ? `${intent.constraints.maxLatencyMs}ms` : '—'} />
                  <Row label="Max retries"  value={intent.constraints.maxRetries ?? '—'} />
                  <Row label="Timeout"      value={intent.constraints.timeoutSeconds
                    ? `${intent.constraints.timeoutSeconds}s` : '—'} />
                  <Row label="Drift limit"  value={intent.constraints.maxDriftThreshold != null
                    ? intent.constraints.maxDriftThreshold.toFixed(2) : '—'} />
                </>
              ) : (
                <div className="flex flex-col items-center justify-center py-5 text-center">
                  <Target size={20} className="text-slate-200 mb-2" />
                  <p className="text-sm text-slate-400">No constraints defined</p>
                  <p className="text-xs text-slate-300 mt-0.5">
                    Add <span className="font-mono">constraints</span> to set retries, timeout, and latency limits
                  </p>
                </div>
              )}
            </CardContent>
          </Card>

          {/* Objective — always shown */}
          <Card>
            <CardHeader>
              <SectionHeader icon={<Hash size={13} className="text-slate-400" />} title="Objective" />
            </CardHeader>
            <CardContent>
              {intent.objective ? (() => {
                const obj = intent.objective;
                const description = obj.description ?? obj.task ?? obj.goal ?? null;
                const hasExtra = obj.objectiveType || obj.taskType || obj.successCriteria?.length > 0 || obj.targetThreshold > 0;
                return (
                  <div className="space-y-3">
                    {description ? (
                      <p className="text-sm text-slate-700 leading-relaxed">{description}</p>
                    ) : (
                      <p className="text-sm text-slate-400 italic">No description provided</p>
                    )}
                    {hasExtra && (
                      <div className="grid grid-cols-2 gap-2">
                        {obj.objectiveType && <Row label="Type"      value={obj.objectiveType} />}
                        {obj.taskType      && <Row label="Task type" value={obj.taskType} />}
                        {obj.targetThreshold > 0 && <Row label="Threshold" value={obj.targetThreshold} />}
                        {obj.tolerance     > 0    && <Row label="Tolerance" value={obj.tolerance} />}
                      </div>
                    )}
                    {obj.successCriteria?.length > 0 && (
                      <div>
                        <p className="text-xs font-medium text-slate-500 uppercase tracking-wide mb-1">
                          Success criteria
                        </p>
                        <ul className="space-y-1">
                          {obj.successCriteria.map((c, i) => (
                            <li key={i} className="flex items-start gap-1.5 text-sm text-slate-600">
                              <CheckCircle size={11} className="text-green-500 shrink-0 mt-0.5" />
                              {c}
                            </li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </div>
                );
              })() : (
                <div className="flex flex-col items-center justify-center py-5 text-center">
                  <Hash size={20} className="text-slate-200 mb-2" />
                  <p className="text-sm text-slate-400">No objective defined</p>
                  <p className="text-xs text-slate-300 mt-0.5">
                    Add an <span className="font-mono">objective</span> block to describe the intent goal
                  </p>
                </div>
              )}
            </CardContent>
          </Card>
        </div>

        {/* ── Right column ── */}
        <div className="lg:col-span-2 space-y-4">

          {/* Decision output — NEW */}
          <DecisionOutputCard executions={executions} />

          {/* Policy outcome */}
          <PolicyOutcomeCard
            policyEvals={policyEvals}
            events={events}
            satisfactionState={intent.satisfactionState}
            violationReason={intent.violationReason}
            constraints={intent.constraints}
          />

          {/* Adapter detail */}
          <AdapterCard events={events} adapters={adapters} executions={executions} />

          {/* Execution timeline */}
          <Card>
            <CardHeader>
              <div className="flex items-center gap-2">
                <Clock size={13} className="text-slate-400" />
                <CardTitle>Execution timeline</CardTitle>
                {!intent.terminal && (
                  <span className="ml-auto flex items-center gap-1.5 text-xs text-blue-600">
                    <span className="w-1.5 h-1.5 rounded-full bg-blue-600 animate-pulse" />Live
                  </span>
                )}
              </div>
            </CardHeader>
            <CardContent>
              <ExecutionTimeline
                keycloak={keycloak}
                intentId={intent.id}
                currentPhase={intent.phase}
                terminal={intent.terminal}
                satisfied={intent.satisfactionState === 'SATISFIED'}
              />
            </CardContent>
          </Card>

        </div>
      </div>
    </Page>
  );
}
