/**
 * AdminFeedback.jsx — Feedback Dashboard (sys_admin only)
 *
 * Displays all user feedback with:
 *   - Summary stats (avg rating, total, breakdown by category)
 *   - Filters by category, minimum rating, date range
 *   - Paginated feedback list
 *   - Highlight low-rating entries for triage
 *
 * Wrap with SysAdminRoute in your router:
 *   <Route path="/admin/feedback" element={
 *     <SysAdminRoute keycloak={keycloak}>
 *       <AdminFeedback keycloak={keycloak} />
 *     </SysAdminRoute>
 *   } />
 */
import { useState, useEffect, useCallback } from 'react';
import {
  Star, Filter, RefreshCw, AlertCircle, MessageSquare,
  TrendingUp, Users, ThumbsUp, Bug, Zap, CreditCard, Info,
} from 'lucide-react';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Spinner } from '../components/shared';
import { request } from '../utils/api';

// ── Constants ─────────────────────────────────────────────────────────────────
const CATEGORIES = [
  { id: 'all',     label: 'All',            icon: <MessageSquare size={11} />, color: '#64748b' },
  { id: 'bug',     label: 'Bug reports',    icon: <Bug size={11} />,           color: '#dc2626' },
  { id: 'feature', label: 'Feature reqs',  icon: <Zap size={11} />,           color: '#7c3aed' },
  { id: 'billing', label: 'Billing',        icon: <CreditCard size={11} />,    color: '#2563eb' },
  { id: 'general', label: 'General',        icon: <Info size={11} />,          color: '#0d9488' },
];

const CATEGORY_META = {
  bug:     { label: '🐛 Bug report',     color: '#dc2626', bg: '#fef2f2' },
  feature: { label: '✨ Feature request', color: '#7c3aed', bg: '#f5f3ff' },
  billing: { label: '💳 Billing',         color: '#2563eb', bg: '#eff6ff' },
  general: { label: '💬 General',         color: '#0d9488', bg: '#f0fdfa' },
};

// ── Star display ──────────────────────────────────────────────────────────────
function Stars({ value, size = 12 }) {
  return (
    <div className="flex gap-0.5">
      {[1, 2, 3, 4, 5].map(s => (
        <Star
          key={s}
          size={size}
          style={{
            fill:   value >= s ? '#f59e0b' : 'transparent',
            stroke: value >= s ? '#f59e0b' : '#cbd5e1',
          }}
        />
      ))}
    </div>
  );
}

// ── Stat card ─────────────────────────────────────────────────────────────────
function StatCard({ label, value, sub, icon, color }) {
  return (
    <Card className="p-5">
      <div className="flex items-start justify-between mb-3">
        <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide">{label}</p>
        <div className="p-2 rounded-lg" style={{ background: color + '15', color }}>
          {icon}
        </div>
      </div>
      <p className="text-2xl font-extrabold text-slate-900">{value}</p>
      {sub && <p className="text-[11px] text-slate-400 mt-0.5">{sub}</p>}
    </Card>
  );
}

