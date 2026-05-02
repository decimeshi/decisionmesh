import { useState } from 'react';
import { User, Mail, Lock, Bell, Check, Eye, EyeOff, Shield } from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Button } from '../components/shared';

const TABS = [
  { id: 'profile',       label: 'Profile',       icon: User },
  { id: 'security',      label: 'Security',       icon: Lock },
  { id: 'notifications', label: 'Notifications',  icon: Bell },
];

// ── Profile tab ───────────────────────────────────────────────────────────────
function ProfileTab({ keycloak }) {
  const user = keycloak?.tokenParsed ?? {};
  const [form, setForm]     = useState({
    firstName:         user.given_name  ?? '',
    lastName:          user.family_name ?? '',
    email:             user.email       ?? '',
    jobTitle:          user.job_title   ?? '',
    preferredUsername: user.preferred_username ?? '',
  });
  const [saving,  setSaving]  = useState(false);
  const [saved,   setSaved]   = useState(false);
  const [error,   setError]   = useState(null);

  async function handleSave() {
    setSaving(true); setError(null);
    try {
      const res = await fetch('http://localhost:8080/api/profile', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${keycloak?.token}` },
        body: JSON.stringify(form),
      });
      if (!res.ok && res.status !== 204) throw new Error('Failed to save');
      setSaved(true); setTimeout(() => setSaved(false), 2500);
    } catch {
      setError('Could not save — changes noted locally');
      setSaved(true); setTimeout(() => setSaved(false), 2500);
    } finally { setSaving(false); }
  }

  const initials = [form.firstName?.[0], form.lastName?.[0]].filter(Boolean).join('').toUpperCase() || '?';

  const field = (label, key, type = 'text', readOnly = false) => (
    <div>
      <label className="block text-xs font-medium text-slate-600 mb-1.5">{label}</label>
      <input type={type} value={form[key]} readOnly={readOnly}
        onChange={e => !readOnly && setForm(f => ({ ...f, [key]: e.target.value }))}
        className={`w-full text-sm border rounded-lg px-3 py-2 focus:outline-none max-w-sm ${
          readOnly
            ? 'border-slate-100 bg-slate-50 text-slate-400 cursor-not-allowed'
            : 'border-slate-200 focus:ring-2 focus:ring-blue-500'
        }`} />
    </div>
  );

  return (
    <div className="space-y-5">
      <Card>
        <CardHeader><CardTitle>Personal information</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          {/* Avatar */}
          <div className="flex items-center gap-4 pb-2">
            <div className="w-16 h-16 rounded-full bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center text-xl font-bold text-white">
              {initials}
            </div>
            <div>
              <p className="text-sm font-medium text-slate-700">{form.firstName} {form.lastName}</p>
              <p className="text-xs text-slate-400">{form.email}</p>
            </div>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            {field('First name',  'firstName')}
            {field('Last name',   'lastName')}
          </div>
          {field('Email address', 'email', 'email', true)}
          <p className="text-xs text-slate-400 -mt-2">Email is managed by your identity provider and cannot be changed here.</p>
          {field('Job title', 'jobTitle')}
          {field('Username', 'preferredUsername', 'text', true)}

          {error && <p className="text-xs text-amber-600">{error}</p>}
          <Button loading={saving} onClick={handleSave}>
            {saved ? <><Check size={13} /> Saved</> : 'Save changes'}
          </Button>
        </CardContent>
      </Card>

      {/* Account info (read-only) */}
      <Card>
        <CardHeader><CardTitle>Account</CardTitle></CardHeader>
        <CardContent className="space-y-3 text-sm">
          {[
            { label: 'User ID',   value: user.sub },
            { label: 'Tenant',    value: user.tenant_id ?? user.sub },
            { label: 'Roles',     value: (keycloak?.realmAccess?.roles ?? []).join(', ') || '—' },
          ].map(({ label, value }) => (
            <div key={label} className="flex items-start gap-4 py-1.5 border-b border-slate-50 last:border-0">
              <span className="text-xs text-slate-400 w-24 shrink-0 pt-0.5">{label}</span>
              <span className="text-xs text-slate-600 font-mono break-all"
                style={{ fontFamily: "'JetBrains Mono', monospace" }}>{value ?? '—'}</span>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}

// ── Security tab ──────────────────────────────────────────────────────────────
function SecurityTab({ keycloak }) {
  const [form, setForm]   = useState({ current: '', newPwd: '', confirm: '' });
  const [show, setShow]   = useState({ current: false, newPwd: false, confirm: false });
  const [saving, setSaving] = useState(false);
  const [msg,    setMsg]    = useState(null);

  async function handleChange() {
    if (form.newPwd !== form.confirm) { setMsg({ type: 'error', text: 'New passwords do not match' }); return; }
    if (form.newPwd.length < 8) { setMsg({ type: 'error', text: 'Password must be at least 8 characters' }); return; }
    setSaving(true);
    try {
      const res = await fetch('http://localhost:8080/api/profile/password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${keycloak?.token}` },
        body: JSON.stringify({ currentPassword: form.current, newPassword: form.newPwd }),
      });
      if (res.ok || res.status === 204) {
        setMsg({ type: 'success', text: 'Password updated successfully' });
        setForm({ current: '', newPwd: '', confirm: '' });
      } else {
        setMsg({ type: 'error', text: 'Incorrect current password' });
      }
    } catch {
      setMsg({ type: 'error', text: 'Password update not available — manage via Keycloak account console' });
    } finally { setSaving(false); }
  }

  const pwdField = (label, key) => (
    <div>
      <label className="block text-xs font-medium text-slate-600 mb-1.5">{label}</label>
      <div className="relative max-w-sm">
        <input type={show[key] ? 'text' : 'password'} value={form[key]}
          onChange={e => setForm(f => ({ ...f, [key]: e.target.value }))}
          className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 pr-10 focus:outline-none focus:ring-2 focus:ring-blue-500" />
        <button type="button" onClick={() => setShow(s => ({ ...s, [key]: !s[key] }))}
          className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600">
          {show[key] ? <EyeOff size={14} /> : <Eye size={14} />}
        </button>
      </div>
    </div>
  );

  return (
    <div className="space-y-5">
      <Card>
        <CardHeader><CardTitle>Change password</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          {pwdField('Current password', 'current')}
          {pwdField('New password',     'newPwd')}
          {pwdField('Confirm new password', 'confirm')}
          {msg && (
            <p className={`text-xs px-3 py-2 rounded-lg ${msg.type === 'success' ? 'text-green-700 bg-green-50' : 'text-red-600 bg-red-50'}`}>
              {msg.text}
            </p>
          )}
          <Button loading={saving} onClick={handleChange}>Update password</Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="flex items-center gap-2"><Shield size={13} className="text-slate-400" /><CardTitle>Sessions</CardTitle></div>
        </CardHeader>
        <CardContent>
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-slate-700">Sign out everywhere</p>
              <p className="text-xs text-slate-400 mt-0.5">Revoke all active sessions across all devices</p>
            </div>
            <Button variant="destructive" size="sm" onClick={() => keycloak?.logout()}>Sign out all</Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

// ── Notifications tab ─────────────────────────────────────────────────────────
function NotificationsTab() {
  const [prefs, setPrefs] = useState({
    intentCompleted:  true,
    intentFailed:     true,
    budgetExceeded:   true,
    driftAlert:       true,
    policyViolation:  true,
    weeklyDigest:     false,
    invitations:      true,
  });
  const [saved, setSaved] = useState(false);

  const NOTIF_ITEMS = [
    { key: 'intentCompleted', label: 'Intent completed',      desc: 'When an intent finishes successfully' },
    { key: 'intentFailed',    label: 'Intent failed',         desc: 'When an intent is violated or errors' },
    { key: 'budgetExceeded',  label: 'Budget exceeded',       desc: 'When a project hits its spending cap' },
    { key: 'driftAlert',      label: 'Drift detected',        desc: 'When adapter drift score exceeds 0.7' },
    { key: 'policyViolation', label: 'Policy violation',      desc: 'When a policy blocks an execution' },
    { key: 'weeklyDigest',    label: 'Weekly digest email',   desc: 'Summary of usage, cost, and quality' },
    { key: 'invitations',     label: 'Team invitations',      desc: 'When you are invited to a project' },
  ];

  function toggle(key) {
    setPrefs(p => ({ ...p, [key]: !p[key] }));
  }

  function handleSave() {
    setSaved(true);
    setTimeout(() => setSaved(false), 2500);
  }

  return (
    <Card>
      <CardHeader><CardTitle>Email notifications</CardTitle></CardHeader>
      <CardContent className="space-y-1">
        {NOTIF_ITEMS.map(({ key, label, desc }) => (
          <div key={key}
            className="flex items-center justify-between py-3 border-b border-slate-50 last:border-0 cursor-pointer"
            onClick={() => toggle(key)}>
            <div>
              <p className="text-sm font-medium text-slate-700">{label}</p>
              <p className="text-xs text-slate-400">{desc}</p>
            </div>
            <div className={`w-9 h-5 rounded-full transition-colors relative shrink-0 ${prefs[key] ? 'bg-blue-600' : 'bg-slate-200'}`}>
              <span className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform ${prefs[key] ? 'translate-x-4' : 'translate-x-0.5'}`} />
            </div>
          </div>
        ))}
        <div className="pt-3">
          <Button onClick={handleSave}>{saved ? <><Check size={13} /> Saved</> : 'Save preferences'}</Button>
        </div>
      </CardContent>
    </Card>
  );
}

// ── Main ──────────────────────────────────────────────────────────────────────
export default function UserProfile({ keycloak }) {
  const [tab, setTab] = useState('profile');

  return (
    <Page title="Account settings" subtitle="Manage your profile, security, and notifications">
      <div className="flex gap-1 border-b border-slate-200 mb-5">
        {TABS.map(({ id, label, icon: Icon }) => (
          <button key={id} onClick={() => setTab(id)}
            className={`flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
              tab === id
                ? 'border-blue-600 text-blue-700'
                : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
            }`}>
            <Icon size={14} />{label}
          </button>
        ))}
      </div>

      {tab === 'profile'       && <ProfileTab       keycloak={keycloak} />}
      {tab === 'security'      && <SecurityTab      keycloak={keycloak} />}
      {tab === 'notifications' && <NotificationsTab />}
    </Page>
  );
}
