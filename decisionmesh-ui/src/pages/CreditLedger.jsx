import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Zap, ArrowDownCircle, ArrowUpCircle, Gift, Users,
  Download, Filter, RefreshCw, ShoppingCart, RotateCcw,
} from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Button, Spinner } from '../components/shared';
import { useCredits, MODEL_TIERS } from '../context/CreditContext';
import { formatDate, formatRelative, shortId } from '../lib/utils';

const API = 'http://localhost:8080/api';

async function req(keycloak, path, opts = {}) {
  if (keycloak?.token) await keycloak.updateToken(30).catch(() => {});
  const res = await fetch(`${API}${path}`, {
    ...opts,
    headers: { Authorization: `Bearer ${keycloak?.token}`, ...(opts.headers ?? {}) },
  });
  if (!res.ok) return null;
  return res.json().catch(() => null);
}

// ── Reason metadata ───────────────────────────────────────────────────────────
const REASON_META = {
  REGISTRATION_GIFT:    { label: 'Registration gift',    icon: Gift,          color: '#16a34a', sign: '+', bg: '#f0fdf4' },
  SUBSCRIPTION:         { label: 'Monthly allocation',   icon: RefreshCw,     color: '#2563eb', sign: '+', bg: '#eff6ff' },
  PURCHASE:             { label: 'Credit pack purchase', icon: ShoppingCart,  color: '#4f46e5', sign: '+', bg: '#f5f3ff' },
  REFERRAL:             { label: 'Referral bonus',       icon: Users,         color: '#0d9488', sign: '+', bg: '#f0fdfa' },
  INTENT_EXECUTION:     { label: 'Intent execution',     icon: Zap,           color: '#64748b', sign: '−', bg: '#f8fafc' },
  RETRY:                { label: 'Retry attempt',        icon: RotateCcw,     color: '#d97706', sign: '−', bg: '#fffbeb' },
  REFUND:               { label: 'Execution refund',     icon: ArrowUpCircle, color: '#16a34a', sign: '+', bg: '#f0fdf4' },
  ADMIN_ADJUSTMENT:     { label: 'Admin adjustment',     icon: ArrowDownCircle, color: '#7c3aed', sign: '+', bg: '#f5f3ff' },
};

const FILTER_OPTIONS = [
  { value: 'all',     label: 'All transactions' },
  { value: 'credits', label: 'Credits in (+)' },
  { value: 'debits',  label: 'Credits out (−)' },
];



// ── CSV export ────────────────────────────────────────────────────────────────
function exportCsv(transactions) {
  const header = 'Date,Type,Amount,Reference\n';
  const rows = transactions.map(t => {
    const meta = REASON_META[t.reason] ?? { label: t.reason, sign: t.amount > 0 ? '+' : '−' };
    return [
      formatDate(t.createdAt),
      meta.label,
      `${meta.sign}${Math.abs(t.amount)}`,
      t.referenceId ?? '',
    ].join(',');
  });
  const blob = new Blob([header + rows.join('\n')], { type: 'text/csv' });
  const url  = URL.createObjectURL(blob);
  const a    = document.createElement('a'); a.href = url; a.download = 'credit-ledger.csv'; a.click();
  URL.revokeObjectURL(url);
}

// ── Summary stats ─────────────────────────────────────────────────────────────
function LedgerStats({ transactions }) {
  const earned  = transactions.filter(t => t.amount > 0).reduce((s, t) => s + t.amount, 0);
  const spent   = transactions.filter(t => t.amount < 0).reduce((s, t) => s + Math.abs(t.amount), 0);
  const executions = transactions.filter(t => t.reason === 'INTENT_EXECUTION').length;
  const purchased  = transactions.filter(t => t.reason === 'PURCHASE').reduce((s, t) => s + t.amount, 0);

  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
      {[
        { label: 'Total earned',    value: `+${earned.toLocaleString()}`,  color: '#16a34a', sub: 'credits received' },
        { label: 'Total spent',     value: `−${spent.toLocaleString()}`,   color: '#dc2626', sub: 'credits used' },
        { label: 'Intents run',     value: executions,                     color: '#2563eb', sub: 'executions' },
        { label: 'Credits purchased', value: purchased.toLocaleString(),   color: '#4f46e5', sub: 'via credit packs' },
      ].map(({ label, value, color, sub }) => (
        <Card key={label} className="p-4">
          <p className="text-xs font-medium text-slate-500 mb-1">{label}</p>
          <p className="text-2xl font-bold" style={{ color }}>{value}</p>
          <p className="text-xs text-slate-400 mt-0.5">{sub}</p>
        </Card>
      ))}
    </div>
  );
}