// ── Main component ────────────────────────────────────────────────────────────
export default function AdminFeedback({ keycloak }) {
  const [feedback,    setFeedback]    = useState([]);
  const [loading,     setLoading]     = useState(true);
  const [refreshing,  setRefreshing]  = useState(false);
  const [category,    setCategory]    = useState('all');
  const [minRating,   setMinRating]   = useState(1);
  const [hoveredStar, setHoveredStar] = useState(0);
  const [page,        setPage]        = useState(0);
  const PAGE_SIZE = 20;

  const load = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true); else setLoading(true);
    try {
      const params = new URLSearchParams({ limit: '200' });
      if (category !== 'all') params.set('category', category);
      if (minRating > 1)      params.set('minRating', minRating);

      const data = await request(keycloak, `/admin/feedback?${params}`);
      setFeedback(data ?? []);
      setPage(0);
    } catch (e) {
      console.error('Failed to load feedback', e);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [keycloak, category, minRating]);

  useEffect(() => { load(); }, [load]);

  // ── Derived stats ─────────────────────────────────────────────────────────
  const total      = feedback.length;
  const avgRating  = total > 0
    ? (feedback.reduce((s, f) => s + f.rating, 0) / total).toFixed(1)
    : '—';
  const lowRatings = feedback.filter(f => f.rating <= 2).length;
  const withComment = feedback.filter(f => f.comment?.trim()).length;

  const catCounts = CATEGORIES.slice(1).reduce((acc, c) => {
    acc[c.id] = feedback.filter(f => f.category === c.id).length;
    return acc;
  }, {});

  // ── Pagination ────────────────────────────────────────────────────────────
  const paginated   = feedback.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);
  const totalPages  = Math.ceil(total / PAGE_SIZE);

  return (
    <Page
      title="User feedback"
      subtitle="All feedback submitted by authenticated users via the feedback widget"
    >
      {/* ── Stats row ──────────────────────────────────────────────────────── */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard
          label="Total responses"
          value={total}
          sub="All time"
          icon={<Users size={14} />}
          color="#2563eb"
        />
        <StatCard
          label="Avg rating"
          value={avgRating}
          sub="Out of 5.0"
          icon={<Star size={14} />}
          color="#f59e0b"
        />
        <StatCard
          label="Low ratings"
          value={lowRatings}
          sub="1–2 stars — need triage"
          icon={<AlertCircle size={14} />}
          color="#dc2626"
        />
        <StatCard
          label="With comments"
          value={withComment}
          sub={`${total > 0 ? Math.round((withComment / total) * 100) : 0}% left comments`}
          icon={<ThumbsUp size={14} />}
          color="#16a34a"
        />
      </div>

      {/* ── Category breakdown ─────────────────────────────────────────────── */}
      <Card className="p-5">
        <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-4">
          By category
        </p>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          {CATEGORIES.slice(1).map(cat => {
            const count = catCounts[cat.id] ?? 0;
            const pct   = total > 0 ? Math.round((count / total) * 100) : 0;
            const meta  = CATEGORY_META[cat.id];
            return (
              <div key={cat.id}
                className="rounded-xl p-3 border"
                style={{ borderColor: meta.color + '33', backgroundColor: meta.bg }}>
                <div className="flex items-center gap-1.5 mb-2" style={{ color: meta.color }}>
                  {cat.icon}
                  <span className="text-[11px] font-semibold">{cat.label}</span>
                </div>
                <p className="text-xl font-extrabold" style={{ color: meta.color }}>{count}</p>
                <p className="text-[10px] text-slate-400">{pct}% of total</p>
              </div>
            );
          })}
        </div>
      </Card>

      {/* ── Filters ────────────────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-1.5 text-xs text-slate-500">
          <Filter size={12} />
          <span className="font-medium">Filter:</span>
        </div>

        {/* Category filter */}
        <div className="flex rounded-lg border border-slate-200 overflow-hidden text-xs">
          {CATEGORIES.map(cat => (
            <button key={cat.id}
              onClick={() => setCategory(cat.id)}
              className={`px-3 py-1.5 font-medium transition-colors flex items-center gap-1 ${
                category === cat.id
                  ? 'bg-slate-900 text-white'
                  : 'bg-white text-slate-600 hover:bg-slate-50'
              }`}>
              {cat.icon}
              {cat.label}
              {cat.id !== 'all' && (
                <span className={`text-[10px] px-1 rounded-full ${
                  category === cat.id ? 'bg-white/20 text-white' : 'bg-slate-100 text-slate-500'
                }`}>
                  {catCounts[cat.id] ?? 0}
                </span>
              )}
            </button>
          ))}
        </div>

        {/* Min rating filter */}
        <div className="flex items-center gap-2 text-xs text-slate-500">
          <span>Min rating:</span>
          <div className="flex gap-0.5">
            {[1, 2, 3, 4, 5].map(s => (
              <button
                key={s}
                onClick={() => setMinRating(s)}
                onMouseEnter={() => setHoveredStar(s)}
                onMouseLeave={() => setHoveredStar(0)}
                className="p-0.5 transition-transform hover:scale-110"
              >
                <Star
                  size={16}
                  style={{
                    fill:   (hoveredStar || minRating) >= s ? '#f59e0b' : 'transparent',
                    stroke: (hoveredStar || minRating) >= s ? '#f59e0b' : '#cbd5e1',
                  }}
                />
              </button>
            ))}
          </div>
          {minRating > 1 && (
            <button
              onClick={() => setMinRating(1)}
              className="text-[10px] text-slate-400 hover:text-slate-600 underline"
            >
              clear
            </button>
          )}
        </div>

        <button
          onClick={() => load(true)}
          disabled={refreshing}
          className="ml-auto flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-slate-600 border border-slate-200 rounded-lg hover:bg-slate-50 disabled:opacity-50 transition-colors"
        >
          <RefreshCw size={11} className={refreshing ? 'animate-spin' : ''} />
          Refresh
        </button>
      </div>

      {/* ── Feedback list ───────────────────────────────────────────────────── */}
      {loading ? (
        <div className="flex justify-center py-16"><Spinner className="w-7 h-7" /></div>
      ) : feedback.length === 0 ? (
        <Card className="p-12 text-center">
          <MessageSquare size={24} className="text-slate-300 mx-auto mb-3" />
          <p className="text-sm text-slate-500">No feedback found for the selected filters.</p>
        </Card>
      ) : (
        <>
          <div className="space-y-3">
            {paginated.map(item => {
              const meta    = CATEGORY_META[item.category] ?? CATEGORY_META.general;
              const isLow   = item.rating <= 2;
              const hasNote = item.comment?.trim();

              return (
                <Card key={item.id}
                  className={`p-4 ${isLow ? 'border-red-200 bg-red-50/30' : ''}`}>
                  <div className="flex items-start justify-between gap-3">

                    {/* Left — rating + meta */}
                    <div className="flex items-start gap-3 flex-1 min-w-0">
                      {/* Rating badge */}
                      <div className={`shrink-0 w-9 h-9 rounded-xl flex items-center justify-center font-extrabold text-sm ${
                        item.rating >= 4 ? 'bg-emerald-50 text-emerald-700' :
                        item.rating === 3 ? 'bg-amber-50 text-amber-700'   :
                        'bg-red-50 text-red-700'
                      }`}>
                        {item.rating}
                      </div>

                      <div className="flex-1 min-w-0">
                        {/* Stars + category */}
                        <div className="flex items-center gap-2 mb-1 flex-wrap">
                          <Stars value={item.rating} />
                          <span
                            className="text-[10px] font-semibold px-2 py-0.5 rounded-full"
                            style={{ color: meta.color, background: meta.bg }}>
                            {meta.label}
                          </span>
                          {isLow && (
                            <span className="text-[10px] font-bold text-red-600 bg-red-100 px-2 py-0.5 rounded-full flex items-center gap-1">
                              <AlertCircle size={9} /> Needs attention
                            </span>
                          )}
                        </div>

                        {/* Comment */}
                        {hasNote ? (
                          <p className="text-xs text-slate-700 leading-relaxed">
                            "{item.comment}"
                          </p>
                        ) : (
                          <p className="text-xs text-slate-400 italic">No comment left</p>
                        )}

                        {/* Page + user */}
                        <div className="flex items-center gap-3 mt-2 text-[10px] text-slate-400">
                          {item.page && <span>📍 {item.page}</span>}
                          {item.userEmail && <span>👤 {item.userEmail}</span>}
                        </div>
                      </div>
                    </div>

                    {/* Right — timestamp */}
                    <div className="text-[10px] text-slate-400 shrink-0 text-right">
                      <p>{new Date(item.createdAt).toLocaleDateString()}</p>
                      <p>{new Date(item.createdAt).toLocaleTimeString()}</p>
                    </div>
                  </div>
                </Card>
              );
            })}
          </div>

          {/* Pagination */}
          {totalPages > 1 && (
            <div className="flex items-center justify-center gap-2">
              <button
                onClick={() => setPage(p => Math.max(0, p - 1))}
                disabled={page === 0}
                className="px-3 py-1.5 text-xs font-medium border border-slate-200 rounded-lg disabled:opacity-40 hover:bg-slate-50 transition-colors"
              >
                ← Prev
              </button>
              <span className="text-xs text-slate-500">
                Page {page + 1} of {totalPages} · {total} total
              </span>
              <button
                onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                disabled={page >= totalPages - 1}
                className="px-3 py-1.5 text-xs font-medium border border-slate-200 rounded-lg disabled:opacity-40 hover:bg-slate-50 transition-colors"
              >
                Next →
              </button>
            </div>
          )}
        </>
      )}
    </Page>
  );
}
