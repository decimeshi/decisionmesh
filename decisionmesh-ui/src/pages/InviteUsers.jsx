import { useState, useEffect } from 'react';
import { UserPlus, Copy, Trash2, Mail, Users, Check, Clock, Shield, Eye } from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Button, Spinner, EmptyState } from '../components/shared';
import { formatDate, formatRelative } from '../lib/utils';

// ── API helpers ───────────────────────────────────────────────────────────────

const API_BASE = 'http://localhost:8080/api';

async function refreshToken(keycloak) {
  if (!keycloak.authenticated) { await keycloak.login(); return; }
  try { await keycloak.updateToken(30); } catch { await keycloak.login(); }
}

async function request(keycloak, path, options = {}) {
  await refreshToken(keycloak);
  if (!keycloak.authenticated) return;
  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${keycloak.token}`,
      ...(options.headers ?? {}),
    },
  });
  if (res.status === 401) { await keycloak.login(); return; }
  if (res.status === 204) return null;
  if (!res.ok) throw new Error(await res.text());
  const text = await res.text();
  try { return JSON.parse(text); } catch { return text; }
}

async function listInvitations(keycloak) {
  return request(keycloak, '/invitations');
}

async function listMembers(keycloak) {
  return request(keycloak, '/members');
}

async function sendInvitation(keycloak, body) {
  return request(keycloak, '/invitations', { method: 'POST', body: JSON.stringify(body) });
}

async function revokeInvitation(keycloak, id) {
  return request(keycloak, `/invitations/${id}`, { method: 'DELETE' });
}

async function removeMember(keycloak, userId) {
  return request(keycloak, `/members/${userId}`, { method: 'DELETE' });
}

async function updateMemberRole(keycloak, userId, role) {
  return request(keycloak, `/members/${userId}/role`, {
    method: 'PATCH',
    body: JSON.stringify({ role }),
  });
}

// ── Constants ─────────────────────────────────────────────────────────────────

const ROLES = [
  { value: 'ADMIN',   label: 'Admin',   desc: 'Full access — manage adapters, policies, members' },
  { value: 'ANALYST', label: 'Analyst', desc: 'View all data, run intents, export audit logs' },
  { value: 'VIEWER',  label: 'Viewer',  desc: 'Read-only access to dashboards and intents' },
];

const ROLE_COLORS = {
  ADMIN:   'bg-purple-100 text-purple-700',
  ANALYST: 'bg-blue-100 text-blue-700',
  VIEWER:  'bg-slate-100 text-slate-600',
};

const STATUS_COLORS = {
  PENDING:  'bg-amber-100 text-amber-700',
  ACCEPTED: 'bg-green-100 text-green-700',
  EXPIRED:  'bg-red-100 text-red-600',
};

// ── Sub-components ────────────────────────────────────────────────────────────

function RoleBadge({ role }) {
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${ROLE_COLORS[role] ?? 'bg-gray-100 text-gray-600'}`}>
      {role}
    </span>
  );
}

