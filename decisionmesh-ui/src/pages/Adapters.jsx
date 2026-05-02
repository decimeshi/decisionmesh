import { useState, useEffect, useCallback } from 'react';
import {
  Plus, Puzzle, Edit2, AlertTriangle, CheckCircle2,
  Zap, Activity, TrendingUp, Shield, RefreshCw, Info,
} from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, Button, EmptyState, Spinner, cn } from '../components/shared';
import { listAdapters, toggleAdapter, createAdapter, updateAdapter, getAdapterPerformance, ApiError } from '../utils/api';
import { formatDate, formatRelative } from '../lib/utils';

// ─── Provider catalogue ────────────────────────────────────────────────────────
//
// provider() string must match EXACTLY what each LlmAdapter.provider() returns —
// the execution engine does: adaptersByProvider.get(provider.toUpperCase())
// Case-insensitive match on the server, but stored and displayed in uppercase.
//
const PROVIDER_META = {
  OPENAI: {
    label:       'OpenAI',
    color:       '#10a37f',
    bg:          '#f0fdf4',
    border:      '#bbf7d0',
    // Models from OpenAILlmAdapter javadoc
    models:      ['gpt-4o', 'gpt-4o-mini', 'gpt-4-turbo', 'gpt-3.5-turbo'],
    defaultModel:'gpt-4o',
    authNote:    'Authorization: Bearer — configure llm.openai.api-key',
    endpoint:    'https://api.openai.com/v1/chat/completions',
    // Fields read via step.getConfigString/Int/Double/Long in OpenAILlmAdapter.execute()
    defaultConfig: {
      model:       'gpt-4o',
      max_tokens:  1024,
      temperature: 0.2,
      timeout_ms:  30000,
    },
  },
  ANTHROPIC: {
    label:       'Anthropic',
    color:       '#c8522a',
    bg:          '#fff7f5',
    border:      '#fed7aa',
    // Models from AnthropicLlmAdapter javadoc
    models:      [
      'claude-3-5-sonnet-20241022',
      'claude-3-5-haiku-20241022',
      'claude-3-opus-20240229',
      'claude-3-haiku-20240307',
    ],
    defaultModel:'claude-3-5-sonnet-20241022',
    authNote:    'x-api-key + anthropic-version headers — configure llm.anthropic.api-key',
    endpoint:    'https://api.anthropic.com/v1/messages',
    // AnthropicLlmAdapter has no temperature param — omit from template
    defaultConfig: {
      model:      'claude-3-5-sonnet-20241022',
      max_tokens: 1024,
      timeout_ms: 30000,
    },
  },
  // ⚠ Provider string is GEMINI, NOT GOOGLE — matches GeminiLlmAdapter.provider()
  GEMINI: {
    label:       'Google Gemini',
    color:       '#4285f4',
    bg:          '#eff6ff',
    border:      '#bfdbfe',
    // Models from GeminiLlmAdapter javadoc
    models:      ['gemini-2.0-flash', 'gemini-1.5-pro', 'gemini-1.5-flash'],
    defaultModel:'gemini-2.0-flash',
    authNote:    'API key as ?key= query param — configure llm.gemini.api-key',
    endpoint:    'https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent',
    // Fields read via step.getConfigString/Int/Double/Long in GeminiLlmAdapter.execute()
    defaultConfig: {
      model:       'gemini-2.0-flash',
      max_tokens:  1024,
      temperature: 0.2,
      timeout_ms:  30000,
    },
  },
  // ⚠ Provider string is AZURE_OPENAI, NOT AZURE — matches AzureOpenAILlmAdapter.provider()
  AZURE_OPENAI: {
    label:       'Azure OpenAI',
    color:       '#0078d4',
    bg:          '#f0f8ff',
    border:      '#bae6fd',
    // AzureOpenAILlmAdapter javadoc: any model deployed under an Azure resource
    models:      ['gpt-4o', 'gpt-4o-mini', 'gpt-4-turbo', 'o1', 'o3-mini'],
    defaultModel:'gpt-4o',
    authNote:    'api-key header (Azure-specific, not Bearer) — configure llm.azure.api-key',
    endpoint:    'https://{resource_name}.openai.azure.com/openai/deployments/{deployment_name}/chat/completions',
    // Azure URL encodes the DEPLOYMENT NAME, not the model name.
    // resource_name, deployment_name, api_version are Azure-specific required fields.
    defaultConfig: {
      resource_name:   '',
      deployment_name: 'gpt-4o',
      api_version:     '2024-12-01-preview',
      model:           'gpt-4o',   // cost lookup only — not sent in Azure request body
      max_tokens:      1024,
      temperature:     0.2,
      timeout_ms:      30000,
    },
    azureSpecific: true,  // triggers Azure field helpers in the modal
  },
  // DeepSeek — NEW, not in previous UI
  DEEPSEEK: {
    label:       'DeepSeek',
    color:       '#7c3aed',
    bg:          '#f5f3ff',
    border:      '#ddd6fe',
    // Models from DeepSeekLlmAdapter javadoc
    models:      ['deepseek-chat', 'deepseek-reasoner'],
    defaultModel:'deepseek-chat',
    authNote:    'Authorization: Bearer — configure llm.deepseek.api-key',
    endpoint:    'https://api.deepseek.com/v1/chat/completions',
    // deepseek-reasoner returns reasoning_content + content — higher timeout by default
    defaultConfig: {
      model:       'deepseek-chat',
      max_tokens:  2048,
      temperature: 0.0,
      timeout_ms:  60000,  // 60s default — reasoner can be slow
    },
  },
  CUSTOM: {
    label:       'Custom',
    color:       '#64748b',
    bg:          '#f8fafc',
    border:      '#e2e8f0',
    models:      [],
    defaultModel:'',
    authNote:    'Auth configured via config JSON',
    endpoint:    '',
    defaultConfig: {
      endpoint:   '',
      model:      '',
      max_tokens: 1024,
      timeout_ms: 30000,
    },
  },
};

