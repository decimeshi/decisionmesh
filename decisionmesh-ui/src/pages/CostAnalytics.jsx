import { useState, useEffect } from 'react';
import { AreaChart, Area, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { DollarSign } from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, MetricCard, Spinner } from '../components/shared';
import { getCostAnalytics } from '../utils/api';
import { formatCost } from '../lib/utils';

function Tip({ active, payload, label, fmt }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="bg-white border border-slate-200 rounded-lg shadow-md p-3 text-xs">
      <p className="text-slate-400 mb-1">{label}</p>
      <p className="font-semibold text-slate-800">{fmt ? fmt(payload[0].value) : payload[0].value}</p>
    </div>
  );
}

export default function CostAnalytics({ keycloak }) {
  const [data, setData]     = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getCostAnalytics(keycloak)
      .then(d => setData(d))
      .finally(() => setLoading(false));
  }, [keycloak]);

  if (loading) return (
    <Page title="Cost Analytics"><div className="flex justify-center py-24"><Spinner className="w-8 h-8"/></div></Page>
  );

  return (
    <Page title="Cost Analytics" subtitle="Track spending across adapters and time">
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <MetricCard label="Total cost"     value={formatCost(data?.totalCostUsd ?? 0)} sub="All time" icon={<DollarSign size={15}/>}/>
        <MetricCard label="Avg per intent" value={formatCost(data?.avgCostPerIntent ?? 0)} sub="Mean cost"/>
        <MetricCard label="Adapters"       value={data?.costByAdapter?.length ?? 0} sub="With spend"/>
      </div>

      <Card>
        <CardHeader><CardTitle>Cost over time</CardTitle></CardHeader>
        <CardContent>
          <ResponsiveContainer width="100%" height={260}>
            <AreaChart data={data?.costOverTime ?? []}>
              <defs>
                <linearGradient id="cg2" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#2563EB" stopOpacity={0.12}/>
                  <stop offset="95%" stopColor="#2563EB" stopOpacity={0}/>
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9"/>
              <XAxis dataKey="date" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false}/>
              <YAxis tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} tickFormatter={v => `$${v.toFixed(3)}`}/>
              <Tooltip content={<Tip fmt={v => formatCost(v)}/>}/>
              <Area type="monotone" dataKey="totalCost" stroke="#2563EB" strokeWidth={2} fill="url(#cg2)"/>
            </AreaChart>
          </ResponsiveContainer>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>Cost by adapter</CardTitle></CardHeader>
        <CardContent>
          <ResponsiveContainer width="100%" height={260}>
            <BarChart data={data?.costByAdapter ?? []}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9"/>
              <XAxis dataKey="adapterName" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false}/>
              <YAxis tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} tickFormatter={v => `$${v.toFixed(2)}`}/>
              <Tooltip content={<Tip fmt={v => formatCost(v)}/>}/>
              <Bar dataKey="totalCost" fill="#2563EB" radius={[4, 4, 0, 0]}/>
            </BarChart>
          </ResponsiveContainer>
        </CardContent>
      </Card>
    </Page>
  );
}
