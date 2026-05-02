import { useState, useEffect, useRef, useCallback } from 'react';
import {
  ShieldAlert, RefreshCw, Copy, Check, CheckCircle2, XCircle,
  Clock, AlertTriangle, Wifi, ChevronDown, ChevronRight, Terminal,
  Eye, EyeOff, Zap, Activity,
} from 'lucide-react';
import Page from '../components/shared/Page';
import { API_BASE, request } from '../utils/api';

// ── Helpers ───────────────────────────────────────────────────────────────────

function timeUntil(expUnix) {
  const sec = expUnix - Math.floor(Date.now() / 1000);
  if (sec <= 0) return { label: 'EXPIRED', seconds: 0, urgent: true };
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return {
    label: m > 0 ? `${m}m ${s}s` : `${s}s`,
    seconds: sec,
    urgent: sec < 60,
    warning: sec < 180,
  };
}

function claimFlag(key, value) {
  if (key === 'email_verified' && value === false)
    return { level: 'error', msg: 'Backend may reject this token — verify email in Keycloak admin.' };
  if (key === 'acr' && value === '0')
    return { level: 'warn', msg: 'Password auth only (no MFA). Some APIs may require acr ≥ 1.' };
  if (key === 'exp') {
    const { seconds, urgent, warning } = timeUntil(value);
    if (urgent)  return { level: 'error', msg: 'Token expired — requests will 401 immediately.' };
    if (warning) return { level: 'warn',  msg: `Expiring in ${Math.round(seconds / 60)}m — refresh soon.` };
  }
  return null;
}

const LEVEL_STYLES = {
  error: 'bg-red-50 border-red-200 text-red-700',
  warn:  'bg-amber-50 border-amber-200 text-amber-700',
  ok:    'bg-green-50 border-green-200 text-green-700',
  info:  'bg-slate-50 border-slate-200 text-slate-600',
};

// ── Endpoints to probe ────────────────────────────────────────────────────────

const PROBE_ENDPOINTS = [
  { label: 'GET /org',               path: '/org' },
  { label: 'GET /projects',          path: '/projects' },
  { label: 'GET /credits/balance',   path: '/credits/balance' },
  { label: 'GET /org/branding',      path: '/org/branding' },
  { label: 'GET /intents/auth/me',   path: '/intents/auth/me' },
  { label: 'GET /adapters',          path: '/adapters' },
  { label: 'GET /api-keys',          path: '/api-keys' },
  { label: 'GET /billing/subscription', path: '/billing/subscription' },
  { label: 'GET /audit',             path: '/audit' },
];

// ── Sub-components ────────────────────────────────────────────────────────────

function CopyButton({ text }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      onClick={() => { navigator.clipboard.writeText(text); setCopied(true); setTimeout(() => setCopied(false), 2000); }}
      className="flex items-center gap-1 text-[10px] font-semibold px-2 py-1 rounded-md bg-slate-100 hover:bg-slate-200 text-slate-600 transition-colors"
    >
      {copied ? <Check size={10} /> : <Copy size={10} />}
      {copied ? 'Copied' : 'Copy'}
    </button>
  );
}

function ExpiryCountdown({ exp }) {
  const [tick, setTick] = useState(timeUntil(exp));
  useEffect(() => {
    const t = setInterval(() => setTick(timeUntil(exp)), 1000);
    return () => clearInterval(t);
  }, [exp]);

  const pct = Math.min(100, (tick.seconds / 300) * 100); // assume 5min max lifetime
  const color = tick.urgent ? '#dc2626' : tick.warning ? '#d97706' : '#16a34a';

  return (
    <div className="flex items-center gap-3">
      <div className="relative w-10 h-10 shrink-0">
        <svg width="40" height="40" viewBox="0 0 40 40">
          <circle cx="20" cy="20" r="16" fill="none" stroke="#e2e8f0" strokeWidth="3" />
          <circle cx="20" cy="20" r="16" fill="none" stroke={color} strokeWidth="3"
            strokeDasharray={`${2 * Math.PI * 16}`}
            strokeDashoffset={`${2 * Math.PI * 16 * (1 - pct / 100)}`}
            strokeLinecap="round"
            transform="rotate(-90 20 20)"
            style={{ transition: 'stroke-dashoffset 1s linear, stroke 0.3s' }}
          />
        </svg>
        <Clock size={12} style={{ color }} className="absolute inset-0 m-auto" />
      </div>
      <div>
        <p className="text-xs text-slate-500 font-medium">Token expires in</p>
        <p className="text-lg font-bold font-mono" style={{ color }}>{tick.label}</p>
      </div>
    </div>
  );
}

