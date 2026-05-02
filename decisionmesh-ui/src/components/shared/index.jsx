import { cn, PHASE_COLORS, SATISFACTION_COLORS } from '../../lib/utils';
export { cn } from '../../lib/utils';

// ── Card ──────────────────────────────────────────────────────────────────────
export function Card({ children, className, ...props }) {
  return (
    <div className={cn('bg-white rounded-xl border border-slate-200 shadow-sm', className)} {...props}>
      {children}
    </div>
  );
}

export function CardHeader({ children, className }) {
  return <div className={cn('px-5 py-4 border-b border-slate-100', className)}>{children}</div>;
}

export function CardTitle({ children }) {
  return <h3 className="text-sm font-semibold text-slate-800">{children}</h3>;
}

export function CardContent({ children, className }) {
  return <div className={cn('px-5 py-4', className)}>{children}</div>;
}

// ── Button ────────────────────────────────────────────────────────────────────
// Fix: primary variant was 'bg-slate-200' (grey) — changed to 'bg-blue-600'
// so the call-to-action button is visually distinct from secondary actions.
export function Button({ children, variant = 'primary', size = 'md', loading, className, ...props }) {
  return (
    <button
      disabled={loading || props.disabled}
      className={cn(
        'inline-flex items-center justify-center gap-2 font-medium rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed',
        {
          primary:     'bg-blue-600 text-white hover:bg-blue-700',
          secondary:   'bg-white text-slate-600 border border-slate-200 hover:bg-slate-100 hover:text-slate-800',
          ghost:       'text-slate-500 hover:bg-slate-100 hover:text-slate-700',
          destructive: 'bg-red-600 text-white hover:bg-red-700',
        }[variant],
        { sm: 'text-xs px-3 py-1.5', md: 'text-sm px-4 py-2', lg: 'text-sm px-5 py-2.5' }[size],
        className
      )}
      {...props}
    >
      {loading && <span className="w-3.5 h-3.5 border-2 border-current border-t-transparent rounded-full animate-spin" />}
      {children}
    </button>
  );
}

// ── Badges ────────────────────────────────────────────────────────────────────
export function PhaseBadge({ phase }) {
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium', PHASE_COLORS[phase] ?? 'bg-gray-100 text-gray-600')}>
      {phase?.replace('_', ' ')}
    </span>
  );
}

export function SatisfactionBadge({ state }) {
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium', SATISFACTION_COLORS[state] ?? 'bg-gray-100 text-gray-600')}>
      {state}
    </span>
  );
}

// ── Metric card ───────────────────────────────────────────────────────────────
export function MetricCard({ label, value, sub, icon }) {
  return (
    <Card className="p-5">
      <div className="flex items-start justify-between">
        <div>
          <p className="text-xs text-slate-500 font-medium uppercase tracking-wide">{label}</p>
          <p className="mt-2 text-2xl font-semibold text-slate-900">{value}</p>
          {sub && <p className="mt-1 text-xs text-slate-400">{sub}</p>}
        </div>
        {icon && <div className="p-2.5 rounded-lg bg-blue-50 text-blue-600">{icon}</div>}
      </div>
    </Card>
  );
}

// ── Empty state ───────────────────────────────────────────────────────────────
export function EmptyState({ icon, title, description, action }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="p-4 rounded-full bg-slate-100 text-slate-400 mb-4">{icon}</div>
      <h3 className="text-sm font-medium text-slate-700">{title}</h3>
      {description && <p className="mt-1 text-sm text-slate-400 max-w-sm">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

// ── Spinner ───────────────────────────────────────────────────────────────────
export function Spinner({ className }) {
  return (
    <div className={cn('w-5 h-5 border-2 border-blue-600 border-t-transparent rounded-full animate-spin', className)} />
  );
}

// ── Toast ─────────────────────────────────────────────────────────────────────
import { useState, useEffect } from 'react';
import { CheckCircle, XCircle, X } from 'lucide-react';

export function Toast({ type, title, message, onDismiss }) {
  useEffect(() => {
    const t = setTimeout(onDismiss, 5000);
    return () => clearTimeout(t);
  }, [onDismiss]);

  return (
    <div className={cn(
      'flex items-start gap-3 p-3.5 rounded-xl border shadow-lg min-w-72 max-w-sm',
      type === 'success' ? 'bg-green-50 border-green-200' : 'bg-red-50 border-red-200'
    )}>
      {type === 'success'
        ? <CheckCircle size={16} className="text-green-600 shrink-0 mt-0.5" />
        : <XCircle size={16} className="text-red-500 shrink-0 mt-0.5" />}
      <div className="flex-1">
        <p className="text-sm font-medium text-slate-800">{title}</p>
        {message && <p className="text-xs text-slate-500 mt-0.5">{message}</p>}
      </div>
      <button onClick={onDismiss} className="text-slate-400 hover:text-slate-600">
        <X size={14} />
      </button>
    </div>
  );
}

export function Toaster({ toasts, dismiss }) {
  return (
    <div className="fixed bottom-4 right-4 flex flex-col gap-2 z-50">
      {toasts.map(t => <Toast key={t.id} {...t} onDismiss={() => dismiss(t.id)} />)}
    </div>
  );
}
