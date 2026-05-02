import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, FolderOpen, Settings, Zap, CheckCircle2, Clock, AlertCircle } from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Button, Spinner } from '../components/shared';
import { useProject } from '../context/ProjectContext';
import { formatRelative } from '../lib/utils';

const ENV_COLORS = {
  Production: 'bg-green-100 text-green-700',
  Staging:    'bg-amber-100 text-amber-700',
  Dev:        'bg-blue-100 text-blue-700',
};

const ENV_DOTS = {
  Production: 'bg-green-500',
  Staging:    'bg-amber-500',
  Dev:        'bg-blue-500',
};

function NewProjectModal({ onSave, onClose, keycloak }) {
  const [form, setForm]       = useState({ name: '', description: '', environment: 'Production' });
  const [saving, setSaving]   = useState(false);
  const [error, setError]     = useState(null);

  async function handleSave() {
    if (!form.name.trim()) { setError('Project name is required'); return; }
    setSaving(true);
    try {
      const res = await fetch('http://localhost:8080/api/projects', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${keycloak.token}`,
        },
        body: JSON.stringify(form),
      });
      const created = res.ok ? await res.json() : { ...form, id: crypto.randomUUID(), isDefault: false, createdAt: new Date().toISOString() };
      onSave(created);
      onClose();
    } catch {
      // API not ready — still create locally
      onSave({ ...form, id: crypto.randomUUID(), isDefault: false, createdAt: new Date().toISOString() });
      onClose();
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
      <Card className="w-full max-w-md">
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle>New project</CardTitle>
            <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-xl leading-none">×</button>
          </div>
        </CardHeader>
        <CardContent className="space-y-4">
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1.5">Project name *</label>
            <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
              placeholder="e.g. Production AI, Customer Support Bot"
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500" />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1.5">Description</label>
            <input value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
              placeholder="What is this project for?"
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500" />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1.5">Environment</label>
            <select value={form.environment} onChange={e => setForm(f => ({ ...f, environment: e.target.value }))}
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white">
              {['Production', 'Staging', 'Dev'].map(e => <option key={e}>{e}</option>)}
            </select>
          </div>
          {error && <p className="text-xs text-red-600">{error}</p>}
        </CardContent>
        <div className="px-5 py-4 border-t border-slate-100 flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button loading={saving} onClick={handleSave}><Plus size={13} /> Create project</Button>
        </div>
      </Card>
    </div>
  );
}

export default function Projects({ keycloak }) {
  const navigate = useNavigate();
  const { projects, activeProject, switchProject, addProject, loading } = useProject();
  const [showModal, setShowModal] = useState(false);

  function handleSwitch(project) {
    switchProject(project);
    navigate('/');
  }

  return (
    <Page
      title="Projects"
      subtitle={`${projects.length} project${projects.length !== 1 ? 's' : ''} in your organisation`}
      action={<Button onClick={() => setShowModal(true)}><Plus size={14} /> New project</Button>}
    >
      {loading ? (
        <div className="flex justify-center py-16"><Spinner className="w-8 h-8" /></div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
          {projects.map(project => {
            const isActive = project.id === activeProject?.id;
            return (
              <Card key={project.id}
                className={`relative transition-all cursor-pointer hover:shadow-md ${isActive ? 'ring-2 ring-blue-500 ring-offset-1' : ''}`}
                onClick={() => handleSwitch(project)}>

                {/* Active indicator */}
                {isActive && (
                  <div className="absolute top-3 right-3">
                    <span className="flex items-center gap-1 text-xs font-medium text-blue-600 bg-blue-50 px-2 py-0.5 rounded-full">
                      <CheckCircle2 size={11} /> Active
                    </span>
                  </div>
                )}

                <CardContent className="pt-5">
                  {/* Icon + name */}
                  <div className="flex items-start gap-3 mb-4">
                    <div className={`p-2.5 rounded-xl shrink-0 ${isActive ? 'bg-blue-600 text-white' : 'bg-slate-100 text-slate-500'}`}>
                      <FolderOpen size={18} />
                    </div>
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <h3 className="text-sm font-semibold text-slate-800 truncate">{project.name}</h3>
                        {project.isDefault && (
                          <span className="text-xs text-slate-400 bg-slate-100 px-1.5 py-0.5 rounded-full">default</span>
                        )}
                      </div>
                      {project.description && (
                        <p className="text-xs text-slate-400 mt-0.5 truncate">{project.description}</p>
                      )}
                    </div>
                  </div>

                  {/* Stats row */}
                  <div className="flex items-center justify-between text-xs text-slate-400">
                    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full font-medium ${ENV_COLORS[project.environment] ?? 'bg-slate-100 text-slate-600'}`}>
                      <span className={`w-1.5 h-1.5 rounded-full ${ENV_DOTS[project.environment] ?? 'bg-slate-400'}`} />
                      {project.environment}
                    </span>
                    <span>{project.createdAt ? formatRelative(project.createdAt) : ''}</span>
                  </div>

                  {/* Quick stats */}
                  <div className="mt-3 pt-3 border-t border-slate-50 grid grid-cols-3 gap-2 text-center">
                    {[
                      { label: 'Intents', value: project.intentCount ?? '—' },
                      { label: 'Members', value: project.memberCount ?? '—' },
                      { label: 'Adapters', value: project.adapterCount ?? '—' },
                    ].map(({ label, value }) => (
                      <div key={label}>
                        <p className="text-sm font-semibold text-slate-700">{value}</p>
                        <p className="text-[11px] text-slate-400">{label}</p>
                      </div>
                    ))}
                  </div>
                </CardContent>

                {/* Settings link */}
                <div className="px-5 pb-4 flex justify-end">
                  <button
                    onClick={e => { e.stopPropagation(); navigate(`/projects/${project.id}/settings`); }}
                    className="text-xs text-slate-400 hover:text-slate-700 flex items-center gap-1 transition-colors">
                    <Settings size={12} /> Settings
                  </button>
                </div>
              </Card>
            );
          })}
        </div>
      )}

      {showModal && (
        <NewProjectModal
          keycloak={keycloak}
          onSave={addProject}
          onClose={() => setShowModal(false)}
        />
      )}
    </Page>
  );
}
