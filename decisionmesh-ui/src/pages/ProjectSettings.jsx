import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Settings, Users, Puzzle, DollarSign, Trash2, UserPlus, Check, Mail, AlertTriangle } from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Button, Spinner } from '../components/shared';
import { useProject } from '../context/ProjectContext';
import { formatRelative } from '../lib/utils';

const ROLES       = ['ADMIN', 'ANALYST', 'VIEWER'];
const ROLE_COLORS = { ADMIN: 'bg-purple-100 text-purple-700', ANALYST: 'bg-blue-100 text-blue-700', VIEWER: 'bg-slate-100 text-slate-600' };
const ENV_OPTS    = ['Production', 'Staging', 'Dev'];

// ── helpers ───────────────────────────────────────────────────────────────────
async function api(keycloak, path, options = {}) {
  if (keycloak?.token) await keycloak.updateToken(30).catch(() => {});
  const res = await fetch(`http://localhost:8080/api${path}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${keycloak?.token}`, ...(options.headers ?? {}) },
  });
  if (!res.ok || res.status === 204) return null;
  return res.json().catch(() => null);
}

// ── Tabs ──────────────────────────────────────────────────────────────────────
function GeneralTab({ project, onSave }) {
  const [form,    setForm]    = useState({ name: project.name, description: project.description ?? '', environment: project.environment ?? 'Production' });
  const [saving,  setSaving]  = useState(false);
  const [saved,   setSaved]   = useState(false);

  async function handleSave() {
    setSaving(true);
    await onSave(form);
    setSaving(false); setSaved(true);
    setTimeout(() => setSaved(false), 2500);
  }

  return (
    <div className="space-y-5">
      <Card>
        <CardHeader><CardTitle>Project details</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1.5">Project name</label>
            <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 max-w-sm" />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1.5">Description</label>
            <textarea value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
              rows={3} className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 max-w-sm resize-none" />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1.5">Environment</label>
            <select value={form.environment} onChange={e => setForm(f => ({ ...f, environment: e.target.value }))}
              className="text-sm border border-slate-200 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-blue-500">
              {ENV_OPTS.map(e => <option key={e}>{e}</option>)}
            </select>
          </div>
          <Button loading={saving} onClick={handleSave}>
            {saved ? <><Check size={13} /> Saved</> : 'Save changes'}
          </Button>
        </CardContent>
      </Card>

      <Card className="border-red-200">
        <CardHeader>
          <div className="flex items-center gap-2"><AlertTriangle size={13} className="text-red-500" /><CardTitle>Danger zone</CardTitle></div>
        </CardHeader>
        <CardContent>
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-slate-700">Archive this project</p>
              <p className="text-xs text-slate-400 mt-0.5">Archived projects are read-only. No new intents can be submitted.</p>
            </div>
            <Button variant="destructive" size="sm">Archive</Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

function MembersTab({ project, keycloak }) {
  const [members,    setMembers]    = useState([]);
  const [loading,    setLoading]    = useState(true);
  const [email,      setEmail]      = useState('');
  const [role,       setRole]       = useState('ANALYST');
  const [inviting,   setInviting]   = useState(false);
  const [success,    setSuccess]    = useState(false);
  const [error,      setError]      = useState(null);

  useEffect(() => {
    api(keycloak, `/projects/${project.id}/members`)
      .then(d => setMembers(d ?? []))
      .finally(() => setLoading(false));
  }, [project.id]);

  async function handleInvite(e) {
    e.preventDefault();
    if (!email.includes('@')) { setError('Enter a valid email'); return; }
    setInviting(true); setError(null);
    try {
      await api(keycloak, `/projects/${project.id}/invitations`, {
        method: 'POST', body: JSON.stringify({ email, role }),
      });
      setEmail(''); setSuccess(true);
      setTimeout(() => setSuccess(false), 3000);
    } catch (err) { setError('Failed to send invitation'); }
    finally { setInviting(false); }
  }

  async function handleRoleChange(userId, newRole) {
    await api(keycloak, `/projects/${project.id}/members/${userId}/role`, {
      method: 'PATCH', body: JSON.stringify({ role: newRole }),
    });
    setMembers(ms => ms.map(m => m.userId === userId ? { ...m, role: newRole } : m));
  }

  async function handleRemove(userId) {
    await api(keycloak, `/projects/${project.id}/members/${userId}`, { method: 'DELETE' });
    setMembers(ms => ms.filter(m => m.userId !== userId));
  }

  return (
    <div className="space-y-5">
      {/* Invite form */}
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2"><UserPlus size={13} className="text-blue-600" /><CardTitle>Invite to project</CardTitle></div>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleInvite} className="flex gap-3 flex-wrap items-end">
            <div className="flex-1 min-w-48">
              <label className="block text-xs font-medium text-slate-600 mb-1.5">Email</label>
              <div className="relative">
                <Mail size={13} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
                <input type="email" value={email} onChange={e => setEmail(e.target.value)}
                  placeholder="colleague@company.com"
                  className="w-full pl-8 pr-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500" />
              </div>
            </div>
            <div className="w-36">
              <label className="block text-xs font-medium text-slate-600 mb-1.5">Role</label>
              <select value={role} onChange={e => setRole(e.target.value)}
                className="w-full py-2 px-3 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white">
                {ROLES.map(r => <option key={r}>{r}</option>)}
              </select>
            </div>
            <Button type="submit" loading={inviting}><UserPlus size={13} /> Invite</Button>
          </form>
          {error   && <p className="mt-2 text-xs text-red-600">{error}</p>}
          {success && <p className="mt-2 text-xs text-green-600 flex items-center gap-1"><Check size={12} /> Invitation sent</p>}
        </CardContent>
      </Card>

      {/* Members table */}
      <Card>
        <CardHeader><CardTitle>Members ({members.length})</CardTitle></CardHeader>
        {loading ? <div className="flex justify-center py-10"><Spinner /></div> : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100">
                  {['Member', 'Role', 'Joined', ''].map(h => (
                    <th key={h} className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {members.length === 0 && (
                  <tr><td colSpan={4} className="px-5 py-10 text-center text-sm text-slate-400">
                    No members yet — invite someone above
                  </td></tr>
                )}
                {members.map(m => (
                  <tr key={m.userId} className="border-b border-slate-50 hover:bg-slate-50 group transition-colors">
                    <td className="px-5 py-3">
                      <div className="flex items-center gap-3">
                        <div className="w-8 h-8 rounded-full bg-gradient-to-br from-blue-400 to-indigo-500 flex items-center justify-center text-xs font-semibold text-white shrink-0">
                          {(m.name || m.email)?.[0]?.toUpperCase() ?? '?'}
                        </div>
                        <div>
                          <p className="text-sm font-medium text-slate-800">{m.name || '—'}</p>
                          <p className="text-xs text-slate-400">{m.email}</p>
                        </div>
                      </div>
                    </td>
                    <td className="px-5 py-3">
                      <select value={m.role} onChange={e => handleRoleChange(m.userId, e.target.value)}
                        className="text-xs border border-slate-200 rounded-lg px-2 py-1 focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white">
                        {ROLES.map(r => <option key={r}>{r}</option>)}
                      </select>
                    </td>
                    <td className="px-5 py-3 text-xs text-slate-400">{m.joinedAt ? formatRelative(m.joinedAt) : '—'}</td>
                    <td className="px-5 py-3 text-right">
                      {!m.isCurrentUser && (
                        <button onClick={() => handleRemove(m.userId)}
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
        )}
      </Card>
    </div>
  );
}

function BudgetTab({ project, keycloak, onSave }) {
  const [ceiling, setCeiling] = useState(project.budgetCeilingUsd ?? '');
  const [saving,  setSaving]  = useState(false);
  const [saved,   setSaved]   = useState(false);

  async function handleSave() {
    setSaving(true);
    await onSave({ budgetCeilingUsd: parseFloat(ceiling) || null });
    setSaving(false); setSaved(true);
    setTimeout(() => setSaved(false), 2500);
  }

  return (
    <Card>
      <CardHeader><CardTitle>Project budget</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <p className="text-sm text-slate-500">Set a monthly spending cap for this project. All intents in this project share this budget.</p>
        <div className="max-w-xs">
          <label className="block text-xs font-medium text-slate-600 mb-1.5">Monthly ceiling (USD)</label>
          <div className="relative">
            <span className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 text-sm">$</span>
            <input type="number" min="0" step="0.01" value={ceiling}
              onChange={e => setCeiling(e.target.value)}
              placeholder="100.00"
              className="w-full pl-6 pr-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500" />
          </div>
          <p className="text-xs text-slate-400 mt-1.5">Leave blank for no limit</p>
        </div>
        <Button loading={saving} onClick={handleSave}>
          {saved ? <><Check size={13} /> Saved</> : 'Save budget'}
        </Button>
      </CardContent>
    </Card>
  );
}

// ── Main page ─────────────────────────────────────────────────────────────────
export default function ProjectSettings({ keycloak }) {
  const { id }      = useParams();
  const navigate    = useNavigate();
  const { projects, updateProject } = useProject();
  const [tab, setTab] = useState('general');

  const project = projects.find(p => p.id === id) ?? projects[0];

  if (!project) return (
    <Page title="Project settings">
      <div className="flex justify-center py-24"><Spinner className="w-8 h-8" /></div>
    </Page>
  );

  async function handleSave(updates) {
    try {
      await api(keycloak, `/projects/${project.id}`, {
        method: 'PATCH', body: JSON.stringify(updates),
      });
    } catch { /* API not ready */ }
    updateProject({ id: project.id, ...updates });
  }

  const TABS = [
    { id: 'general', label: 'General',  icon: Settings },
    { id: 'members', label: 'Members',  icon: Users },
    { id: 'budget',  label: 'Budget',   icon: DollarSign },
  ];

  return (
    <Page
      title={`${project.name} — Settings`}
      subtitle={project.description}
      action={
        <Button variant="secondary" size="sm" onClick={() => navigate('/projects')}>
          ← All projects
        </Button>
      }
    >
      {/* Tab bar */}
      <div className="flex gap-1 border-b border-slate-200 mb-5">
        {TABS.map(({ id: tid, label, icon: Icon }) => (
          <button key={tid} onClick={() => setTab(tid)}
            className={`flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
              tab === tid
                ? 'border-blue-600 text-blue-700'
                : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
            }`}>
            <Icon size={14} />{label}
          </button>
        ))}
      </div>

      {tab === 'general' && <GeneralTab project={project} onSave={handleSave} />}
      {tab === 'members' && <MembersTab project={project} keycloak={keycloak} />}
      {tab === 'budget'  && <BudgetTab  project={project} keycloak={keycloak} onSave={handleSave} />}
    </Page>
  );
}