// ── Transaction row ───────────────────────────────────────────────────────────
function TxRow({ tx, onIntentClick }) {
  const meta = REASON_META[tx.reason] ?? {
    label: tx.reason, icon: Zap, color: '#64748b',
    sign: tx.amount > 0 ? '+' : '−', bg: '#f8fafc',
  };
  const Icon  = meta.icon;
  const isDebit = tx.amount < 0;

  // Infer tier from amount for execution rows
  const tierLabel = tx.reason === 'INTENT_EXECUTION' || tx.reason === 'RETRY'
    ? Object.values(MODEL_TIERS).find(t => t.credits === Math.abs(tx.amount))?.label ?? 'Custom'
    : null;

  return (
    <tr className="border-b border-slate-50 hover:bg-slate-50 transition-colors group">
      <td className="px-5 py-3">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 rounded-full flex items-center justify-center shrink-0"
            style={{ backgroundColor: meta.bg }}>
            <Icon size={14} style={{ color: meta.color }} />
          </div>
          <div>
            <p className="text-sm font-medium text-slate-700">{meta.label}</p>
            {tierLabel && (
              <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded-full"
                style={{ backgroundColor: meta.bg, color: meta.color }}>
                {tierLabel} tier
              </span>
            )}
          </div>
        </div>
      </td>

      <td className="px-5 py-3">
        <span className={`text-sm font-bold ${isDebit ? 'text-slate-600' : 'text-green-600'}`}>
          {meta.sign}{Math.abs(tx.amount).toLocaleString()}
          <span className="text-xs font-normal text-slate-400 ml-1">cr</span>
        </span>
      </td>

      <td className="px-5 py-3 text-xs text-slate-400" title={formatDate(tx.createdAt)}>
        {formatRelative(tx.createdAt)}
      </td>

      <td className="px-5 py-3">
        {tx.referenceId && (
          <button
            onClick={() => onIntentClick(tx.referenceId)}
            className="text-xs font-mono text-blue-500 hover:text-blue-700 hover:underline transition-colors"
            style={{ fontFamily: "'JetBrains Mono', monospace" }}
          >
            {tx.referenceId.length === 36 ? shortId(tx.referenceId) : tx.referenceId.slice(0, 18) + '…'}
          </button>
        )}
      </td>
    </tr>
  );
}