function StatusBadge({ status }) {
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${STATUS_COLORS[status] ?? 'bg-gray-100 text-gray-600'}`}>
      {status === 'PENDING' && <Clock size={10} />}
      {status === 'ACCEPTED' && <Check size={10} />}
      {status}
    </span>
  );
}

function InviteForm({ keycloak, onInvited }) {
  const [email, setEmail]     = useState('');
  const [role, setRole]       = useState('ANALYST');
  const [loading, setLoading] = useState(false);
  const [error, setError]     = useState(null);
  const [success, setSuccess] = useState(false);

  async function handleSubmit(e) {
    e.preventDefault();
    if (!email.trim() || !email.includes('@')) {
      setError('Please enter a valid email address');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      await sendInvitation(keycloak, { email: email.trim(), role });
      setEmail('');
      setSuccess(true);
      setTimeout(() => setSuccess(false), 3000);
      onInvited();
    } catch (err) {
      setError(err.message ?? 'Failed to send invitation');
    } finally {
      setLoading(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-2">
          <div className="p-1.5 rounded-lg bg-blue-50">
            <UserPlus size={14} className="text-blue-600" />
          </div>
          <CardTitle>Invite a team member</CardTitle>
        </div>
      </CardHeader>
      <CardContent>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="flex gap-3 flex-wrap">
            {/* Email */}
            <div className="flex-1 min-w-52">
              <label className="block text-xs font-medium text-slate-600 mb-1.5">
                Email address
              </label>
              <div className="relative">
                <Mail size={13} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
                <input
                  type="email"
                  value={email}
                  onChange={e => setEmail(e.target.value)}
                  placeholder="colleague@company.com"
                  className="w-full pl-8 pr-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>
            </div>

            {/* Role */}
            <div className="w-40">
              <label className="block text-xs font-medium text-slate-600 mb-1.5">
                Role
              </label>
              <select
                value={role}
                onChange={e => setRole(e.target.value)}
                className="w-full py-2 px-3 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white"
              >
                {ROLES.map(r => (
                  <option key={r.value} value={r.value}>{r.label}</option>
                ))}
              </select>
            </div>

            {/* Submit */}
            <div className="flex items-end">
              <Button type="submit" loading={loading} className="whitespace-nowrap">
                <UserPlus size={13} /> Send invite
              </Button>
            </div>
          </div>

          {/* Role description */}
          <p className="text-xs text-slate-400">
            {ROLES.find(r => r.value === role)?.desc}
          </p>

          {error && (
            <p className="text-xs text-red-600 bg-red-50 border border-red-100 rounded-lg px-3 py-2">
              {error}
            </p>
          )}
          {success && (
            <p className="text-xs text-green-700 bg-green-50 border border-green-100 rounded-lg px-3 py-2 flex items-center gap-1.5">
              <Check size={12} /> Invitation sent successfully
            </p>
          )}
        </form>
      </CardContent>
    </Card>
  );
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function InviteUsers({ keycloak }) {
  const [members,     setMembers]     = useState([]);
  const [invitations, setInvitations] = useState([]);
  const [loading,     setLoading]     = useState(true);
  const [copied,      setCopied]      = useState(null);
  const [editRole,    setEditRole]    = useState(null); // { userId, current }

  async function load() {
    try {
      const [m, i] = await Promise.allSettled([
        listMembers(keycloak),
        listInvitations(keycloak),
      ]);
      if (m.status === 'fulfilled' && m.value) setMembers(m.value ?? []);
      if (i.status === 'fulfilled' && i.value) setInvitations(i.value ?? []);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, [keycloak]);

  function copyLink(inv) {
    const link = inv.inviteLink ?? `${window.location.origin}/invite/${inv.token}`;
    navigator.clipboard.writeText(link);
    setCopied(inv.id);
    setTimeout(() => setCopied(null), 2000);
  }

  async function handleRevoke(id) {
    try { await revokeInvitation(keycloak, id); load(); } catch { /* ignore */ }
  }

  async function handleRemoveMember(userId) {
    try { await removeMember(keycloak, userId); load(); } catch { /* ignore */ }
  }

  async function handleRoleChange(userId, newRole) {
    try {
      await updateMemberRole(keycloak, userId, newRole);
      setEditRole(null);
      load();
    } catch { /* ignore */ }
  }

  const pending = invitations.filter(i => i.status === 'PENDING');

  return (
    <Page
      title="Team members"
      subtitle="Invite colleagues and manage access to your tenant"
    >
      {/* Invite form */}
      <InviteForm keycloak={keycloak} onInvited={load} />

      {loading ? (
        <div className="flex justify-center py-16"><Spinner className="w-8 h-8" /></div>
      ) : (
        <>
          {/* Pending invitations */}
          {pending.length > 0 && (
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Clock size={13} className="text-amber-500" />
                    <CardTitle>Pending invitations</CardTitle>
                  </div>
                  <span className="text-xs text-slate-400">{pending.length} awaiting</span>
                </div>
              </CardHeader>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-slate-100">
                      {['Email', 'Role', 'Status', 'Sent', 'Expires', ''].map(h => (
                        <th key={h} className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide whitespace-nowrap">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {pending.map(inv => (
                      <tr key={inv.id} className="border-b border-slate-50 hover:bg-slate-50 transition-colors">
                        <td className="px-5 py-3">
                          <div className="flex items-center gap-2">
                            <div className="w-7 h-7 rounded-full bg-slate-100 flex items-center justify-center text-xs font-medium text-slate-600">
                              {inv.email?.[0]?.toUpperCase() ?? '?'}
                            </div>
                            <span className="text-sm text-slate-700">{inv.email}</span>
                          </div>
                        </td>
                        <td className="px-5 py-3"><RoleBadge role={inv.role} /></td>
                        <td className="px-5 py-3"><StatusBadge status={inv.status} /></td>
                        <td className="px-5 py-3 text-xs text-slate-400" title={formatDate(inv.createdAt)}>
                          {formatRelative(inv.createdAt)}
                        </td>
                        <td className="px-5 py-3 text-xs text-slate-400">
                          {inv.expiresAt ? formatDate(inv.expiresAt) : '—'}
                        </td>
                        <td className="px-5 py-3">
                          <div className="flex items-center justify-end gap-1">
                            <button
                              onClick={() => copyLink(inv)}
                              title="Copy invite link"
                              className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400 hover:text-slate-700 transition-colors"
                            >
                              {copied === inv.id ? <Check size={13} className="text-green-500" /> : <Copy size={13} />}
                            </button>
                            <button
                              onClick={() => handleRevoke(inv.id)}
                              title="Revoke invitation"
                              className="p-1.5 rounded-lg hover:bg-red-50 text-slate-300 hover:text-red-500 transition-colors"
                            >
                              <Trash2 size={13} />
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          )}

          {/* Active members */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Users size={13} className="text-slate-400" />
                  <CardTitle>Active members</CardTitle>
                </div>
                <span className="text-xs text-slate-400">{members.length} members</span>
              </div>
            </CardHeader>
            {members.length === 0 ? (
              <EmptyState
                icon={<Users size={22} />}
                title="No members yet"
                description="Invite your first team member above"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-slate-100">
                      {['Member', 'Role', 'Joined', 'Last active', ''].map(h => (
                        <th key={h} className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide whitespace-nowrap">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {members.map(member => (
                      <tr key={member.userId} className="border-b border-slate-50 hover:bg-slate-50 transition-colors group">
                        <td className="px-5 py-3">
                          <div className="flex items-center gap-3">
                            <div className="w-8 h-8 rounded-full bg-gradient-to-br from-blue-400 to-indigo-500 flex items-center justify-center text-xs font-semibold text-white shrink-0">
                              {(member.name || member.email)?.[0]?.toUpperCase() ?? '?'}
                            </div>
                            <div>
                              <p className="text-sm font-medium text-slate-800">{member.name || '—'}</p>
                              <p className="text-xs text-slate-400">{member.email}</p>
                            </div>
                          </div>
                        </td>
                        <td className="px-5 py-3">
                          {editRole?.userId === member.userId ? (
                            <div className="flex items-center gap-2">
                              <select
                                defaultValue={member.role}
                                onChange={e => handleRoleChange(member.userId, e.target.value)}
                                autoFocus
                                className="text-xs border border-blue-300 rounded-lg px-2 py-1 focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white"
                              >
                                {ROLES.map(r => (
                                  <option key={r.value} value={r.value}>{r.label}</option>
                                ))}
                              </select>
                              <button
                                onClick={() => setEditRole(null)}
                                className="text-xs text-slate-400 hover:text-slate-600"
                              >
                                Cancel
                              </button>
                            </div>
                          ) : (
                            <div className="flex items-center gap-2">
                              <RoleBadge role={member.role} />
                              {!member.isCurrentUser && (
                                <button
                                  onClick={() => setEditRole({ userId: member.userId })}
                                  className="opacity-0 group-hover:opacity-100 text-xs text-slate-400 hover:text-blue-600 transition-opacity"
                                >
                                  Change
                                </button>
                              )}
                            </div>
                          )}
                        </td>
                        <td className="px-5 py-3 text-xs text-slate-400">
                          {member.joinedAt ? formatRelative(member.joinedAt) : '—'}
                        </td>
                        <td className="px-5 py-3 text-xs text-slate-400">
                          {member.lastActiveAt ? formatRelative(member.lastActiveAt) : 'Never'}
                        </td>
                        <td className="px-5 py-3 text-right">
                          {member.isCurrentUser ? (
                            <span className="text-xs text-slate-300 italic">You</span>
                          ) : (
                            <button
                              onClick={() => handleRemoveMember(member.userId)}
                              title="Remove member"
                              className="opacity-0 group-hover:opacity-100 p-1.5 rounded-lg hover:bg-red-50 text-slate-300 hover:text-red-500 transition-all"
                            >
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

          {/* Role legend */}
          <Card>
            <CardHeader>
              <div className="flex items-center gap-2">
                <Shield size={13} className="text-slate-400" />
                <CardTitle>Role permissions</CardTitle>
              </div>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                {ROLES.map(r => (
                  <div key={r.value} className="flex items-start gap-3 p-3 rounded-lg bg-slate-50 border border-slate-100">
                    <RoleBadge role={r.value} />
                    <div>
                      <p className="text-xs font-medium text-slate-700">{r.label}</p>
                      <p className="text-xs text-slate-400 mt-0.5">{r.desc}</p>
                    </div>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </>
      )}
    </Page>
  );
}
