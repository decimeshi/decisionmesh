import { useState, useEffect } from 'react';
import { request } from '../utils/api';
import Page from '../components/shared/Page';
import { useNavigate } from 'react-router-dom';

// ── Category colours ──────────────────────────────────────────────────────────
const CAT_COLOR = {
  PAYMENTS:'#1d4ed8',  LENDING:'#15803d',      AP_AR:'#b45309',
  TREASURY:'#0f766e',  FRAUD:'#dc2626',         COMPLIANCE:'#7c3aed',
  PROCUREMENT:'#c2410c', INVESTMENTS:'#0369a1', CUSTOMER_OPS:'#0e7490',
  RECONCILIATION:'#1e40af', BILLING:'#6d28d9',  INSURANCE:'#065f46',
  REPORTING:'#374151', RISK_MANAGEMENT:'#9f1239',
};
const catColor = (c) => CAT_COLOR[c] ?? '#475569';

// ── Risk config ───────────────────────────────────────────────────────────────
const RISK = {
  HIGH:   { bg:'#fef2f2', text:'#991b1b', dot:'#dc2626', label:'High'   },
  MEDIUM: { bg:'#fffbeb', text:'#92400e', dot:'#d97706', label:'Medium' },
  LOW:    { bg:'#f0fdf4', text:'#14532d', dot:'#16a34a', label:'Low'    },
};

// ── Intent card ───────────────────────────────────────────────────────────────
function IntentCard({ intent, onSelect, compact }) {
    const navigate = useNavigate();
    const risk = RISK[intent.riskLevel] ?? RISK.MEDIUM;
    const col  = catColor(intent.category);

    return (
        <div
            className="rounded-xl border flex flex-col gap-2 transition-colors"
            style={{
                padding: compact ? '10px 12px' : '12px 14px',
                borderLeft: `3px solid ${col}`,
                borderTop:'1px solid #e2e8f0', borderRight:'1px solid #e2e8f0',
                borderBottom:'1px solid #e2e8f0',
                background:'white',
            }}
        >
            {/* Name row */}
            <div className="flex items-start justify-between gap-2">
                <p className="text-sm font-medium text-slate-800 capitalize leading-snug">
                    {intent.name.replace(/_/g, ' ')}
                </p>
                {intent.isActive === false && (
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-slate-100 text-slate-400 shrink-0">
            inactive
          </span>
                )}
            </div>

            {!compact && intent.description && (
                <p className="text-xs text-slate-500 leading-relaxed line-clamp-2">{intent.description}</p>
            )}

            <div className="flex items-center gap-1.5 flex-wrap">
        <span className="flex items-center gap-1 text-[11px] px-2 py-0.5 rounded-full"
              style={{ background: risk.bg, color: risk.text }}>
          <span className="w-1.5 h-1.5 rounded-full" style={{ background: risk.dot }} />
            {risk.label}
        </span>
                {intent.defaultPolicy && (
                    <span className="text-[11px] px-2 py-0.5 rounded-full bg-slate-100 text-slate-500 capitalize">
            {intent.defaultPolicy.replace(/_/g, ' ')}
          </span>
                )}
                {intent.slaMs != null && (
                    <span className="text-[11px] px-2 py-0.5 rounded-full bg-slate-100 text-slate-500 ml-auto">
            {intent.slaMs} ms
          </span>
                )}
            </div>

            {!compact && intent.tags?.length > 0 && (
                <div className="flex gap-1 flex-wrap">
                    {intent.tags.slice(0, 4).map(tag => (
                        <span key={tag} className="text-[10px] px-1.5 py-0.5 rounded bg-slate-50 text-slate-400 border border-slate-100">
              {tag}
            </span>
                    ))}
                </div>
            )}

            {/* Actions */}
            {onSelect ? (
                // Picker mode (inside Playground panel)
                <button
                    onClick={() => onSelect(intent)}
                    className="text-[10px] text-blue-500 font-medium text-left hover:text-blue-700">
                    Click to use in Playground →
                </button>
            ) : (
                // Standalone library page — show action buttons
                <div className="flex gap-1.5 mt-1">
                    <button
                        onClick={() => navigate(`/playground?intent=${intent.name}`)}
                        className="flex-1 text-[11px] px-2 py-1 rounded-lg bg-blue-50 text-blue-600 hover:bg-blue-100 font-medium transition-colors">
                        Try in Playground
                    </button>
                    <button
                        onClick={() => navigate(`/policies/new?intentType=${intent.name}`)}
                        className="text-[11px] px-2 py-1 rounded-lg bg-slate-100 text-slate-500 hover:bg-slate-200 transition-colors">
                        Add Policy
                    </button>
                </div>
            )}
        </div>
    );
}