// ── Main page ─────────────────────────────────────────────────────────────────
export default function CreditLedger({ keycloak }) {
  const navigate = useNavigate();
  const { balance, allocated, statusColor, reload } = useCredits();

  const [transactions, setTransactions] = useState([]);
  const [loading,      setLoading]      = useState(true);
  const [filter,       setFilter]       = useState('all');
  const [page,         setPage]         = useState(0);
  const PAGE_SIZE = 20;

  useEffect(() => {
    let active = true;
    async function load() {
      setLoading(true);
      try {
        const data = await req(keycloak, `/credits/ledger?page=${page}&size=${PAGE_SIZE}`);
        if (active) setTransactions(data?.content ?? data ?? []);
      } catch {
        if (active) setTransactions([]);
      } finally {
        if (active) setLoading(false);
      }
    }
    load();
    return () => { active = false; };
  }, [page, keycloak]);

  const filtered = transactions.filter(t => {
    if (filter === 'credits') return t.amount > 0;
    if (filter === 'debits')  return t.amount < 0;
    return true;
  });

  function handleIntentClick(refId) {
    if (refId?.length === 36) navigate(`/intents/${refId}`);
  }

  return (
    <Page
      title="Credit ledger"
      subtitle="Complete history of every credit earned, purchased, and spent"
      action={
        <div className="flex items-center gap-2">
          <Button variant="secondary" size="sm" onClick={() => exportCsv(filtered)}>
            <Download size={13} /> Export CSV
          </Button>
          <Button variant="secondary" size="sm" onClick={() => navigate('/billing?tab=credits')}>
            <ShoppingCart size={13} /> Buy credits
          </Button>
        </div>
      }
    >
      {/* Balance summary banner */}
      <Card className="border-2 p-5" style={{ borderColor: statusColor + '33' }}>
        <div className="flex flex-wrap items-center gap-6">
          <div>
            <p className="text-xs font-semibold text-slate-500 mb-1">Current balance</p>
            <div className="flex items-end gap-2">
              <p className="text-4xl font-bold" style={{ color: statusColor }}>
                {balance?.toLocaleString() ?? '—'}
              </p>
              <p className="text-slate-400 text-sm mb-1">
                / {allocated?.toLocaleString()} monthly
              </p>
            </div>
          </div>

          <div className="flex-1 min-w-48">
            <div className="flex justify-between text-xs text-slate-500 mb-1.5">
              <span>Usage this period</span>
              <span style={{ color: statusColor }}>
                {allocated ? Math.round(((allocated - balance) / allocated) * 100) : 0}% used
              </span>
            </div>
            <div className="h-3 bg-slate-100 rounded-full overflow-hidden">
              <div className="h-full rounded-full transition-all"
                style={{
                  width: `${allocated ? Math.min(100, ((allocated - balance) / allocated) * 100) : 0}%`,
                  backgroundColor: statusColor,
                }} />
            </div>
          </div>

          {/* Model tier credit guide */}
          <div className="flex items-center gap-3">
            {Object.entries(MODEL_TIERS).map(([key, tier]) => (
              <div key={key} className="text-center px-3 py-2 rounded-lg"
                style={{ backgroundColor: tier.bg }}>
                <p className="text-xs font-bold" style={{ color: tier.color }}>{tier.credits} cr</p>
                <p className="text-[10px] text-slate-500">{tier.label}</p>
              </div>
            ))}
          </div>
        </div>
      </Card>

      {/* Stats */}
      {!loading && <LedgerStats transactions={transactions} />}

      {/* Filter + table */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between flex-wrap gap-3">
            <CardTitle>Transactions</CardTitle>
            <div className="flex items-center gap-2">
              <Filter size={13} className="text-slate-400" />
              <div className="flex gap-1">
                {FILTER_OPTIONS.map(({ value, label }) => (
                  <button key={value} onClick={() => setFilter(value)}
                    className={`px-3 py-1 rounded-lg text-xs font-medium border transition-colors ${
                      filter === value
                        ? 'bg-blue-600 text-white border-blue-600'
                        : 'bg-white text-slate-600 border-slate-200 hover:border-blue-300'
                    }`}>
                    {label}
                  </button>
                ))}
              </div>
            </div>
          </div>
        </CardHeader>

        {loading ? (
          <div className="flex justify-center py-16"><Spinner className="w-8 h-8" /></div>
        ) : filtered.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 text-slate-400">
            <Zap size={24} className="mb-3 opacity-20" />
            <p className="text-sm">No transactions found</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100">
                  {['Type', 'Amount', 'When', 'Reference'].map(h => (
                    <th key={h} className="px-5 py-3 text-left text-xs font-medium text-slate-500 uppercase tracking-wide">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {filtered.map(tx => (
                  <TxRow key={tx.id} tx={tx} onIntentClick={handleIntentClick} />
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination */}
        {filtered.length === PAGE_SIZE && (
          <div className="px-5 py-3 border-t border-slate-100 flex justify-between items-center">
            <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>
              ← Previous
            </Button>
            <span className="text-xs text-slate-400">Page {page + 1}</span>
            <Button variant="secondary" size="sm" onClick={() => setPage(p => p + 1)}>
              Next →
            </Button>
          </div>
        )}
      </Card>
    </Page>
  );
}
