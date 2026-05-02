import { useState, useEffect } from 'react';
import { Search, ScrollText, Download } from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, EmptyState, Spinner, Button } from '../components/shared';
import { listAudit } from '../utils/api';

function exportCsv(rows) {
  const header = 'Time,User,Action,Entity type,Entity ID,Tenant\n';
  const lines  = rows.map(e =>
    [e.timestamp, e.userId, e.action, e.entityType, e.entityId, e.tenantId].join(',')
  );
  const blob = new Blob([header + lines.join('\n')], { type: 'text/csv' });
  const url  = URL.createObjectURL(blob);
  const a    = document.createElement('a');
  a.href = url; a.download = 'audit-log.csv'; a.click();
  URL.revokeObjectURL(url);
}
import { formatDate, formatRelative, shortId } from '../lib/utils';

export default function AuditLog({ keycloak }) {
  const [data, setData]       = useState(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage]       = useState(0);
  const [userId, setUserId]   = useState('');
  const [action, setAction]   = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true);
    listAudit(keycloak, {
      page, size: 50,
      userId: userId || undefined,
      action: action || undefined,
    }).then(d => { if (active) { setData(d); setLoading(false); }});
    return () => { active = false; };
  }, [keycloak, page, userId, action]);

  const rows = data?.content ?? [];

  return (
    <Page title="Audit Log" subtitle={`${data?.totalElements ?? 0} events`}
      action={rows.length > 0 && (
        <Button variant="secondary" size="sm" onClick={() => exportCsv(rows)}>
          <Download size={13}/> Export CSV
        </Button>
      )}>
      <Card>
        <div className="px-5 py-3 border-b border-slate-100 flex flex-wrap items-center gap-3">
          <div className="relative">
            <Search size={13} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/>
            <input value={userId} onChange={e => { setUserId(e.target.value); setPage(0); }}
              placeholder="Filter by user ID…"
              className="pl-8 pr-3 py-1.5 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 w-52"/>
          </div>
          <div className="relative">
            <Search size={13} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/>
            <input value={action} onChange={e => { setAction(e.target.value); setPage(0); }}
              placeholder="Filter by action…"
              className="pl-8 pr-3 py-1.5 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 w-44"/>
          </div>
          {loading && <Spinner className="ml-auto w-4 h-4"/>}
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100">
                {['Time', 'User', 'Action', 'Entity type', 'Entity ID', 'Tenant'].map(h => (
                  <th key={h} className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {loading && !data ? (
                <tr><td colSpan={6} className="py-16 text-center"><Spinner className="mx-auto"/></td></tr>
              ) : rows.length === 0 ? (
                <tr><td colSpan={6}>
                  <EmptyState icon={<ScrollText size={22}/>} title="No audit events" description="Events are recorded as users take actions"/>
                </td></tr>
              ) : rows.map(e => (
                <tr key={e.id} className="border-b border-slate-50 hover:bg-slate-50 transition-colors">
                  <td className="px-5 py-3 text-xs text-slate-400 whitespace-nowrap" title={formatDate(e.timestamp)}>
                    {formatRelative(e.timestamp)}
                  </td>
                  <td className="px-5 py-3">
                    <span className="font-mono text-xs text-slate-500" style={{ fontFamily: "'JetBrains Mono', monospace" }}>
                      {shortId(e.userId)}
                    </span>
                  </td>
                  <td className="px-5 py-3">
                    <span className="text-xs font-medium text-slate-700 bg-slate-100 px-2 py-0.5 rounded">{e.action}</span>
                  </td>
                  <td className="px-5 py-3 text-xs text-slate-600">{e.entityType}</td>
                  <td className="px-5 py-3">
                    <span className="font-mono text-xs text-slate-500" style={{ fontFamily: "'JetBrains Mono', monospace" }}>
                      {shortId(e.entityId)}
                    </span>
                  </td>
                  <td className="px-5 py-3">
                    <span className="font-mono text-xs text-slate-400" style={{ fontFamily: "'JetBrains Mono', monospace" }}>
                      {shortId(e.tenantId)}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {(data?.totalPages ?? 0) > 1 && (
          <div className="px-5 py-3 border-t border-slate-100 flex items-center justify-between text-sm">
            <span className="text-xs text-slate-500">Page {page + 1} of {data.totalPages}</span>
            <div className="flex gap-2">
              <button disabled={page === 0} onClick={() => setPage(p => p - 1)}
                className="text-xs px-3 py-1.5 border border-slate-200 rounded-lg disabled:opacity-40 hover:bg-slate-50">
                Previous
              </button>
              <button disabled={page >= data.totalPages - 1} onClick={() => setPage(p => p + 1)}
                className="text-xs px-3 py-1.5 border border-slate-200 rounded-lg disabled:opacity-40 hover:bg-slate-50">
                Next
              </button>
            </div>
          </div>
        )}
      </Card>
    </Page>
  );
}