const PROVIDERS     = Object.keys(PROVIDER_META);
const ADAPTER_TYPES = ['LLM', 'TOOL', 'EMBEDDING', 'RERANKER'];
// Intent types matched against adapters.allowed_intent_types JSONB in AdapterRegistry
const INTENT_TYPES  = ['SUMMARIZATION', 'CHAT', 'CLASSIFICATION', 'CUSTOM'];

// ─── Circuit breaker threshold — mirrors llm.selector.circuit-breaker-threshold ─
const CIRCUIT_BREAKER_THRESHOLD = 5;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function providerMeta(provider) {
  return PROVIDER_META[provider?.toUpperCase()] ?? PROVIDER_META.CUSTOM;
}

function formatScore(v) {
  if (v == null) return '—';
  return (v * 100).toFixed(0) + '%';
}
function formatLatency(ms) {
  if (ms == null) return '—';
  return ms >= 1000 ? (ms / 1000).toFixed(1) + 's' : Math.round(ms) + 'ms';
}
function formatCostUsd(v) {
  if (v == null) return '—';
  return '$' + v.toFixed(5);
}

// ─── Status badges ─────────────────────────────────────────────────────────────

function StatusBadge({ perf }) {
  if (!perf) return null;

  // Circuit breaker: degraded AND enough data to trust the signal
  if (perf.isDegraded && perf.executionCount > CIRCUIT_BREAKER_THRESHOLD) {
    return (
        <span className="inline-flex items-center gap-1 text-[10px] font-semibold px-2 py-0.5 rounded-full bg-red-100 text-red-700">
        <AlertTriangle size={9} /> Degraded
      </span>
    );
  }
  // Cold start — no execution history yet
  if (perf.coldStart || perf.executionCount === 0) {
    return (
        <span className="inline-flex items-center gap-1 text-[10px] font-semibold px-2 py-0.5 rounded-full bg-slate-100 text-slate-500">
        Cold start
      </span>
    );
  }
  return (
      <span className="inline-flex items-center gap-1 text-[10px] font-semibold px-2 py-0.5 rounded-full bg-green-100 text-green-700">
      <CheckCircle2 size={9} /> Healthy
    </span>
  );
}

// ─── Performance mini-panel ────────────────────────────────────────────────────

