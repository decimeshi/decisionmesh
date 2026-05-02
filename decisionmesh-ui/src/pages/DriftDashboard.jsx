import { useState, useEffect } from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { TrendingUp, AlertTriangle } from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Spinner } from '../components/shared';
import { getDriftData } from '../utils/api';
import { formatCost, formatLatency } from '../lib/utils';

function DriftBar({ value }) {
  const pct   = Math.min(100, (value ?? 0) * 100);
  const color = pct > 70 ? 'bg-red-500' : pct > 40 ? 'bg-amber-500' : 'bg-green-500';
  return (
    <div className="flex items-center gap-2">
      <div className="flex-1 h-1.5 bg-slate-100 rounded-full overflow-hidden">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${pct}%` }} />
      </div>
      <span className="text-xs tabular-nums text-slate-600 w-10 text-right">
        {(value ?? 0).toFixed(3)}
      </span>
    </div>
  );
}

export default function DriftDashboard({ keycloak }) {
  const [summary,      setSummary]      = useState([]);
  const [trend,        setTrend]        = useState([]);
  const [overallDrift, setOverallDrift] = useState(0);
  const [loading,      setLoading]      = useState(true);

  useEffect(() => {
    getDriftData(keycloak)
      .then(data => {
        if (!data) return;

        // ── Handle new API response shape ────────────────────────────────────
        // API returns: { overallDrift, lookbackDays, summary: [...], trend: [...] }
        // Old shape was a flat array — guard both so it works during transition
        if (Array.isArray(data)) {
          // Old flat array shape — map to new shape
          setSummary(data);
        } else {
          setSummary(data.summary  ?? []);
          setTrend(data.trend      ?? []);
          setOverallDrift(data.overallDrift ?? 0);
        }
      })
      .catch(err => console.error('[DriftDashboard] load failed:', err?.message))
      .finally(() => setLoading(false));
  }, [keycloak]);

  // Format trend dates for chart x-axis
  const formattedTrend = trend.map(p => ({
    ...p,
    date: p.date
      ? new Date(p.date).toLocaleDateString('en-GB', { month: 'short', day: 'numeric' })
      : '',
  }));

  const highDrift = summary.filter(a => (a.avgDriftScore ?? a.driftScore ?? 0) > 0.7);

  if (loading) return (
    <Page title="Drift Dashboard">
      <div className="flex justify-center py-24">
        <Spinner className="w-8 h-8" />
      </div>
    </Page>
  );

  return (
    <Page title="Drift Dashboard" subtitle="Monitor adapter performance and drift">

      {/* Overall drift score */}
      {overallDrift > 0 && (
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mb-2">
          <Card>
            <CardContent className="pt-4">
              <p className="text-xs font-medium text-slate-500 uppercase tracking-wider mb-1">
                Overall Drift
              </p>
              <div className="flex items-end gap-2">
                <span className="text-2xl font-bold text-slate-800">
                  {overallDrift.toFixed(3)}
                </span>
                <span className="text-xs text-slate-400 mb-1">/ 1.0</span>
              </div>
              <DriftBar value={overallDrift} />
            </CardContent>
          </Card>
          <Card>
            <CardContent className="pt-4">
              <p className="text-xs font-medium text-slate-500 uppercase tracking-wider mb-1">
                Adapters Monitored
              </p>
              <span className="text-2xl font-bold text-slate-800">{summary.length}</span>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="pt-4">
              <p className="text-xs font-medium text-slate-500 uppercase tracking-wider mb-1">
                High Drift Adapters
              </p>
              <span className={`text-2xl font-bold ${highDrift.length > 0 ? 'text-red-600' : 'text-green-600'}`}>
                {highDrift.length}
              </span>
            </CardContent>
          </Card>
        </div>
      )}

      {/* High drift alert */}
      {highDrift.length > 0 && (
        <div className="p-4 bg-amber-50 border border-amber-200 rounded-xl flex items-start gap-3">
          <AlertTriangle size={16} className="text-amber-600 shrink-0 mt-0.5" />
          <div>
            <p className="text-sm font-medium text-amber-800">High drift detected</p>
            <p className="text-xs text-amber-700 mt-0.5">
              {highDrift.map(a => a.adapterName).join(', ')} {highDrift.length === 1 ? 'has' : 'have'} drift score above 0.7
            </p>
          </div>
        </div>
      )}

      {/* Adapter comparison table */}
      <Card>
        <CardHeader><CardTitle>Adapter comparison</CardTitle></CardHeader>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100">
                {['Adapter', 'Drift score', 'Failure rate', 'Avg cost', 'Avg latency', 'Executions'].map(h => (
                  <th key={h} className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide whitespace-nowrap">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {summary.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-5 py-12 text-center text-sm text-slate-400">
                    No drift data available — submit some intents first
                  </td>
                </tr>
              )}
              {summary.map(a => {
                // Handle both old (driftScore) and new (avgDriftScore) field names
                const drift       = a.avgDriftScore  ?? a.driftScore  ?? 0;
                const failureRate = a.failureRate     ?? 0;
                const avgCost     = a.avgCostUsd      ?? a.avgCost     ?? 0;
                const avgLatency  = a.avgLatencyMs    ?? a.avgLatency   ?? 0;
                const count       = a.executionCount  ?? 0;

                return (
                  <tr key={a.adapterId} className="border-b border-slate-50 hover:bg-slate-50 transition-colors">
                    <td className="px-5 py-4">
                      <p className="text-sm font-medium text-slate-800">{a.adapterName ?? '—'}</p>
                      <p className="text-xs text-slate-400 font-mono">
                        {a.adapterId?.split('-')[0]}
                      </p>
                    </td>
                    <td className="px-5 py-4 w-40">
                      <DriftBar value={drift} />
                    </td>
                    <td className="px-5 py-4 text-xs tabular-nums text-slate-700">
                      {(failureRate * 100).toFixed(1)}%
                    </td>
                    <td className="px-5 py-4 text-xs tabular-nums text-slate-700">
                      {formatCost(avgCost)}
                    </td>
                    <td className="px-5 py-4 text-xs tabular-nums text-slate-700">
                      {formatLatency(avgLatency)}
                    </td>
                    <td className="px-5 py-4 text-xs tabular-nums text-slate-700">
                      {count.toLocaleString()}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Card>

      {/* Trend chart — overall daily trend */}
      {formattedTrend.length > 0 && (
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <TrendingUp size={13} className="text-slate-400" />
              <CardTitle>Daily execution trend</CardTitle>
            </div>
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={200}>
              <LineChart data={formattedTrend}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
                <XAxis
                  dataKey="date"
                  tick={{ fontSize: 11, fill: '#94a3b8' }}
                  axisLine={false}
                  tickLine={false}
                />
                <YAxis
                  tick={{ fontSize: 11, fill: '#94a3b8' }}
                  axisLine={false}
                  tickLine={false}
                />
                <Tooltip
                  contentStyle={{
                    fontSize: 12,
                    borderRadius: 8,
                    border: '1px solid #e2e8f0',
                  }}
                />
                <Line
                  type="monotone"
                  dataKey="avgCost"
                  name="Avg cost ($)"
                  stroke="var(--brand-primary)"
                  strokeWidth={2}
                  dot={false}
                />
                <Line
                  type="monotone"
                  dataKey="executionCount"
                  name="Executions"
                  stroke="#94a3b8"
                  strokeWidth={1.5}
                  dot={false}
                  strokeDasharray="4 2"
                />
              </LineChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>
      )}

      {/* Empty state */}
      {summary.length === 0 && trend.length === 0 && !loading && (
        <Card>
          <CardContent className="py-16 text-center">
            <TrendingUp size={32} className="text-slate-200 mx-auto mb-3" />
            <p className="text-sm font-medium text-slate-500">No drift data yet</p>
            <p className="text-xs text-slate-400 mt-1">
              Drift data appears after adapters process intents
            </p>
          </CardContent>
        </Card>
      )}
    </Page>
  );
}
