import { clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';
import { format, formatDistanceToNow } from 'date-fns';

export function cn(...inputs) { return twMerge(clsx(inputs)); }

export function formatDate(iso) {
  try { return format(new Date(iso), 'MMM d, yyyy HH:mm:ss'); } catch { return iso; }
}

export function formatRelative(iso) {
  try { return formatDistanceToNow(new Date(iso), { addSuffix: true }); } catch { return iso; }
}

export function formatCost(usd) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency', currency: 'USD',
    minimumFractionDigits: 4, maximumFractionDigits: 6,
  }).format(usd ?? 0);
}

export function formatLatency(ms) {
  if (!ms) return '—';
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : `${ms}ms`;
}

export function shortId(uuid) {
  return uuid ? uuid.split('-')[0] : '—';
}

// Fix: added FAILED, CANCELLED, REJECTED so terminal failure states render
// with explicit colours rather than falling back to the generic grey default.
export const PHASE_COLORS = {
  CREATED:         'bg-slate-100 text-slate-700',
  PLANNING:        'bg-blue-100 text-blue-700',
  PLANNED:         'bg-indigo-100 text-indigo-700',
  EXECUTING:       'bg-amber-100 text-amber-700',
  RETRY_SCHEDULED: 'bg-orange-100 text-orange-700',
  EVALUATING:      'bg-purple-100 text-purple-700',
  COMPLETED:       'bg-green-100 text-green-700',
  FAILED:          'bg-red-100 text-red-700',
  CANCELLED:       'bg-slate-100 text-slate-500',
  REJECTED:        'bg-red-50 text-red-600',
};

export const SATISFACTION_COLORS = {
  SATISFIED: 'bg-green-100 text-green-700',
  VIOLATED:  'bg-red-100 text-red-700',
  UNKNOWN:   'bg-gray-100 text-gray-600',
};

export const PHASE_ORDER = [
  'CREATED', 'PLANNING', 'PLANNED',
  'EXECUTING', 'EVALUATING', 'COMPLETED',
];