function PerformancePanel({ perf }) {
  if (!perf || perf.executionCount === 0) {
    return (
        <p className="text-[10px] text-slate-400 italic mt-3">
          No execution history yet — cold-start priors active
        </p>
    );
  }

  const scoreColor =
      perf.compositeScore >= 0.75 ? '#16a34a' :
          perf.compositeScore >= 0.50 ? '#d97706' : '#dc2626';

  return (
      <div className="mt-3 pt-3 border-t border-slate-100 space-y-2">
        {/* Composite score bar */}
        <div>
          <div className="flex justify-between text-[10px] text-slate-500 mb-0.5">
            <span>Composite score</span>
            <span style={{ color: scoreColor }} className="font-semibold">
            {formatScore(perf.compositeScore)}
          </span>
          </div>
          <div className="h-1.5 bg-slate-100 rounded-full overflow-hidden">
            <div
                className="h-full rounded-full transition-all"
                style={{ width: `${Math.min(100, (perf.compositeScore ?? 0) * 100)}%`, backgroundColor: scoreColor }}
            />
          </div>
        </div>
        {/* Stats row */}
        <div className="grid grid-cols-3 gap-2 text-[10px]">
          <div>
            <p className="text-slate-400">Success</p>
            <p className="font-semibold text-slate-600">{formatScore(perf.emaSuccessRate)}</p>
          </div>
          <div>
            <p className="text-slate-400">P50 latency</p>
            <p className="font-semibold text-slate-600">{formatLatency(perf.emaLatencyMs)}</p>
          </div>
          <div>
            <p className="text-slate-400">Avg cost</p>
            <p className="font-semibold text-slate-600">{formatCostUsd(perf.emaCostPerCall)}</p>
          </div>
        </div>
        {/* Execution count */}
        <p className="text-[10px] text-slate-400">
          {perf.executionCount.toLocaleString()} executions
          {perf.isDegraded && perf.executionCount > CIRCUIT_BREAKER_THRESHOLD && (
              <span className="ml-2 text-red-500">
            · Circuit breaker active (emaSuccess &lt; 60%)
          </span>
          )}
        </p>
      </div>
  );
}

// ─── Adapter card ─────────────────────────────────────────────────────────────

