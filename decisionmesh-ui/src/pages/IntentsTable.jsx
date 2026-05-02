import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Search, ChevronUp, ChevronDown, ListOrdered } from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, Button, PhaseBadge, SatisfactionBadge, EmptyState, Spinner } from '../components/shared';
import { listIntents } from '../utils/api';
import { formatCost, formatDate, formatRelative, shortId } from '../lib/utils';


// ── Category colour map — matches FintechIntents.jsx ─────────────────────────
const CAT_COLOR = {
  PAYMENTS:'#1d4ed8', LENDING:'#15803d', AP_AR:'#b45309', TREASURY:'#0f766e',
  FRAUD:'#dc2626', COMPLIANCE:'#7c3aed', PROCUREMENT:'#c2410c',
  INVESTMENTS:'#0369a1', CUSTOMER_OPS:'#0e7490', RECONCILIATION:'#1e40af',
  BILLING:'#6d28d9', INSURANCE:'#065f46', REPORTING:'#374151',
  RISK_MANAGEMENT:'#9f1239',
};
// Heuristic: match intentType (snake_case) against known category prefixes
function intentDotColor(intentType) {
  if (!intentType) return '#94a3b8';
  const t = intentType.toUpperCase();
  for (const [cat, col] of Object.entries(CAT_COLOR)) {
    const prefix = cat.split('_')[0];   // "RISK" from "RISK_MANAGEMENT"
    if (t.startsWith(prefix) || t.includes(cat.replace('_',''))) return col;
  }
  return '#94a3b8';
}

const PHASES = ['CREATED','PLANNING','PLANNED','EXECUTING','EVALUATING','COMPLETED','RETRY_SCHEDULED'];

export default function IntentsTable({ keycloak }) {
  const navigate = useNavigate();
  const [data, setData]     = useState(null);
  const [loading, setLoading] = useState(true);
  const [phase, setPhase]   = useState('');
  const [page, setPage]     = useState(0);
  const [search, setSearch] = useState('');
  const [sort, setSort]     = useState({ field: 'createdAt', dir: 'desc' });

  useEffect(() => {
    let active = true;
    setLoading(true);
    listIntents(keycloak, {
      page, size: 20,
      phase: phase || undefined,
      sort: `${sort.field},${sort.dir}`,
    }).then(d => { if (active) { setData(d); setLoading(false); } })
      .catch(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [keycloak, page, phase, sort]);

  function toggleSort(field) {
    setSort(s => s.field === field ? { field, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { field, dir: 'desc' });
    setPage(0);
  }

  function SortIcon({ field }) {
    if (sort.field !== field) return <ChevronUp size={12} className="text-slate-300"/>;
    return sort.dir === 'asc' ? <ChevronUp size={12} className="text-blue-600"/> : <ChevronDown size={12} className="text-blue-600"/>;
  }

  const rows = (data?.content ?? []).filter(i =>
    !search || i.id.includes(search) || i.intentType?.toUpperCase().includes(search.toUpperCase())
  );

  const COLS = [
    { h: 'Intent ID',    f: '',           s: false },
    { h: 'Type',         f: 'intentType', s: true  },
    { h: 'Phase',        f: 'phase',      s: true  },
    { h: 'Satisfaction', f: '',           s: false },
    { h: 'Cost',         f: '',           s: false },
    { h: 'Version',      f: 'version',    s: true  },
    { h: 'Created',      f: 'createdAt',  s: true  },
  ];

  return (
    <Page title="Intents" subtitle={`${data?.totalElements ?? 0} total`}
      action={<Button onClick={() => navigate('/playground')}>+ New intent</Button>}>
      <Card>
        {/* Toolbar */}
        <div className="px-5 py-3 border-b border-slate-100 flex flex-wrap items-center gap-3">
          <div className="relative">
            <Search size={13} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/>
            <input value={search} onChange={e => setSearch(e.target.value)}
              placeholder="Search by ID or type…"
              className="pl-8 pr-3 py-1.5 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 w-52"/>
          </div>
          <div className="flex gap-1.5 flex-wrap">
            {['', ...PHASES].map(p => (
              <button key={p}
                onClick={() => { setPhase(p); setPage(0); }}
                className={`px-2.5 py-1 rounded-full text-xs font-medium transition-colors ${
                  phase === p ? 'bg-blue-600 text-white' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                }`}>
                {p || 'All'}
              </button>
            ))}
          </div>
          {loading && <Spinner className="ml-auto w-4 h-4"/>}
        </div>

        {/* Table */}
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100">
                {COLS.map(({ h, f, s }) => (
                  <th key={h}
                    onClick={() => s && f && toggleSort(f)}
                    className={`px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide whitespace-nowrap ${s ? 'cursor-pointer hover:text-slate-700 select-none' : ''}`}>
                    <span className="inline-flex items-center gap-1">{h}{s && f && <SortIcon field={f}/>}</span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {loading && !data ? (
                <tr><td colSpan={7} className="py-16 text-center"><Spinner className="mx-auto"/></td></tr>
              ) : rows.length === 0 ? (
                <tr><td colSpan={7}>
                  <EmptyState icon={<ListOrdered size={22}/>}
                    title="No intents found"
                    description={phase ? `No intents in ${phase} phase` : 'Submit your first intent from the Playground'}
                    action={<Button size="sm" onClick={() => navigate('/playground')}>Open Playground</Button>}/>
                </td></tr>
              ) : rows.map(intent => (
                <tr key={intent.id}
                  className="border-b border-slate-50 hover:bg-blue-50/30 cursor-pointer transition-colors group"
                  onClick={() => navigate(`/intents/${intent.id}`)}>
                  <td className="px-5 py-3">
                    <span className="font-mono text-xs bg-slate-100 group-hover:bg-white px-1.5 py-0.5 rounded transition-colors"
                      style={{ fontFamily: "'JetBrains Mono', monospace" }}>
                      {shortId(intent.id)}
                    </span>
                  </td>
                  <td className="px-5 py-3">
                    <div className="flex items-center gap-1.5">
                      <span className="w-1.5 h-1.5 rounded-full shrink-0"
                        style={{ background: intentDotColor(intent.intentType) }} />
                      <span className="text-xs font-medium text-slate-700">{intent.intentType}</span>
                    </div>
                  </td>
                  <td className="px-5 py-3"><PhaseBadge phase={intent.phase}/></td>
                  <td className="px-5 py-3"><SatisfactionBadge state={intent.satisfactionState}/></td>
                  <td className="px-5 py-3 text-xs tabular-nums text-slate-700">{formatCost(intent.budget?.spentUsd ?? 0)}</td>
                  <td className="px-5 py-3 text-xs text-slate-500">v{intent.version}</td>
                  <td className="px-5 py-3 text-xs text-slate-400" title={formatDate(intent.createdAt)}>{formatRelative(intent.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {(data?.totalPages ?? 0) > 1 && (
          <div className="px-5 py-3 border-t border-slate-100 flex items-center justify-between">
            <span className="text-xs text-slate-500">Page {page + 1} of {data.totalPages}</span>
            <div className="flex gap-2">
              <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</Button>
              <Button variant="secondary" size="sm" disabled={page >= data.totalPages - 1} onClick={() => setPage(p => p + 1)}>Next</Button>
            </div>
          </div>
        )}
      </Card>
    </Page>
  );
}