function ClaimsTable({ claims, title, raw }) {
  const [expanded, setExpanded] = useState(true);
  const [showRaw, setShowRaw] = useState(false);

  const flags = Object.entries(claims)
    .map(([k, v]) => ({ k, v, flag: claimFlag(k, v) }))
    .filter(x => x.flag);

  return (
    <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
      <div
        className="flex items-center justify-between px-4 py-3 border-b border-slate-100 cursor-pointer hover:bg-slate-50"
        onClick={() => setExpanded(e => !e)}
      >
        <div className="flex items-center gap-2">
          {expanded ? <ChevronDown size={14} className="text-slate-400" /> : <ChevronRight size={14} className="text-slate-400" />}
          <span className="text-sm font-semibold text-slate-800">{title}</span>
          {flags.some(f => f.flag?.level === 'error') && (
            <span className="text-[10px] font-bold text-red-600 bg-red-50 border border-red-200 px-1.5 py-0.5 rounded-full">
              {flags.filter(f => f.flag?.level === 'error').length} error{flags.filter(f => f.flag?.level === 'error').length > 1 ? 's' : ''}
            </span>
          )}
          {flags.some(f => f.flag?.level === 'warn') && (
            <span className="text-[10px] font-bold text-amber-600 bg-amber-50 border border-amber-200 px-1.5 py-0.5 rounded-full">
              {flags.filter(f => f.flag?.level === 'warn').length} warning{flags.filter(f => f.flag?.level === 'warn').length > 1 ? 's' : ''}
            </span>
          )}
        </div>
        <div className="flex items-center gap-2" onClick={e => e.stopPropagation()}>
          <button onClick={() => setShowRaw(r => !r)}
            className="flex items-center gap-1 text-[10px] font-semibold px-2 py-1 rounded-md bg-slate-100 hover:bg-slate-200 text-slate-600 transition-colors">
            {showRaw ? <EyeOff size={10} /> : <Eye size={10} />}
            {showRaw ? 'Table' : 'Raw JWT'}
          </button>
          <CopyButton text={showRaw ? raw : JSON.stringify(claims, null, 2)} />
        </div>
      </div>

      {expanded && (
        showRaw ? (
          <div className="p-4 bg-slate-950 overflow-x-auto">
            <p className="font-mono text-[11px] text-green-400 break-all leading-relaxed">{raw}</p>
          </div>
        ) : (
          <div>
            {/* Flag alerts */}
            {flags.length > 0 && (
              <div className="p-3 space-y-2 border-b border-slate-100">
                {flags.map(({ k, flag }) => (
                  <div key={k} className={`flex items-start gap-2 px-3 py-2 rounded-lg border text-xs ${LEVEL_STYLES[flag.level]}`}>
                    {flag.level === 'error' ? <XCircle size={12} className="shrink-0 mt-0.5" /> : <AlertTriangle size={12} className="shrink-0 mt-0.5" />}
                    <span><strong>{k}:</strong> {flag.msg}</span>
                  </div>
                ))}
              </div>
            )}
            {/* Claims rows */}
            <div className="divide-y divide-slate-50">
              {Object.entries(claims).map(([key, value]) => {
                const flag = claimFlag(key, value);
                const displayVal = typeof value === 'object' ? JSON.stringify(value) : String(value);
                const isDate = (key === 'exp' || key === 'iat' || key === 'auth_time') && typeof value === 'number';
                return (
                  <div key={key} className={`flex items-start gap-3 px-4 py-2.5 ${flag?.level === 'error' ? 'bg-red-50/40' : flag?.level === 'warn' ? 'bg-amber-50/40' : ''}`}>
                    <span className="font-mono text-[11px] text-slate-400 w-36 shrink-0 pt-0.5">{key}</span>
                    <div className="flex-1 min-w-0">
                      <p className="font-mono text-xs text-slate-800 break-all">
                        {typeof value === 'boolean'
                          ? <span className={value ? 'text-green-600 font-semibold' : 'text-red-600 font-semibold'}>{displayVal}</span>
                          : displayVal
                        }
                      </p>
                      {isDate && (
                        <p className="text-[10px] text-slate-400 mt-0.5">
                          {new Date(value * 1000).toLocaleString()}
                        </p>
                      )}
                    </div>
                    {flag && (
                      <span className={`shrink-0 text-[9px] font-bold px-1.5 py-0.5 rounded uppercase ${
                        flag.level === 'error' ? 'bg-red-100 text-red-700' : 'bg-amber-100 text-amber-700'
                      }`}>
                        {flag.level}
                      </span>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        )
      )}
    </div>
  );
}

function ProbeResult({ label, path, token }) {
  const [status, setStatus]   = useState(null); // null | 'loading' | number
  const [latency, setLatency] = useState(null);
  const [detail, setDetail]   = useState(null);
  const [open, setOpen]       = useState(false);

  async function probe() {
    setStatus('loading'); setDetail(null); setOpen(false);
    const start = Date.now();
    try {
      const res = await fetch(`${API_BASE}${path}`, {
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      });
      setLatency(Date.now() - start);
      setStatus(res.status);
      const text = await res.text();
      try { setDetail(JSON.stringify(JSON.parse(text), null, 2)); }
      catch { setDetail(text || '(empty body)'); }
    } catch (e) {
      setLatency(Date.now() - start);
      setStatus('ERR');
      setDetail(e.message);
    }
  }

  const statusColor =
    status === null || status === 'loading' ? '#94a3b8' :
    status === 200  ? '#16a34a' :
    status === 204  ? '#16a34a' :
    status === 401  ? '#dc2626' :
    status === 403  ? '#d97706' :
    status === 404  ? '#64748b' :
    '#dc2626';

  return (
    <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
      <div className="flex items-center gap-3 px-4 py-3">
        <span className="font-mono text-xs text-slate-600 flex-1 truncate">{label}</span>
        {status !== null && status !== 'loading' && (
          <div className="flex items-center gap-2 shrink-0">
            <span className="font-mono text-xs font-bold" style={{ color: statusColor }}>{status}</span>
            {latency !== null && <span className="text-[10px] text-slate-400">{latency}ms</span>}
            {detail && (
              <button onClick={() => setOpen(o => !o)}
                className="text-[10px] text-slate-400 hover:text-slate-700">
                {open ? 'hide' : 'body'}
              </button>
            )}
          </div>
        )}
        <button onClick={probe} disabled={status === 'loading'}
          className="shrink-0 flex items-center gap-1 text-[10px] font-semibold px-2.5 py-1.5 rounded-lg bg-slate-900 text-white hover:bg-slate-700 disabled:opacity-50 transition-colors">
          {status === 'loading'
            ? <RefreshCw size={10} className="animate-spin" />
            : <Wifi size={10} />}
          {status === 'loading' ? 'Testing…' : 'Test'}
        </button>
      </div>
      {open && detail && (
        <div className="border-t border-slate-100 bg-slate-950 px-4 py-3 max-h-48 overflow-auto">
          <pre className="text-[11px] text-green-400 font-mono whitespace-pre-wrap break-all">{detail}</pre>
        </div>
      )}
    </div>
  );
}

// ── Request log interceptor ───────────────────────────────────────────────────

function useRequestLog() {
  const [log, setLog] = useState([]);
  const origFetch = useRef(null);
  const queue = useRef([]);
  const flushTimer = useRef(null);

  useEffect(() => {
    // .bind(window) is critical — storing fetch without it loses the native
    // 'this' binding, causing "Illegal invocation" on every call.
    origFetch.current = window.fetch.bind(window);

    window.fetch = async (...args) => {
      const url = typeof args[0] === 'string' ? args[0] : (args[0]?.url ?? '?');

      // Only intercept calls to our own API — ignore Keycloak token refresh
      // requests and any other third-party calls, which would cause constant
      // re-renders as they succeed/fail in the background.
      if (!url.includes(API_BASE)) {
        return origFetch.current(...args);
      }

      const method = args[1]?.method ?? 'GET';
      const start  = Date.now();
      try {
        const res = await origFetch.current(...args);
        const ms  = Date.now() - start;
        if (res.status === 401 || res.status === 403) {
          queue.current.push({
            id:         Date.now() + Math.random(),
            ts:         new Date().toLocaleTimeString(),
            method, url, ms,
            status:     res.status,
            authHeader: args[1]?.headers?.Authorization
              ? args[1].headers.Authorization.substring(0, 30) + '…'
              : '(none)',
          });
          // Batch state updates — flush at most once per 300 ms so rapid
          // failing requests don't cause a re-render storm.
          clearTimeout(flushTimer.current);
          flushTimer.current = setTimeout(() => {
            const batch = queue.current.splice(0);
            if (batch.length) setLog(l => [...batch, ...l].slice(0, 50));
          }, 300);
        }
        return res;
      } catch (e) {
        const ms = Date.now() - start;
        queue.current.push({
          id: Date.now() + Math.random(),
          ts: new Date().toLocaleTimeString(),
          method, url, ms,
          status: 'ERR',
          authHeader: '—',
          error: e.message,
        });
        clearTimeout(flushTimer.current);
        flushTimer.current = setTimeout(() => {
          const batch = queue.current.splice(0);
          if (batch.length) setLog(l => [...batch, ...l].slice(0, 50));
        }, 300);
        throw e;
      }
    };

    return () => {
      clearTimeout(flushTimer.current);
      window.fetch = origFetch.current;
    };
  }, []);

  return log;
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function TokenDebugPage({ keycloak }) {
  const log = useRequestLog();
  const [refreshing,    setRefreshing]    = useState(false);
  const [refreshStatus, setRefreshStatus] = useState(null); // null | 'ok' | 'session_expired' | 'error'
  const [, forceUpdate] = useState(0);

  const access    = keycloak?.tokenParsed;
  const id        = keycloak?.idTokenParsed;
  const rawAccess = keycloak?.token ?? '';
  const rawId     = keycloak?.idToken ?? '';

  async function forceRefresh() {
    setRefreshing(true);
    setRefreshStatus(null);
    try {
      // -1 forces a refresh regardless of current token expiry.
      // updateToken() rejects when the Keycloak SSO session has expired
      // server-side (refresh token invalid) — do NOT call login() here
      // automatically as that interrupts the debug workflow.
      const refreshed = await keycloak.updateToken(-1);
      setRefreshStatus(refreshed ? 'ok' : 'not_needed');
      forceUpdate(n => n + 1);
    } catch {
      // Keycloak returned 401 on the /token endpoint — the SSO session
      // has expired server-side. Show a clear message instead of redirecting.
      setRefreshStatus('session_expired');
    } finally {
      setRefreshing(false);
      // Clear status message after 5 seconds
      setTimeout(() => setRefreshStatus(null), 5000);
    }
  }

  async function probeAll() {
    // trigger all probe buttons — done by dispatching click on each
    document.querySelectorAll('[data-probe-btn]').forEach(btn => btn.click());
  }

  if (!access) {
    return (
      <Page title="Token Debugger" subtitle="Diagnose 401 Unauthorized errors">
        <div className="flex items-center gap-3 p-5 bg-red-50 border border-red-200 rounded-xl text-sm text-red-700">
          <XCircle size={16} /> No access token found — are you authenticated?
        </div>
      </Page>
    );
  }

  const expiry = timeUntil(access.exp);
  const overallHealth = expiry.urgent ? 'error'
    : !access.email_verified ? 'error'
    : expiry.warning ? 'warn'
    : 'ok';

  const healthConfig = {
    error: { label: 'Token issues detected',  color: '#dc2626', bg: 'bg-red-50',   border: 'border-red-200',   icon: <XCircle size={16} className="text-red-600" /> },
    warn:  { label: 'Token warnings present', color: '#d97706', bg: 'bg-amber-50', border: 'border-amber-200', icon: <AlertTriangle size={16} className="text-amber-600" /> },
    ok:    { label: 'Token looks healthy',    color: '#16a34a', bg: 'bg-green-50', border: 'border-green-200', icon: <CheckCircle2 size={16} className="text-green-600" /> },
  }[overallHealth];

  return (
    <Page
      title="Token Debugger"
      subtitle="Live JWT inspection and 401 root-cause analysis"
      action={
        <div className="flex items-center gap-3">
          {/* Refresh status feedback */}
          {refreshStatus === 'ok' && (
            <span className="flex items-center gap-1 text-xs font-medium text-green-700 bg-green-50 border border-green-200 px-2.5 py-1.5 rounded-lg">
              <CheckCircle2 size={12} /> Token refreshed
            </span>
          )}
          {refreshStatus === 'not_needed' && (
            <span className="flex items-center gap-1 text-xs font-medium text-slate-600 bg-slate-100 border border-slate-200 px-2.5 py-1.5 rounded-lg">
              <CheckCircle2 size={12} /> Token still valid
            </span>
          )}
          {refreshStatus === 'session_expired' && (
            <span className="flex items-center gap-1.5 text-xs font-medium text-red-700 bg-red-50 border border-red-200 px-2.5 py-1.5 rounded-lg">
              <XCircle size={12} />
              SSO session expired — <button onClick={() => keycloak.login()} className="underline font-semibold">log in again</button>
            </span>
          )}
          <button onClick={forceRefresh} disabled={refreshing}
            className="flex items-center gap-1.5 text-xs font-semibold px-3 py-2 rounded-lg bg-slate-900 text-white hover:bg-slate-700 disabled:opacity-50 transition-colors">
            <RefreshCw size={12} className={refreshing ? 'animate-spin' : ''} />
            Force token refresh
          </button>
        </div>
      }
    >
      {/* ── Health banner ── */}
      <div className={`flex items-center gap-3 px-5 py-4 rounded-xl border ${healthConfig.bg} ${healthConfig.border}`}>
        {healthConfig.icon}
        <div className="flex-1">
          <p className="text-sm font-semibold" style={{ color: healthConfig.color }}>{healthConfig.label}</p>
          <p className="text-xs mt-0.5" style={{ color: healthConfig.color, opacity: 0.8 }}>
            {overallHealth === 'error' && !access.email_verified && 'email_verified is false — most backends reject this. '}
            {expiry.urgent && 'Token has expired — all requests will 401 until refreshed. '}
            {overallHealth === 'ok' && 'No obvious issues in the token claims.'}
          </p>
        </div>
        <ExpiryCountdown exp={access.exp} />
      </div>

      {/* ── Token metadata strip ── */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        {[
          { label: 'Subject (sub)',  value: access.sub?.split('-')[0] + '…' },
          { label: 'Issuer (iss)',   value: access.iss?.replace('http://', '') },
          { label: 'Audience (aud)', value: Array.isArray(access.aud) ? access.aud.join(', ') : access.aud },
          { label: 'Auth method (acr)', value: access.acr === '0' ? 'Password' : access.acr === '1' ? 'MFA' : access.acr ?? '—' },
        ].map(({ label, value }) => (
          <div key={label} className="bg-white rounded-xl border border-slate-200 p-4">
            <p className="text-[10px] text-slate-400 font-semibold uppercase tracking-wider mb-1">{label}</p>
            <p className="text-xs font-mono text-slate-800 truncate" title={value}>{value}</p>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-5">
        {/* ── Left column: claims ── */}
        <div className="space-y-4">
          <ClaimsTable claims={access} title="Access token claims" raw={rawAccess} />
          {id && <ClaimsTable claims={id} title="ID token claims" raw={rawId} />}
        </div>

        {/* ── Right column: endpoint probe + log ── */}
        <div className="space-y-4">

          {/* Endpoint probe panel */}
          <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
            <div className="flex items-center justify-between px-4 py-3 border-b border-slate-100">
              <div className="flex items-center gap-2">
                <Wifi size={14} className="text-slate-500" />
                <span className="text-sm font-semibold text-slate-800">Endpoint probe</span>
                <span className="text-[10px] text-slate-400">Uses your current token</span>
              </div>
              <button onClick={probeAll}
                className="text-[10px] font-semibold px-2.5 py-1.5 rounded-lg bg-blue-600 text-white hover:bg-blue-700 transition-colors">
                Test all
              </button>
            </div>
            <div className="p-3 space-y-2">
              {PROBE_ENDPOINTS.map(ep => (
                <ProbeResult key={ep.path} {...ep} token={rawAccess} />
              ))}
            </div>
          </div>

          {/* Live 401/403 log */}
          <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
            <div className="flex items-center justify-between px-4 py-3 border-b border-slate-100">
              <div className="flex items-center gap-2">
                <Terminal size={14} className="text-slate-500" />
                <span className="text-sm font-semibold text-slate-800">Live 401 / 403 log</span>
              </div>
              <div className="flex items-center gap-2">
                {log.length > 0 && (
                  <span className="text-[10px] font-bold text-red-600 bg-red-50 border border-red-200 px-2 py-0.5 rounded-full">
                    {log.length} caught
                  </span>
                )}
                <span className="text-[10px] text-slate-400">intercepts all fetch calls</span>
              </div>
            </div>

            {log.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-10 text-center text-slate-400">
                <Activity size={24} className="mb-2 opacity-30" />
                <p className="text-sm font-medium">No auth errors captured yet</p>
                <p className="text-xs mt-1">Navigate around the app or use the endpoint probe above</p>
              </div>
            ) : (
              <div className="divide-y divide-slate-50 max-h-96 overflow-y-auto">
                {log.map(entry => (
                  <div key={entry.id} className={`px-4 py-3 ${entry.status === 401 ? 'bg-red-50/50' : entry.status === 403 ? 'bg-amber-50/50' : ''}`}>
                    <div className="flex items-center gap-2 mb-1">
                      <span className={`text-xs font-bold ${entry.status === 401 ? 'text-red-600' : entry.status === 403 ? 'text-amber-600' : 'text-slate-600'}`}>
                        {entry.status}
                      </span>
                      <span className="text-[10px] font-semibold text-slate-500 bg-slate-100 px-1.5 py-0.5 rounded">{entry.method}</span>
                      <span className="text-[10px] text-slate-400">{entry.ts}</span>
                      <span className="text-[10px] text-slate-400 ml-auto">{entry.ms}ms</span>
                    </div>
                    <p className="font-mono text-[11px] text-slate-700 truncate" title={entry.url}>{entry.url}</p>
                    <p className="text-[10px] text-slate-400 mt-0.5">
                      Auth header: <span className="font-mono">{entry.authHeader}</span>
                    </p>
                    {entry.error && (
                      <p className="text-[10px] text-red-500 mt-0.5">Network: {entry.error}</p>
                    )}
                    {/* Common 401 root-cause hints */}
                    {entry.status === 401 && (
                      <div className="mt-1.5 text-[10px] text-red-700 bg-red-50 border border-red-100 rounded px-2 py-1 space-y-0.5">
                        {!access.email_verified && <p>• email_verified is false — backend likely rejecting token</p>}
                        {expiry.urgent && <p>• Token expired at time of request</p>}
                        {entry.authHeader === '(none)' && <p>• No Authorization header was sent with this request</p>}
                        {entry.authHeader !== '(none)' && !expiry.urgent && access.email_verified && (
                          <p>• Token looks valid — check backend role/scope requirements or CORS config</p>
                        )}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </Page>
  );
}