function AdapterCard({ adapter, perf, onToggle, onEdit }) {
  const meta = providerMeta(adapter.provider);
  const allowedTypes = Array.isArray(adapter.allowedIntentTypes)
      ? adapter.allowedIntentTypes
      : [];

  return (
      <Card className="p-5 flex flex-col">
        {/* Header row */}
        <div className="flex items-start justify-between mb-3">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 flex-wrap mb-1">
              <p className="text-sm font-semibold text-slate-800 truncate">{adapter.name}</p>
              <StatusBadge perf={perf} />
            </div>
            {/* Provider + type badges */}
            <div className="flex items-center gap-1.5 flex-wrap">
            <span
                className="text-[10px] font-semibold px-1.5 py-0.5 rounded"
                style={{ backgroundColor: meta.bg, color: meta.color, border: `1px solid ${meta.border}` }}>
              {meta.label}
            </span>
              <span className="text-[10px] font-medium px-1.5 py-0.5 rounded bg-slate-100 text-slate-600">
              {adapter.adapterType}
            </span>
              {adapter.region && (
                  <span className="text-[10px] font-medium px-1.5 py-0.5 rounded bg-indigo-50 text-indigo-600">
                {adapter.region}
              </span>
              )}
            </div>
          </div>
          {/* Active toggle */}
          <button
              onClick={() => onToggle(adapter)}
              title={adapter.isActive ? 'Disable adapter' : 'Enable adapter'}
              className={`w-9 h-5 rounded-full transition-colors relative shrink-0 ml-2 ${adapter.isActive ? 'bg-blue-600' : 'bg-slate-200'}`}>
            <span className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform ${adapter.isActive ? 'translate-x-4' : 'translate-x-0.5'}`} />
          </button>
        </div>

        {/* Key fields */}
        <div className="space-y-1 text-xs text-slate-500 flex-1">
          <div className="flex gap-2">
            <span className="text-slate-400 w-20 shrink-0">Model</span>
            <span className="font-mono truncate" style={{ fontFamily: "'JetBrains Mono', monospace" }}>
            {adapter.modelId || '—'}
          </span>
          </div>
          {/* Azure: show deployment name if present */}
          {adapter.provider?.toUpperCase() === 'AZURE_OPENAI' && adapter.config?.deployment_name && (
              <div className="flex gap-2">
                <span className="text-slate-400 w-20 shrink-0">Deployment</span>
                <span className="font-mono truncate" style={{ fontFamily: "'JetBrains Mono', monospace" }}>
              {adapter.config.deployment_name}
            </span>
              </div>
          )}
          <div className="flex gap-2">
            <span className="text-slate-400 w-20 shrink-0">ID</span>
            <span className="font-mono" style={{ fontFamily: "'JetBrains Mono', monospace" }}>
            {adapter.id?.split('-')[0]}
          </span>
          </div>
          <div className="flex gap-2">
            <span className="text-slate-400 w-20 shrink-0">Created</span>
            <span>{formatDate(adapter.createdAt)}</span>
          </div>
          {/* Allowed intent types — empty [] = all types (AdapterRegistry SQL) */}
          {allowedTypes.length > 0 && (
              <div className="flex gap-2 flex-wrap pt-1">
                <span className="text-slate-400 w-20 shrink-0">Types</span>
                <div className="flex gap-1 flex-wrap">
                  {allowedTypes.map(t => (
                      <span key={t} className="text-[10px] bg-blue-50 text-blue-700 px-1.5 py-0.5 rounded font-medium">
                  {t}
                </span>
                  ))}
                </div>
              </div>
          )}
        </div>

        {/* EMA performance panel */}
        <PerformancePanel perf={perf} />

        {/* Footer */}
        <div className="mt-4 flex justify-end">
          <Button variant="ghost" size="sm" onClick={() => onEdit(adapter)}>
            <Edit2 size={13} /> Edit config
          </Button>
        </div>
      </Card>
  );
}

// ─── Adapter modal (create / edit) ────────────────────────────────────────────

const DEFAULT_FORM = {
  name:               '',
  provider:           'OPENAI',
  modelId:            'gpt-4o',
  adapterType:        'LLM',
  region:             '',
  allowedIntentTypes: [],  // [] = all intent types — AdapterRegistry WHERE allowed_intent_types = '[]'::jsonb
  config:             JSON.stringify(PROVIDER_META.OPENAI.defaultConfig, null, 2),
  // capabilityFlags is a real nullable=false jsonb column on AdapterEntity.
  // Must be sent as {} (empty object) when not used — never omit it.
  capabilityFlags:    '{}',
  isActive:           true,
};

function AdapterModal({ adapter, onSave, onClose }) {
  const isEdit = !!adapter;
  const [form, setForm] = useState(() => {
    if (!adapter) return DEFAULT_FORM;
    return {
      ...adapter,
      allowedIntentTypes: Array.isArray(adapter.allowedIntentTypes)
          ? adapter.allowedIntentTypes
          : [],
      // capabilityFlags comes through via ...adapter spread.
      // Normalise here in case the server returns null (nullable=false but old data).
      capabilityFlags: (adapter.capabilityFlags && typeof adapter.capabilityFlags === 'object')
          ? adapter.capabilityFlags
          : {},
      config: JSON.stringify(adapter.config ?? {}, null, 2),
    };
  });
  const [saving,  setSaving]  = useState(false);
  const [jsonErr, setJsonErr] = useState(null);

  // When provider changes: update modelId default and config template
  function handleProviderChange(newProvider) {
    const meta = providerMeta(newProvider);
    setForm(f => ({
      ...f,
      provider: newProvider,
      modelId:  meta.defaultModel,
      config:   JSON.stringify(meta.defaultConfig, null, 2),
    }));
    setJsonErr(null);
  }

  function handleConfigChange(raw) {
    setForm(f => ({ ...f, config: raw }));
    try { JSON.parse(raw); setJsonErr(null); }
    catch { setJsonErr('Invalid JSON'); }
  }

  // Merge dedicated fields (model, max_tokens, etc.) back into the config JSON
  function handleModelIdChange(v) {
    setForm(f => {
      // Keep config JSON in sync with the modelId field
      try {
        const cfg = JSON.parse(f.config);
        cfg.model = v;
        return { ...f, modelId: v, config: JSON.stringify(cfg, null, 2) };
      } catch { return { ...f, modelId: v }; }
    });
  }

  function toggleIntentType(type) {
    setForm(f => ({
      ...f,
      allowedIntentTypes: f.allowedIntentTypes.includes(type)
          ? f.allowedIntentTypes.filter(t => t !== type)
          : [...f.allowedIntentTypes, type],
    }));
  }

  async function handleSave() {
    let cfg;
    try {
      cfg = JSON.parse(form.config);
    } catch {
      setJsonErr('Fix JSON before saving');
      return;
    }

    setSaving(true);
    try {
      await onSave({
        ...form,

        // config → JSONB map column on AdapterEntity. Must be an object, never array.
        config: (cfg && typeof cfg === 'object' && !Array.isArray(cfg)) ? cfg : {},

        // capabilityFlags → JSONB map column on AdapterEntity, nullable = false.
        // Must always be sent as an object. The field exists on the entity — omitting
        // it causes a NOT NULL constraint violation on INSERT / UPDATE.
        capabilityFlags: (() => {
          try {
            const raw = form.capabilityFlags;
            // If it was stored as a JSON string in state (DEFAULT_FORM), parse it first
            const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
            return (parsed && typeof parsed === 'object' && !Array.isArray(parsed))
                ? parsed : {};
          } catch { return {}; }
        })(),

        // allowedIntentTypes → JSONB *array* column (allowed_intent_types jsonb).
        // Entity field is List<String>. Sending [] means "all intent types eligible"
        // — matches AdapterRegistry WHERE allowed_intent_types = '[]'::jsonb.
        allowedIntentTypes:
            Array.isArray(form.allowedIntentTypes)
                ? form.allowedIntentTypes
                : [],
      });

      onClose();
    } catch (e) {
      // Show the error inline in the modal — keep it open so the user can retry.
      // 401 gets a specific message pointing at the Token Debugger.
      const msg = e instanceof ApiError && e.isAuth
        ? `Not authorised (${e.status}) — verify email in Keycloak or check /debug/token`
        : (e?.message ?? 'Save failed — please try again');
      setJsonErr(msg);
    } finally {
      setSaving(false);
    }
  }

  const meta     = providerMeta(form.provider);
  const isAzure  = form.provider?.toUpperCase() === 'AZURE_OPENAI';

  // Parse current config for Azure-specific dedicated inputs
  let parsedConfig = {};
  try { parsedConfig = JSON.parse(form.config); } catch { /**/ }

  function patchConfig(key, val) {
    setForm(f => {
      try {
        const cfg = JSON.parse(f.config);
        cfg[key] = val;
        return { ...f, config: JSON.stringify(cfg, null, 2) };
      } catch { return f; }
    });
  }

  return (
      <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4 overflow-y-auto">
        <Card className="w-full max-w-2xl my-4">
          {/* Header */}
          <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between">
            <div>
              <h3 className="text-sm font-semibold text-slate-800">
                {isEdit ? 'Edit adapter' : 'Add adapter'}
              </h3>
              {!isEdit && (
                  <p className="text-xs text-slate-400 mt-0.5">
                    Selecting a provider auto-fills the config template with correct field names.
                  </p>
              )}
            </div>
            <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-lg leading-none">×</button>
          </div>

          <div className="p-5 space-y-5">
            {/* ── Row 1: Name + Active ── */}
            <div className="flex gap-4">
              <div className="flex-1">
                <label className="block text-xs font-medium text-slate-600 mb-1.5">Name *</label>
                <input
                    value={form.name}
                    onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                    placeholder="e.g. GPT-4o Production"
                    className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>
              <div className="flex items-end pb-2">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                      type="checkbox"
                      checked={form.isActive}
                      onChange={e => setForm(f => ({ ...f, isActive: e.target.checked }))}
                      className="rounded"
                  />
                  <span className="text-sm text-slate-700">Active</span>
                </label>
              </div>
            </div>

            {/* ── Row 2: Provider + Adapter type ── */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-medium text-slate-600 mb-1.5">Provider *</label>
                <select
                    value={form.provider}
                    onChange={e => handleProviderChange(e.target.value)}
                    className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white">
                  {PROVIDERS.map(p => (
                      <option key={p} value={p}>{providerMeta(p).label} ({p})</option>
                  ))}
                </select>
                {/* Auth note from provider */}
                {meta.authNote && (
                    <p className="text-[10px] text-slate-400 mt-1 flex items-start gap-1">
                      <Info size={9} className="shrink-0 mt-0.5" /> {meta.authNote}
                    </p>
                )}
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-600 mb-1.5">Adapter type</label>
                <select
                    value={form.adapterType}
                    onChange={e => setForm(f => ({ ...f, adapterType: e.target.value }))}
                    className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white">
                  {ADAPTER_TYPES.map(t => <option key={t}>{t}</option>)}
                </select>
                <p className="text-[10px] text-slate-400 mt-1">
                  AdapterRegistry only loads adapter_type = 'LLM' for intent routing
                </p>
              </div>
            </div>

            {/* ── Row 3: Model ID (with provider-specific suggestions) ── */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-medium text-slate-600 mb-1.5">
                  Model ID *
                  {isAzure && <span className="text-slate-400 font-normal"> (cost lookup — not sent in Azure request body)</span>}
                </label>
                <input
                    list={`models-${form.provider}`}
                    value={form.modelId}
                    onChange={e => handleModelIdChange(e.target.value)}
                    placeholder={meta.defaultModel || 'Enter model identifier'}
                    className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
                    style={{ fontFamily: "'JetBrains Mono', monospace" }}
                />
                {/* Datalist provides model suggestions without locking the input */}
                <datalist id={`models-${form.provider}`}>
                  {meta.models.map(m => <option key={m} value={m} />)}
                </datalist>
                {meta.models.length > 0 && (
                    <div className="flex flex-wrap gap-1 mt-1.5">
                      {meta.models.map(m => (
                          <button
                              key={m}
                              type="button"
                              onClick={() => handleModelIdChange(m)}
                              className={`text-[10px] px-1.5 py-0.5 rounded border transition-colors ${
                                  form.modelId === m
                                      ? 'border-blue-500 bg-blue-50 text-blue-700'
                                      : 'border-slate-200 text-slate-500 hover:border-blue-300'
                              }`}>
                            {m}
                          </button>
                      ))}
                    </div>
                )}
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-600 mb-1.5">
                  Region
                  <span className="text-slate-400 font-normal"> (optional)</span>
                </label>
                <input
                    value={form.region}
                    onChange={e => setForm(f => ({ ...f, region: e.target.value }))}
                    placeholder="e.g. eu-west-1 (LlmModelSelector region filter)"
                    className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
                <p className="text-[10px] text-slate-400 mt-1">
                  Matched against intent constraints.region() in LlmModelSelector
                </p>
              </div>
            </div>

            {/* ── Azure-specific fields ── */}
            {isAzure && (
                <div className="rounded-xl border border-blue-200 bg-blue-50/50 p-4 space-y-3">
                  <p className="text-xs font-semibold text-blue-700 flex items-center gap-1.5">
                    <Shield size={12} /> Azure OpenAI — required fields
                  </p>
                  <p className="text-[10px] text-blue-600">
                    Azure encodes the deployment name in the URL, not the model name.
                    These fields are read by AzureOpenAILlmAdapter via step.getConfigString().
                  </p>
                  <div className="grid grid-cols-3 gap-3">
                    <div>
                      <label className="block text-[10px] font-medium text-slate-600 mb-1">
                        Resource name *
                      </label>
                      <input
                          value={parsedConfig.resource_name ?? ''}
                          onChange={e => patchConfig('resource_name', e.target.value)}
                          placeholder="my-azure-resource"
                          className="w-full text-xs border border-slate-200 rounded-lg px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-blue-500"
                          style={{ fontFamily: "'JetBrains Mono', monospace" }}
                      />
                    </div>
                    <div>
                      <label className="block text-[10px] font-medium text-slate-600 mb-1">
                        Deployment name *
                      </label>
                      <input
                          value={parsedConfig.deployment_name ?? ''}
                          onChange={e => patchConfig('deployment_name', e.target.value)}
                          placeholder="gpt-4o-prod"
                          className="w-full text-xs border border-slate-200 rounded-lg px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-blue-500"
                          style={{ fontFamily: "'JetBrains Mono', monospace" }}
                      />
                    </div>
                    <div>
                      <label className="block text-[10px] font-medium text-slate-600 mb-1">
                        API version
                      </label>
                      <input
                          value={parsedConfig.api_version ?? '2024-12-01-preview'}
                          onChange={e => patchConfig('api_version', e.target.value)}
                          className="w-full text-xs border border-slate-200 rounded-lg px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-blue-500"
                          style={{ fontFamily: "'JetBrains Mono', monospace" }}
                      />
                    </div>
                  </div>
                </div>
            )}

            {/* ── Allowed intent types ── */}
            <div>
              <label className="block text-xs font-medium text-slate-600 mb-1.5">
                Allowed intent types
                <span className="text-slate-400 font-normal ml-1">
                (empty = all types — AdapterRegistry WHERE allowed_intent_types = '[]')
              </span>
              </label>
              <div className="flex gap-2 flex-wrap">
                {INTENT_TYPES.map(type => (
                    <button
                        key={type}
                        type="button"
                        onClick={() => toggleIntentType(type)}
                        className={`px-3 py-1.5 rounded-lg text-xs font-medium border transition-colors ${
                            form.allowedIntentTypes.includes(type)
                                ? 'bg-blue-600 text-white border-blue-600'
                                : 'bg-white text-slate-600 border-slate-200 hover:border-blue-300'
                        }`}>
                      {type}
                    </button>
                ))}
              </div>
              {form.allowedIntentTypes.length === 0 && (
                  <p className="text-[10px] text-slate-400 mt-1">
                    All intent types eligible — adapter competes for any intent type
                  </p>
              )}
            </div>

            {/* ── Config JSON (full provider-specific config snapshot) ── */}
            <div>
              <div className="flex items-center justify-between mb-1.5">
                <label className="text-xs font-medium text-slate-600">
                  Config snapshot (JSON)
                </label>
                <button
                    type="button"
                    onClick={() => {
                      const freshCfg = { ...meta.defaultConfig };
                      if (form.modelId) freshCfg.model = form.modelId;
                      setForm(f => ({ ...f, config: JSON.stringify(freshCfg, null, 2) }));
                      setJsonErr(null);
                    }}
                    className="text-[10px] text-blue-600 hover:text-blue-800 flex items-center gap-1">
                  <RefreshCw size={10} /> Reset to {meta.label} template
                </button>
              </div>
              <p className="text-[10px] text-slate-400 mb-2">
                Stored as config_snapshot in adapters table. Each adapter reads specific keys
                via step.getConfigString/getConfigInt/getConfigDouble/getConfigLong.
                {isAzure && ' Azure fields above sync into this JSON automatically.'}
              </p>
              <textarea
                  value={form.config}
                  onChange={e => handleConfigChange(e.target.value)}
                  rows={8}
                  className="w-full text-xs font-mono border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 bg-slate-50"
                  style={{ fontFamily: "'JetBrains Mono', monospace" }}
              />
              {jsonErr && <p className="text-xs text-red-500 mt-1">{jsonErr}</p>}
            </div>
          </div>

          {/* Footer */}
          <div className="px-5 py-4 border-t border-slate-100 flex justify-end gap-2">
            <Button variant="secondary" onClick={onClose}>Cancel</Button>
            <Button
              loading={saving}
              disabled={!!jsonErr || !form.name.trim()}
              onClick={() => {
                // Attach .catch() at the call site so any rejection that
                // escapes the inner try/catch is still caught here and never
                // becomes an unhandled promise rejection in the browser.
                handleSave().catch(e => {
                  const msg = e instanceof ApiError && e.isAuth
                    ? `Not authorised (${e.status}) — verify email in Keycloak or check /debug/token`
                    : (e?.message ?? 'Save failed — please try again');
                  setJsonErr(msg);
                  setSaving(false);
                });
              }}
            >
              {isEdit ? 'Save changes' : 'Add adapter'}
            </Button>
          </div>
        </Card>
      </div>
  );
}

// ─── Main page ────────────────────────────────────────────────────────────────

export default function Adapters({ keycloak }) {
  const [adapters, setAdapters] = useState([]);
  const [perfMap,  setPerfMap]  = useState({});   // adapterId → performance profile
  const [loading,  setLoading]  = useState(true);
  const [modal,    setModal]    = useState(null);  // null | 'new' | adapter obj

  // ── Load adapters + their performance profiles ─────────────────────────────

  const load = useCallback(async () => {
    try {
      const list = await listAdapters(keycloak);
      const loaded = list ?? [];
      setAdapters(loaded);

      // Fetch performance profiles in parallel — non-fatal if endpoint not ready
      const perfResults = await Promise.allSettled(
          loaded.map(a => getAdapterPerformance(keycloak, a.id))
      );
      const map = {};
      loaded.forEach((a, i) => {
        const r = perfResults[i];
        if (r.status === 'fulfilled' && r.value) {
          map[a.id] = r.value;
        }
      });
      setPerfMap(map);
    } catch {
      // API unavailable or auth error — keep existing list, don't crash
    } finally {
      setLoading(false);
    }
  }, [keycloak]);

  useEffect(() => { load(); }, [load]);

  // ── Actions ────────────────────────────────────────────────────────────────

  async function handleToggle(adapter) {
    await toggleAdapter(keycloak, adapter.id, !adapter.isActive);
    load();
  }

  async function handleSave(form) {
    try {
      if (form.id) await updateAdapter(keycloak, form.id, form);
      else          await createAdapter(keycloak, form);
      load();
    } catch (e) {
      // Re-throw so AdapterModal's catch block can display it inline.
      // Without this explicit re-throw the promise rejection was unhandled
      // at the outer level before the modal's catch could intercept it.
      throw e;
    }
  }

  // ── Summary stats ──────────────────────────────────────────────────────────

  const activeCount    = adapters.filter(a => a.isActive).length;
  const degradedCount  = adapters.filter(a => {
    const p = perfMap[a.id];
    return p && p.isDegraded && p.executionCount > CIRCUIT_BREAKER_THRESHOLD;
  }).length;
  const coldStartCount = adapters.filter(a => {
    const p = perfMap[a.id];
    return !p || p.executionCount === 0;
  }).length;

  // ── Provider breakdown ─────────────────────────────────────────────────────

  const providerCounts = adapters.reduce((acc, a) => {
    const key = a.provider?.toUpperCase() ?? 'UNKNOWN';
    acc[key] = (acc[key] ?? 0) + 1;
    return acc;
  }, {});

  return (
      <Page
          title="Adapters"
          subtitle="Manage LLM provider adapters — the execution engine selects via adaptive scoring"
          action={
            <Button onClick={() => setModal('new')}>
              <Plus size={14} /> Add adapter
            </Button>
          }
      >
        {/* ── Summary stats ── */}
        {!loading && adapters.length > 0 && (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
              {[
                { label: 'Active',      value: activeCount,   icon: <CheckCircle2 size={14}/>, color: '#16a34a', bg: '#f0fdf4' },
                { label: 'Degraded',    value: degradedCount, icon: <AlertTriangle size={14}/>, color: '#dc2626', bg: '#fef2f2' },
                { label: 'Cold start',  value: coldStartCount,icon: <Activity size={14}/>,     color: '#64748b', bg: '#f8fafc' },
                { label: 'Providers',   value: Object.keys(providerCounts).length, icon: <Puzzle size={14}/>, color: '#2563eb', bg: '#eff6ff' },
              ].map(({ label, value, icon, color, bg }) => (
                  <Card key={label} className="p-4 flex items-center gap-3">
                    <div className="p-2 rounded-lg shrink-0" style={{ backgroundColor: bg, color }}>
                      {icon}
                    </div>
                    <div>
                      <p className="text-xl font-semibold text-slate-800">{value}</p>
                      <p className="text-xs text-slate-400">{label}</p>
                    </div>
                  </Card>
              ))}
            </div>
        )}

        {/* ── Provider breakdown chips ── */}
        {!loading && Object.keys(providerCounts).length > 0 && (
            <div className="flex flex-wrap gap-2">
              {Object.entries(providerCounts).map(([p, count]) => {
                const m = providerMeta(p);
                return (
                    <span
                        key={p}
                        className="text-xs font-medium px-3 py-1 rounded-full border"
                        style={{ backgroundColor: m.bg, color: m.color, borderColor: m.border }}>
                {m.label} · {count}
              </span>
                );
              })}
            </div>
        )}

        {/* ── Degraded alert banner ── */}
        {degradedCount > 0 && (
            <div className="p-4 bg-red-50 border border-red-200 rounded-xl flex items-start gap-3">
              <AlertTriangle size={16} className="text-red-600 shrink-0 mt-0.5" />
              <div>
                <p className="text-sm font-semibold text-red-800">
                  {degradedCount} adapter{degradedCount > 1 ? 's' : ''} degraded
                </p>
                <p className="text-xs text-red-700 mt-0.5">
                  Circuit breaker active — these adapters are hard-filtered by LlmModelSelector
                  when emaSuccessRate &lt; 60% and executionCount &gt; {CIRCUIT_BREAKER_THRESHOLD}.
                  Check provider status and consider disabling until recovery.
                </p>
              </div>
            </div>
        )}

        {/* ── Main content ── */}
        {loading ? (
            <div className="flex justify-center py-16"><Spinner className="w-8 h-8" /></div>
        ) : adapters.length === 0 ? (
            <Card>
              <EmptyState
                  icon={<Puzzle size={22} />}
                  title="No adapters configured"
                  description="Add at least one LLM adapter so the execution engine can route intents. The AdapterRegistry query requires is_active=true and adapter_type='LLM'."
                  action={
                    <Button onClick={() => setModal('new')}>
                      <Plus size={14} /> Add first adapter
                    </Button>
                  }
              />
            </Card>
        ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
              {adapters.map(a => (
                  <AdapterCard
                      key={a.id}
                      adapter={a}
                      perf={perfMap[a.id] ?? null}
                      onToggle={handleToggle}
                      onEdit={adapter => setModal(adapter)}
                  />
              ))}
            </div>
        )}

        {/* ── Info footer ── */}
        {!loading && adapters.length > 0 && (
            <p className="text-xs text-slate-400 text-center">
              Selection: epsilon-greedy bandit · EMA composite score (40% success + 25% latency + 20% cost + 15% risk) ·
              Circuit breaker threshold: {CIRCUIT_BREAKER_THRESHOLD} executions ·
              Degradation at emaSuccess &lt; 60% · Recovery at &gt; 75%
            </p>
        )}

        {/* ── Modal ── */}
        {modal && (
            <AdapterModal
                adapter={modal === 'new' ? null : modal}
                onSave={handleSave}
                onClose={() => setModal(null)}
            />
        )}
      </Page>
  );
}
