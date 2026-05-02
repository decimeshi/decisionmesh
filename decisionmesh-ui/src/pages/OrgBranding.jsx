import { useState, useRef } from 'react';
import { Upload, Palette, Type, Check, RefreshCw, Eye } from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Button } from '../components/shared';
import { useBranding } from '../context/BrandingContext';
import { request } from '../utils/api';

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api';

const PRESET_COLORS = [
  { name: 'Blue',    value: '#2563eb' },
  { name: 'Indigo',  value: '#4f46e5' },
  { name: 'Violet',  value: '#7c3aed' },
  { name: 'Teal',    value: '#0d9488' },
  { name: 'Green',   value: '#16a34a' },
  { name: 'Orange',  value: '#ea580c' },
  { name: 'Rose',    value: '#e11d48' },
  { name: 'Slate',   value: '#475569' },
];

export default function OrgBranding({ keycloak }) {
  const { branding, updateBranding } = useBranding();

  const [form, setForm]         = useState({
    orgName:      branding.orgName,
    primaryColor: branding.primaryColor,
    logoUrl:      branding.logoUrl,
  });
  const [saving,    setSaving]    = useState(false);
  const [saved,     setSaved]     = useState(false);
  const [error,     setError]     = useState('');
  const [uploading, setUploading] = useState(false);
  const [preview,   setPreview]   = useState(branding.logoUrl);
  const fileRef = useRef(null);

  // ── Logo upload ─────────────────────────────────────────────────────────────
  async function handleLogoChange(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) { alert('Logo must be under 2 MB'); return; }
    setUploading(true);
    try {
      const formData = new FormData();
      formData.append('logo', file);
      const res = await fetch(`${API_BASE}/org/branding/logo`, {
        method:  'POST',
        headers: { Authorization: `Bearer ${keycloak?.token}` },
        body:    formData,
      });
      const url = res.ok
        ? ((await res.json().catch(() => null))?.logoUrl ?? URL.createObjectURL(file))
        : URL.createObjectURL(file);
      setPreview(url);
      setForm(f => ({ ...f, logoUrl: url }));
    } catch {
      const url = URL.createObjectURL(file);
      setPreview(url);
      setForm(f => ({ ...f, logoUrl: url }));
    } finally {
      setUploading(false);
    }
  }

  // ── Color selection — live preview only ─────────────────────────────────────
  function handleColorSelect(color) {
    setForm(f => ({ ...f, primaryColor: color }));
    updateBranding({ primaryColor: color }); // instant DOM preview
  }

  // ── Reload branding from backend and apply to DOM ───────────────────────────
  // Called after every successful save to confirm what was persisted
  // and apply the exact values returned by the backend.
  async function reloadBrandingFromBackend() {
    try {
      await keycloak.updateToken(30).catch(() => {});

      const res = await fetch(`${API_BASE}/org/branding`, {
        headers: {
          'Authorization': `Bearer ${keycloak.token}`,
          'Content-Type':  'application/json',
        },
      });

      if (res.ok) {
        const data = await res.json();
        console.log('[Branding] reloaded after save:', JSON.stringify(data));

        // Normalize — handles both camelCase and snake_case from backend
        const normalized = {
          primaryColor: data.primaryColor ?? data.primary_color ?? form.primaryColor,
          orgName:      data.orgName      ?? data.org_name      ?? form.orgName,
          logoUrl:      data.logoUrl      ?? data.logo_url      ?? null,
          favicon:      data.favicon      ?? null,
        };

        // Update context + apply to DOM
        updateBranding(normalized);

        // Sync local form state with what backend confirmed
        setForm(f => ({ ...f, ...normalized }));
      } else {
        console.error('[Branding] reload after save failed: HTTP', res.status);
      }
    } catch (err) {
      console.error('[Branding] reload exception:', err.message);
    }
  }

  // ── Save ────────────────────────────────────────────────────────────────────
  async function handleSave() {
    setError('');
    setSaving(true);
    try {
      // PATCH to save
      await request(keycloak, '/org/branding', {
        method: 'PATCH',
        body:   JSON.stringify(form),
      });

      // ── GET to reload saved data and apply to DOM ─────────────────────────
      // Without this, the color is only applied locally from form state.
      // After reload the page it would not persist because BrandingContext
      // GET happens on mount — before the save. Reloading here ensures the
      // backend-confirmed values are applied immediately after save.
      await reloadBrandingFromBackend();

      setSaved(true);
      setTimeout(() => setSaved(false), 2500);

    } catch (err) {
      const msg = err?.message || 'Failed to save branding';
      setError(msg);
      console.error('[OrgBranding] save failed:', msg);
      // Revert to last saved branding on failure
      updateBranding(branding);
      setForm({
        orgName:      branding.orgName,
        primaryColor: branding.primaryColor,
        logoUrl:      branding.logoUrl,
      });
    } finally {
      setSaving(false);
    }
  }

  // ── Reset ───────────────────────────────────────────────────────────────────
  function handleReset() {
    const defaults = { orgName: 'DecisionMesh', primaryColor: '#2563eb', logoUrl: null };
    setForm(defaults);
    setPreview(null);
    updateBranding(defaults);
  }

  const initial = form.orgName?.[0]?.toUpperCase() ?? 'D';

  return (
    <Page title="Organisation branding" subtitle="Customise how your organisation appears in the control plane">
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">

        {/* ── Settings ───────────────────────────────────────────────────── */}
        <div className="xl:col-span-2 space-y-5">

          {/* Logo */}
          <Card>
            <CardHeader>
              <div className="flex items-center gap-2">
                <Upload size={13} className="text-slate-400" />
                <CardTitle>Organisation logo</CardTitle>
              </div>
            </CardHeader>
            <CardContent>
              <div className="flex items-center gap-5">
                <div className="w-16 h-16 rounded-xl border-2 border-dashed border-slate-200 flex items-center justify-center overflow-hidden bg-slate-50 shrink-0">
                  {preview
                    ? <img src={preview} alt="Logo" className="w-full h-full object-contain" />
                    : <span className="text-2xl font-bold text-slate-400">{initial}</span>
                  }
                </div>
                <div className="space-y-2">
                  <input ref={fileRef} type="file" accept="image/*" className="hidden" onChange={handleLogoChange} />
                  <Button variant="secondary" size="sm" loading={uploading} onClick={() => fileRef.current?.click()}>
                    <Upload size={13} /> {preview ? 'Change logo' : 'Upload logo'}
                  </Button>
                  {preview && (
                    <button
                      onClick={() => { setPreview(null); setForm(f => ({ ...f, logoUrl: null })); }}
                      className="block text-xs text-red-500 hover:text-red-700"
                    >
                      Remove logo
                    </button>
                  )}
                  <p className="text-xs text-slate-400">PNG, SVG, or JPG — max 2 MB. Recommended: 128×128px</p>
                </div>
              </div>
            </CardContent>
          </Card>

          {/* Display name */}
          <Card>
            <CardHeader>
              <div className="flex items-center gap-2">
                <Type size={13} className="text-slate-400" />
                <CardTitle>Organisation name</CardTitle>
              </div>
            </CardHeader>
            <CardContent>
              <div className="max-w-sm space-y-1.5">
                <label className="block text-xs font-medium text-slate-600">Display name</label>
                <input
                  value={form.orgName}
                  onChange={e => setForm(f => ({ ...f, orgName: e.target.value }))}
                  placeholder="Acme Corporation"
                  className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2
                    focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
                <p className="text-xs text-slate-400">Shown in the sidebar header and browser tab title</p>
              </div>
            </CardContent>
          </Card>

          {/* Brand colour */}
          <Card>
            <CardHeader>
              <div className="flex items-center gap-2">
                <Palette size={13} className="text-slate-400" />
                <CardTitle>Primary colour</CardTitle>
              </div>
            </CardHeader>
            <CardContent className="space-y-4">
              <div>
                <p className="text-xs font-medium text-slate-600 mb-3">Preset colours</p>
                <div className="flex flex-wrap gap-2.5">
                  {PRESET_COLORS.map(({ name, value }) => (
                    <button
                      key={value}
                      title={name}
                      onClick={() => handleColorSelect(value)}
                      className="relative w-8 h-8 rounded-full transition-transform hover:scale-110
                        focus:outline-none focus:ring-2 focus:ring-offset-2"
                      style={{ backgroundColor: value }}
                    >
                      {form.primaryColor === value && (
                        <Check size={14} className="absolute inset-0 m-auto text-white drop-shadow" />
                      )}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <p className="text-xs font-medium text-slate-600 mb-2">Custom colour</p>
                <div className="flex items-center gap-3">
                  <input
                    type="color"
                    value={form.primaryColor}
                    onChange={e => handleColorSelect(e.target.value)}
                    className="w-10 h-10 rounded-lg border border-slate-200 cursor-pointer p-0.5"
                  />
                  <input
                    type="text"
                    value={form.primaryColor}
                    onChange={e => {
                      const val = e.target.value;
                      if (/^#[0-9a-fA-F]{6}$/.test(val)) handleColorSelect(val);
                      else setForm(f => ({ ...f, primaryColor: val }));
                    }}
                    placeholder="#2563eb"
                    maxLength={7}
                    className="w-28 text-sm font-mono border border-slate-200 rounded-lg px-3 py-2
                      focus:outline-none focus:ring-2 focus:ring-blue-500"
                    style={{ fontFamily: "'JetBrains Mono', monospace" }}
                  />
                  <div className="flex-1 h-8 rounded-lg border border-slate-100"
                    style={{ background: form.primaryColor }} />
                </div>
              </div>
            </CardContent>
          </Card>

          {/* Error */}
          {error && (
            <div className="px-4 py-3 rounded-lg bg-red-50 border border-red-100">
              <p className="text-sm text-red-600">{error}</p>
            </div>
          )}

          {/* Actions */}
          <div className="flex items-center gap-3">
            <Button loading={saving} onClick={handleSave}>
              {saved ? <><Check size={13} /> Saved</> : 'Save branding'}
            </Button>
            <Button variant="secondary" onClick={handleReset}>
              <RefreshCw size={13} /> Reset to defaults
            </Button>
          </div>
        </div>

        {/* ── Live preview ────────────────────────────────────────────────── */}
        <div>
          <p className="text-xs font-semibold text-slate-500 uppercase tracking-wider mb-3 flex items-center gap-2">
            <Eye size={12} /> Live preview
          </p>
          <div className="border border-slate-200 rounded-xl overflow-hidden shadow-sm">
            <div className="flex h-64" style={{ background: '#f8fafc' }}>
              <div className="w-40 bg-white border-r border-slate-100 flex flex-col">
                <div className="flex items-center gap-2 px-3 py-3 border-b border-slate-100">
                  <div className="w-6 h-6 rounded-md flex items-center justify-center shrink-0 overflow-hidden"
                    style={{ backgroundColor: form.primaryColor }}>
                    {preview
                      ? <img src={preview} className="w-full h-full object-contain" alt="" />
                      : <span className="text-white text-[10px] font-bold">{initial}</span>
                    }
                  </div>
                  <span className="text-xs font-semibold text-slate-800 truncate">
                    {form.orgName || 'Your Org'}
                  </span>
                </div>
                {['Dashboard', 'Intents', 'Adapters', 'Policies', 'Audit'].map((item, i) => (
                  <div key={item} className="flex items-center gap-2 mx-1.5 px-2 py-1.5 rounded-md my-0.5"
                    style={i === 0 ? { backgroundColor: `${form.primaryColor}18` } : {}}>
                    <div className="w-2.5 h-2.5 rounded-sm shrink-0"
                      style={{ backgroundColor: i === 0 ? form.primaryColor : '#cbd5e1' }} />
                    <span className="text-[10px] font-medium"
                      style={{ color: i === 0 ? form.primaryColor : '#64748b' }}>{item}</span>
                  </div>
                ))}
              </div>
              <div className="flex-1 p-3 space-y-2">
                <div className="flex items-center justify-between bg-white rounded-lg px-3 py-1.5 border border-slate-100">
                  <span className="text-[10px] font-medium text-slate-700">Dashboard</span>
                  <div className="w-5 h-5 rounded-full shrink-0"
                    style={{ backgroundColor: form.primaryColor, opacity: 0.8 }} />
                </div>
                <div className="grid grid-cols-2 gap-1.5">
                  {['Intents', 'Cost', 'Success', 'Drift'].map(label => (
                    <div key={label} className="bg-white rounded-lg p-2 border border-slate-100">
                      <div className="w-4 h-1 rounded-full mb-1.5"
                        style={{ backgroundColor: form.primaryColor, opacity: 0.3 }} />
                      <div className="text-[10px] font-bold text-slate-700">—</div>
                      <div className="text-[9px] text-slate-400">{label}</div>
                    </div>
                  ))}
                </div>
                <div className="flex gap-1">
                  <div className="px-2 py-0.5 rounded text-[9px] font-semibold text-white"
                    style={{ backgroundColor: form.primaryColor }}>Primary</div>
                  <div className="px-2 py-0.5 rounded text-[9px] font-medium text-slate-600 border border-slate-200 bg-white">
                    Secondary</div>
                </div>
              </div>
            </div>
          </div>
          <div className="mt-3 space-y-1.5 text-xs text-slate-500">
            <p className="flex items-center gap-2">
              <span className="w-3 h-3 rounded-full shrink-0" style={{ backgroundColor: form.primaryColor }} />
              Active nav highlight
            </p>
            <p className="flex items-center gap-2">
              <span className="w-3 h-3 rounded-full shrink-0"
                style={{ backgroundColor: form.primaryColor, opacity: 0.3 }} />
              Metric card accents
            </p>
            <p className="flex items-center gap-2">
              <span className="w-3 h-3 rounded-full shrink-0"
                style={{ backgroundColor: form.primaryColor, opacity: 0.15 }} />
              Focus rings, badges
            </p>
          </div>
        </div>
      </div>
    </Page>
  );
}