// ── Category pill ─────────────────────────────────────────────────────────────
// cat is a CategoryResponse object: { category, categoryLabel, count, ... }
function CategoryPill({ cat, selected, onClick }) {
  const col = catColor(cat.category);
  const isSelected = selected?.category === cat.category;
  return (
    <button onClick={onClick}
      className="flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs whitespace-nowrap transition-all"
      style={{
        border: isSelected ? `1.5px solid ${col}` : '1px solid #e2e8f0',
        background: isSelected ? col + '18' : 'white',
        color: isSelected ? col : '#64748b',
        fontWeight: isSelected ? 600 : 400,
      }}>
      <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ background: col }} />
      {(cat.categoryLabel || cat.category).replace(/_/g, ' ')}
      <span className="text-[10px] px-1.5 py-0.5 rounded-full"
        style={{ background: isSelected ? col : '#f1f5f9', color: isSelected ? '#fff' : '#94a3b8' }}>
        {cat.count ?? 0}
      </span>
    </button>
  );
}

// ── Main ──────────────────────────────────────────────────────────────────────
// Props:
//   keycloak  — Keycloak instance (required)
//   onSelect  — callback(intent) — enables picker mode for Playground panel
//   compact   — hides descriptions for tight layout
//   vertical  — "FINTECH" (default); future: "LEGAL", "HEALTHCARE"
export default function FintechIntents({ keycloak, onSelect, compact = false, vertical = 'FINTECH' }) {
  // categories is List<CategoryResponse>: [{ category, categoryLabel, count, ... }]
  const [categories,   setCategories]   = useState([]);
  // selected is a CategoryResponse object (or null)
  const [selected,     setSelected]     = useState(null);
  const [intents,      setIntents]      = useState([]);
  const [loadingCats,  setLoadingCats]  = useState(true);
  const [loadingItems, setLoadingItems] = useState(false);
  const [search,       setSearch]       = useState('');
  const [riskFilter,   setRiskFilter]   = useState('ALL');
  const [error,        setError]        = useState(null);

  // Load categories — counts come from CategoryResponse.count directly,
  // no need to fetch all 264 intents just to count them.
  useEffect(() => {
    setLoadingCats(true);
    setError(null);
    request(keycloak, `/intent-library/${vertical.toLowerCase()}/meta/categories`)
      .then(cats => {
        const list = cats ?? [];
        setCategories(list);
        // Auto-select first category
        if (list.length > 0) setSelected(list[0]);
      })
      .catch(err => setError(err.message))
      .finally(() => setLoadingCats(false));
  }, [vertical]);

  // Load intents for selected category
  // selected is a CategoryResponse object — use selected.category for the path
  useEffect(() => {
    if (!selected?.category) return;
    setLoadingItems(true);
    setSearch('');
    setRiskFilter('ALL');
    request(keycloak, `/intent-library/${vertical.toLowerCase()}/by-category/${selected.category}`)
      .then(d => setIntents(d ?? []))
      .catch(err => setError(err.message))
      .finally(() => setLoadingItems(false));
  }, [selected, vertical]);

  const filtered = intents.filter(i => {
    const q = search.toLowerCase();
    const matchQ    = !q || i.name.toLowerCase().includes(q) || i.description?.toLowerCase().includes(q)
                         || i.tags?.some(t => t.toLowerCase().includes(q));
    const matchRisk = riskFilter === 'ALL' || i.riskLevel === riskFilter;
    return matchQ && matchRisk;
  });

  const total = categories.reduce((sum, c) => sum + (c.count ?? 0), 0);
  const col   = selected ? catColor(selected.category) : '#475569';

  const body = (
    <div className="flex flex-col gap-3">

      {/* Header */}
      <div className="flex items-start justify-between flex-wrap gap-2">
        {onSelect ? (
          <p className="text-xs text-slate-400">
            {loadingCats ? '…' : `${total} intents — click one to pre-fill the Playground`}
          </p>
        ) : (
          <div>
            <h1 className="text-xl font-semibold text-slate-900">Fintech intent library</h1>
            <p className="text-sm text-slate-500 mt-0.5">
              {loadingCats ? '…' : `${total} intents across ${categories.length} categories`}
            </p>
          </div>
        )}

        {/* Risk filter */}
        <div className="flex gap-1.5 ml-auto flex-wrap">
          {['ALL','HIGH','MEDIUM','LOW'].map(r => {
            const active = riskFilter === r;
            const rc = r==='HIGH'?'#dc2626': r==='MEDIUM'?'#d97706': r==='LOW'?'#16a34a':'#475569';
            return (
              <button key={r} onClick={() => setRiskFilter(r)}
                className="px-2.5 py-0.5 rounded-full text-xs transition-all"
                style={{
                  border: active ? `1.5px solid ${rc}` : '1px solid #e2e8f0',
                  background: active ? rc+'14' : 'transparent',
                  color: active ? rc : '#94a3b8',
                  fontWeight: active ? 600 : 400,
                }}>
                {r === 'ALL' ? 'All' : r.charAt(0)+r.slice(1).toLowerCase()}
              </button>
            );
          })}
        </div>
      </div>

      {error && (
        <div className="px-3 py-2 rounded-lg bg-red-50 border border-red-200 text-xs text-red-700">{error}</div>
      )}

      {/* Category pills — each cat is a CategoryResponse object */}
      <div className="flex gap-1.5 overflow-x-auto pb-1 border-b border-slate-100" style={{ scrollbarWidth:'none' }}>
        {loadingCats
          ? Array.from({ length:7 }).map((_,i) => (
              <div key={i} className="h-7 rounded-full bg-slate-100 shrink-0 animate-pulse" style={{ width: 80+i*12 }} />
            ))
          : categories.map(cat => (
              <CategoryPill
                key={cat.category}
                cat={cat}
                selected={selected}
                onClick={() => setSelected(cat)}
              />
            ))
        }
      </div>

      {/* Active category label + search */}
      {selected && (
        <div className="flex items-center gap-3 flex-wrap">
          <div className="flex items-center gap-2 flex-1">
            <span className="w-2 h-2 rounded-full shrink-0" style={{ background: col }} />
            <span className="text-sm font-medium text-slate-700">
              {(selected.categoryLabel || selected.category).replace(/_/g, ' ')}
            </span>
            <span className="text-xs text-slate-400">
              {loadingItems ? '…' : `${filtered.length} / ${intents.length}`}
            </span>
          </div>
          <div className="relative">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2"
              className="absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none">
              <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
            </svg>
            <input type="text" placeholder="Search name, description, tag…" value={search}
              onChange={e => setSearch(e.target.value)}
              className="pl-7 pr-3 py-1.5 text-xs border border-slate-200 rounded-lg w-48 focus:outline-none focus:ring-1 focus:ring-blue-400 bg-white" />
          </div>
        </div>
      )}

      {/* Intent grid */}
      {loadingItems ? (
        <div className="grid gap-2" style={{ gridTemplateColumns:'repeat(auto-fill,minmax(200px,1fr))' }}>
          {Array.from({ length:9 }).map((_,i) => (
            <div key={i} className="h-24 rounded-xl bg-slate-100 animate-pulse" />
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <div className="text-center py-10 text-slate-400 text-sm">
          {search ? `No intents matching "${search}"` : 'No intents in this category'}
        </div>
      ) : (
        <div className="grid gap-2" style={{ gridTemplateColumns:'repeat(auto-fill,minmax(200px,1fr))' }}>
          {filtered.map(intent => (
            <IntentCard key={intent.id} intent={intent} onSelect={onSelect} compact={compact} />
          ))}
        </div>
      )}
    </div>
  );

  // Picker mode (inside Playground) — bare content, no Page wrapper
  if (onSelect) return body;

  // Standalone page — wrap in Page layout
  return (
    <Page title="Intent Library" subtitle="Browse 264 fintech AI decision templates">
      {body}
    </Page>
  );
}
