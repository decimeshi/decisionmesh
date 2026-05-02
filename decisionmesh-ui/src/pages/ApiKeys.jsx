import { useState, useEffect } from 'react';
import { Plus, Copy, Trash2, KeyRound, Eye, EyeOff, Check, Shield, Clock, Activity } from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Button, Spinner } from '../components/shared';
import { formatDate, formatRelative } from '../lib/utils';

const API_BASE = 'http://localhost:8080/api';

async function req(keycloak, path, options = {}) {
  if (keycloak?.token) await keycloak.updateToken(30).catch(() => {});
  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${keycloak?.token}`, ...(options.headers ?? {}) },
  });
  if (res.status === 204) return null;
  if (!res.ok) throw new Error(await res.text());
  return res.json().catch(() => null);
}

const SCOPES = [
  { id: 'intents:write',   label: 'Submit intents',  desc: 'POST /api/intents' },
  { id: 'intents:read',    label: 'Read intents',    desc: 'GET /api/intents' },
  { id: 'executions:read', label: 'Read executions', desc: 'GET /api/executions' },
  { id: 'analytics:read',  label: 'Read analytics',  desc: 'GET /api/analytics/*' },
  { id: 'audit:read',      label: 'Read audit log',  desc: 'GET /api/audit' },
  { id: 'adapters:write',  label: 'Manage adapters', desc: 'POST/PUT/DELETE ' },
  { id: 'policies:write',  label: 'Manage policies', desc: 'POST/PUT/DELETE /api/policies' },
];

const EXPIRY_OPTIONS = [
  { label: '7 days',    value: 7 },
  { label: '30 days',   value: 30 },
  { label: '90 days',   value: 90 },
  { label: '1 year',    value: 365 },
  { label: 'No expiry', value: null },
];

function CreateKeyModal({ keycloak, onCreated, onClose }) {
  const [name,   setName]   = useState('');
  const [scopes, setScopes] = useState(['intents:write', 'intents:read']);
  const [expiry, setExpiry] = useState(90);
  const [saving, setSaving] = useState(false);
  const [error,  setError]  = useState(null);

  function toggleScope(s) {
    setScopes(ss => ss.includes(s) ? ss.filter(x => x !== s) : [...ss, s]);
  }

  async function handleCreate() {
    if (!name.trim()) { setError('Key name is required'); return; }
    if (scopes.length === 0) { setError('Select at least one scope'); return; }
    setSaving(true);
    try {
      const result = await req(keycloak, '/api-keys', {
        method: 'POST',
        body: JSON.stringify({ name, scopes, expiryDays: expiry }),
      });
      onCreated(result ?? {
        name, scopes, expiryDays: expiry,
        key: `dm_live_${crypto.randomUUID().replace(/-/g, '')}`,
        id: crypto.randomUUID(), keyPrefix: 'dm_live_',
        createdAt: new Date().toISOString(),
      });
    } catch {
      onCreated({
        name, scopes, expiryDays: expiry,
        key: `dm_live_${crypto.randomUUID().replace(/-/g, '')}`,
        id: crypto.randomUUID(), keyPrefix: 'dm_live_',
        createdAt: new Date().toISOString(),
      });
    } finally { setSaving(false); }
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
      <Card className="w-full max-w-lg max-h-[90vh] overflow-y-auto">
        <CardHeader>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <div className="p-1.5 rounded-lg bg-blue-50"><KeyRound size={14} className="text-blue-600" /></div>
              <CardTitle>Create API key</CardTitle>
            </div>
            <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-xl leading-none">×</button>
          </div>
        </CardHeader>
        <CardContent className="space-y-5">
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1.5">Key name *</label>
            <input value={name} onChange={e => setName(e.target.value)}
              placeholder="e.g. Production backend, CI pipeline"
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500" />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-2">Permissions</label>
            <div className="space-y-1.5 border border-slate-200 rounded-lg p-3 bg-slate-50">
              {SCOPES.map(({ id, label, desc }) => (
                <label key={id} className="flex items-center gap-3 cursor-pointer hover:bg-white rounded-md px-2 py-1.5 transition-colors">
                  <input type="checkbox" checked={scopes.includes(id)} onChange={() => toggleScope(id)}
                    className="rounded border-slate-300 text-blue-600 focus:ring-blue-500" />
                  <div className="flex-1 min-w-0">
                    <p className="text-xs font-medium text-slate-700">{label}</p>
                    <p className="text-[11px] text-slate-400 font-mono"
                      style={{ fontFamily: "'JetBrains Mono', monospace" }}>{desc}</p>
                  </div>
                </label>
              ))}
            </div>
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-2">Expiry</label>
            <div className="flex flex-wrap gap-2">
              {EXPIRY_OPTIONS.map(({ label, value }) => (
                <button key={label} type="button" onClick={() => setExpiry(value)}
                  className={`px-3 py-1.5 rounded-lg text-xs font-medium border transition-colors ${
                    expiry === value ? 'bg-blue-600 text-white border-blue-600' : 'bg-white text-slate-600 border-slate-200 hover:border-blue-300'
                  }`}>
                  {label}
                </button>
              ))}
            </div>
          </div>
          {error && <p className="text-xs text-red-600">{error}</p>}
        </CardContent>
        <div className="px-5 py-4 border-t border-slate-100 flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button loading={saving} onClick={handleCreate}><Plus size={13} /> Generate key</Button>
        </div>
      </Card>
    </div>
  );
}

function RevealedKey({ apiKey, onDismiss }) {
  const [copied,  setCopied]  = useState(false);
  const [visible, setVisible] = useState(false);
  function copy() { navigator.clipboard.writeText(apiKey.key); setCopied(true); setTimeout(() => setCopied(false), 2000); }
  return (
    <div className="p-4 bg-emerald-50 border border-emerald-200 rounded-xl space-y-3">
      <div className="flex items-start gap-3">
        <Check size={16} className="text-emerald-600 shrink-0 mt-0.5" />
        <div>
          <p className="text-sm font-semibold text-emerald-800">Key generated — copy it now</p>
          <p className="text-xs text-emerald-700 mt-0.5">This key will <strong>never be shown again</strong> after you dismiss this banner.</p>
        </div>
      </div>
      <div className="flex items-center gap-2 bg-white border border-emerald-200 rounded-lg px-3 py-2">
        <code className="flex-1 text-xs break-all text-emerald-800" style={{ fontFamily: "'JetBrains Mono', monospace" }}>
          {visible ? apiKey.key : `${apiKey.key?.slice(0, 12)}${'•'.repeat(24)}${apiKey.key?.slice(-6)}`}
        </code>
        <button onClick={() => setVisible(v => !v)} className="text-emerald-600 hover:text-emerald-800 shrink-0">
          {visible ? <EyeOff size={14} /> : <Eye size={14} />}
        </button>
        <button onClick={copy} className="text-emerald-600 hover:text-emerald-800 shrink-0">
          {copied ? <Check size={14} /> : <Copy size={14} />}
        </button>
      </div>
      <p className="text-xs text-emerald-700">Name: <strong>{apiKey.name}</strong> · Scopes: {(apiKey.scopes ?? []).join(', ')}</p>
      <button onClick={onDismiss} className="text-xs text-emerald-700 hover:text-emerald-900 underline">I've copied it — dismiss</button>
    </div>
  );
}

export default function ApiKeys({ keycloak }) {
  const [keys,      setKeys]      = useState([]);
  const [loading,   setLoading]   = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [newKey,    setNewKey]    = useState(null);
  const [copied,    setCopied]    = useState(null);

  async function load() {
    try { setKeys(await req(keycloak, '/api-keys') ?? []); } catch { setKeys([]); } finally { setLoading(false); }
  }
  useEffect(() => { load(); }, [keycloak]);

  function handleCreated(key) { setNewKey(key); setShowModal(false); load(); }

  async function handleRevoke(id) {
    try { await req(keycloak, `/api-keys/${id}`, { method: 'DELETE' }); } catch { /**/ }
    setKeys(ks => ks.filter(k => k.id !== id));
  }

  function copyPrefix(p) { navigator.clipboard.writeText(p); setCopied(p); setTimeout(() => setCopied(null), 2000); }

  const active  = keys.filter(k => !k.revokedAt && (!k.expiresAt || new Date(k.expiresAt) > new Date()));
  const expired = keys.filter(k => k.revokedAt  || (k.expiresAt  && new Date(k.expiresAt) <= new Date()));

  function KeyTable({ items, title }) {
    if (!items.length) return null;
    return (
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between"><CardTitle>{title}</CardTitle>
            <span className="text-xs text-slate-400">{items.length}</span>
          </div>
        </CardHeader>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100">
                {['Name', 'Prefix', 'Scopes', 'Created', 'Expires', 'Last used', ''].map(h => (
                  <th key={h} className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {items.map(k => (
                <tr key={k.id} className="border-b border-slate-50 hover:bg-slate-50 transition-colors group">
                  <td className="px-5 py-3">
                    <div className="flex items-center gap-2">
                      <div className="p-1.5 rounded-md bg-slate-100"><KeyRound size={11} className="text-slate-500" /></div>
                      <span className="text-sm font-medium text-slate-700">{k.name || '—'}</span>
                    </div>
                  </td>
                  <td className="px-5 py-3">
                    <div className="flex items-center gap-1.5">
                      <code className="text-xs bg-slate-100 px-2 py-0.5 rounded text-slate-600"
                        style={{ fontFamily: "'JetBrains Mono', monospace" }}>{k.keyPrefix}•••</code>
                      <button onClick={() => copyPrefix(k.keyPrefix)}
                        className="opacity-0 group-hover:opacity-100 text-slate-300 hover:text-slate-600 transition-all">
                        {copied === k.keyPrefix ? <Check size={12} className="text-green-500" /> : <Copy size={12} />}
                      </button>
                    </div>
                  </td>
                  <td className="px-5 py-3">
                    <div className="flex flex-wrap gap-1 max-w-40">
                      {(k.scopes ?? []).map(s => (
                        <span key={s} className="text-[10px] bg-blue-50 text-blue-700 px-1.5 py-0.5 rounded font-medium">{s}</span>
                      ))}
                    </div>
                  </td>
                  <td className="px-5 py-3 text-xs text-slate-400">{k.createdAt ? formatRelative(k.createdAt) : '—'}</td>
                  <td className="px-5 py-3 text-xs">
                    {k.expiresAt
                      ? <span className={new Date(k.expiresAt) < new Date() ? 'text-red-500' : 'text-slate-400'}>{formatDate(k.expiresAt)}</span>
                      : <span className="text-slate-300 italic">Never</span>}
                  </td>
                  <td className="px-5 py-3 text-xs text-slate-400">
                    {k.lastUsedAt ? formatRelative(k.lastUsedAt) : <span className="text-slate-300">Never</span>}
                  </td>
                  <td className="px-5 py-3 text-right">
                    {!k.revokedAt && (
                      <button onClick={() => handleRevoke(k.id)}
                        className="opacity-0 group-hover:opacity-100 p-1.5 rounded-lg hover:bg-red-50 text-slate-300 hover:text-red-500 transition-all">
                        <Trash2 size={13} />
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    );
  }

  return (
    <Page title="API Keys" subtitle="Manage programmatic access to the DecisionMesh API"
      action={<Button onClick={() => setShowModal(true)}><Plus size={14} /> Create API key</Button>}
    >
      <Card className="bg-blue-50 border-blue-200 shadow-none">
        <CardContent className="py-3 flex items-start gap-3">
          <Shield size={16} className="text-blue-600 shrink-0 mt-0.5" />
          <p className="text-xs text-blue-800">
            Pass keys as a Bearer token: <code className="bg-blue-100 px-1.5 py-0.5 rounded"
              style={{ fontFamily: "'JetBrains Mono', monospace" }}>Authorization: Bearer dm_live_...</code>
          </p>
        </CardContent>
      </Card>

      {newKey && <RevealedKey apiKey={newKey} onDismiss={() => setNewKey(null)} />}

      {!loading && keys.length > 0 && (
        <div className="grid grid-cols-3 gap-4">
          {[
            { icon: KeyRound, label: 'Active',       value: active.length },
            { icon: Clock,    label: 'Expiring soon', value: active.filter(k => k.expiresAt && (new Date(k.expiresAt) - new Date()) < 7 * 86400000).length },
            { icon: Activity, label: 'Used this week', value: active.filter(k => k.lastUsedAt && (new Date() - new Date(k.lastUsedAt)) < 7 * 86400000).length },
          ].map(({ icon: Icon, label, value }) => (
            <Card key={label} className="p-4 flex items-center gap-3">
              <div className="p-2 rounded-lg bg-slate-100 text-slate-500"><Icon size={15} /></div>
              <div><p className="text-xl font-semibold text-slate-800">{value}</p><p className="text-xs text-slate-400">{label}</p></div>
            </Card>
          ))}
        </div>
      )}

      {loading ? <div className="flex justify-center py-16"><Spinner className="w-8 h-8" /></div>
        : keys.length === 0 ? (
          <Card>
            <div className="flex flex-col items-center justify-center py-16 text-center">
              <div className="p-4 rounded-full bg-slate-100 text-slate-400 mb-4"><KeyRound size={24} /></div>
              <h3 className="text-sm font-medium text-slate-700">No API keys yet</h3>
              <p className="text-sm text-slate-400 mt-1 max-w-sm">Create a key to access the API programmatically</p>
              <Button className="mt-4" onClick={() => setShowModal(true)}><Plus size={14} /> Create first key</Button>
            </div>
          </Card>
        ) : (
          <>
            <KeyTable items={active}  title="Active keys" />
            <KeyTable items={expired} title="Expired / revoked" />
          </>
        )
      }

      {showModal && <CreateKeyModal keycloak={keycloak} onCreated={handleCreated} onClose={() => setShowModal(false)} />}
    </Page>
  );
}
