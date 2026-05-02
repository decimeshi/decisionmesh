import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Cpu, RefreshCw } from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, Button, EmptyState, Spinner } from '../components/shared';
import { listExecutions } from '../utils/api';
import { formatCost, formatDate, formatLatency, formatRelative, shortId, cn } from '../lib/utils';

const STATUS_COLORS = {
  RUNNING: 'bg-blue-100 text-blue-700',
  SUCCESS: 'bg-green-100 text-green-700',
  FAILED:  'bg-red-100 text-red-700',
};

export default function ExecutionMonitor({ keycloak }) {
  const navigate   = useNavigate();
  const [data, setData]     = useState(null);
  const [loading, setLoading] = useState(true);
  const [status, setStatus] = useState('');

  async function load() {
    try {
      const d = await listExecutions(keycloak, { size: 50, status: status || undefined });
      setData(d);
    } finally { setLoading(false); }
  }

  useEffect(() => {
    setLoading(true);
    load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [keycloak, status]);

  const rows = data?.content ?? [];
  const running = rows.filter(r => r.status === 'RUNNING').length;

  return (
    <Page
      title="Execution Monitor"
      subtitle={running > 0 ? `${running} executions running` : 'Live execution feed'}
      action={
        <Button variant="secondary" size="sm" onClick={load}>
          <RefreshCw size={13}/> Refresh
        </Button>
      }
    >
      <Card>
        <div className="px-5 py-3 border-b border-slate-100 flex items-center gap-3">
          {['', 'RUNNING', 'SUCCESS', 'FAILED'].map(s => (
            <button key={s}
              onClick={() => setStatus(s)}
              className={`px-2.5 py-1 rounded-full text-xs font-medium transition-colors ${
                status === s ? 'bg-blue-600 text-white' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
              }`}>
              {s || 'All'}
            </button>
          ))}
          {loading && <Spinner className="ml-auto w-4 h-4"/>}
          <div className="ml-auto flex items-center gap-1.5 text-xs text-blue-600">
            <span className="w-1.5 h-1.5 rounded-full bg-blue-600 animate-pulse"/>
            Live
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100">
                {['Execution ID', 'Intent ID', 'Adapter', 'Status', 'Attempt', 'Latency', 'Cost', 'Time'].map(h => (
                  <th key={h} className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {loading && !data ? (
                <tr><td colSpan={8} className="py-16 text-center"><Spinner className="mx-auto"/></td></tr>
              ) : rows.length === 0 ? (
                <tr><td colSpan={8}>
                  <EmptyState icon={<Cpu size={22}/>} title="No executions found" description="Executions appear here as intents are processed"/>
                </td></tr>
              ) : rows.map(ex => (
                <tr key={ex.executionId}
                  className="border-b border-slate-50 hover:bg-slate-50 cursor-pointer transition-colors"
                  onClick={() => navigate(`/intents/${ex.intentId}`)}>
                  <td className="px-5 py-3">
                    <span className="font-mono text-xs bg-slate-100 px-1.5 py-0.5 rounded"
                      style={{ fontFamily: "'JetBrains Mono', monospace" }}>
                      {shortId(ex.executionId)}
                    </span>
                  </td>
                  <td className="px-5 py-3">
                    <span className="font-mono text-xs text-slate-500"
                      style={{ fontFamily: "'JetBrains Mono', monospace" }}>
                      {shortId(ex.intentId)}
                    </span>
                  </td>
                  <td className="px-5 py-3 text-xs text-slate-600">{shortId(ex.adapterId)}</td>
                  <td className="px-5 py-3">
                    <span className={cn('px-2 py-0.5 rounded-full text-xs font-medium', STATUS_COLORS[ex.status] ?? 'bg-gray-100 text-gray-600')}>
                      {ex.status}
                    </span>
                  </td>
                  <td className="px-5 py-3 text-xs text-slate-500">#{ex.attemptNumber}</td>
                  <td className="px-5 py-3 text-xs tabular-nums text-slate-700">{formatLatency(ex.latencyMs)}</td>
                  <td className="px-5 py-3 text-xs tabular-nums text-slate-700">{formatCost(ex.cost)}</td>
                  <td className="px-5 py-3 text-xs text-slate-400" title={formatDate(ex.timestamp)}>
                    {formatRelative(ex.timestamp)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </Page>
  );
}
