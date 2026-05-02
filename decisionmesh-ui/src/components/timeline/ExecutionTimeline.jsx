import { useState, useEffect, useRef } from 'react';
import { CheckCircle2, Circle, Loader2, XCircle } from 'lucide-react';
import { getIntentEvents } from '../../utils/api';
import { PHASE_ORDER, cn } from '../../lib/utils';
import { Spinner } from '../shared';

const TERMINAL_PHASES = new Set([
  'SATISFIED', 'FAILED', 'VIOLATED', 'COMPLETED', 'CANCELLED',
]);

const PHASE_MAP = {
  'PLANNING_STARTED':   'PLANNING',
  'EXECUTION_STARTED':  'EXECUTING',
  'EVALUATION_STARTED': 'EVALUATING',
  'DRIFT_UPDATED':      'COMPLETED',
  'SATISFIED':          'COMPLETED',
  'FAILED':             'COMPLETED',
  'VIOLATED':           'COMPLETED',
};

const STEPS = [
  { phase: 'CREATED',    label: 'Created',    desc: 'Intent registered' },
  { phase: 'PLANNING',   label: 'Planning',   desc: 'Building execution plan' },
  { phase: 'PLANNED',    label: 'Planned',    desc: 'Plan locked' },
  { phase: 'EXECUTING',  label: 'Executing',  desc: 'Dispatching to adapter' },
  { phase: 'EVALUATING', label: 'Evaluating', desc: 'Scoring output' },
  { phase: 'COMPLETED',  label: 'Completed',  desc: 'Terminal state' },
];

function stepStatus(phase, currentPhase, terminal, satisfied) {
  const order = PHASE_ORDER;
  const cur   = order.indexOf(currentPhase);
  const idx   = order.indexOf(phase);
  if (terminal) {
    if (idx < order.length - 1) return 'done';
    return satisfied ? 'done' : 'failed';
  }
  if (idx < cur)   return 'done';
  if (idx === cur) return 'active';
  return 'pending';
}

function StepIcon({ status }) {
  if (status === 'done')   return <CheckCircle2 size={18} className="text-green-500" />;
  if (status === 'active') return <Loader2 size={18} className="text-blue-600 animate-spin" />;
  if (status === 'failed') return <XCircle size={18} className="text-red-500" />;
  return <Circle size={18} className="text-slate-300" />;
}

export default function ExecutionTimeline({ keycloak, intentId }) {
  const [loading,      setLoading]      = useState(true);
  const [currentPhase, setCurrentPhase] = useState('CREATED');
  const [terminal,     setTerminal]     = useState(false);
  const [satisfied,    setSatisfied]    = useState(false);
  const intervalRef = useRef(null);

  useEffect(() => {
    if (!intentId) return;
    let mounted = true;

    async function load() {
      try {
        const data = await getIntentEvents(keycloak, intentId);
        if (!mounted) return;

        const list = data ?? [];
        if (list.length === 0) return;

        const allPhases = list.flatMap(e =>
          [e.phaseTo, e.phaseFrom, e.eventType].filter(Boolean)
        );
        const isTerminal  = allPhases.some(p => TERMINAL_PHASES.has(p));
        const isSatisfied = allPhases.some(p => p === 'SATISFIED' || p?.includes('SATISF'));

        if (isTerminal) {
          setCurrentPhase('COMPLETED');
          setTerminal(true);
          setSatisfied(isSatisfied);
          clearInterval(intervalRef.current);
          intervalRef.current = null;
          return;
        }

        const latest  = list[list.length - 1];
        const raw     = latest.phaseTo ?? latest.phaseFrom ?? 'CREATED';
        setCurrentPhase(PHASE_MAP[raw] ?? raw);

      } catch { /* non-fatal */ }
      finally  { if (mounted) setLoading(false); }
    }

    load();
    intervalRef.current = setInterval(load, 3000);

    return () => {
      mounted = false;
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    };
  }, [intentId]);

  if (loading) return <div className="flex justify-center py-8"><Spinner /></div>;

  return (
    <div className="relative space-y-0">
      {STEPS.map(({ phase, label, desc }, i) => {
        const status = stepStatus(phase, currentPhase, terminal, satisfied);
        const isLast = i === STEPS.length - 1;

        return (
          <div key={phase} className="relative">
            {/* Connector line */}
            {!isLast && (
              <div className={cn(
                'absolute left-[8px] top-[26px] h-10 w-px',
                status === 'done' ? 'bg-green-200' : 'bg-slate-100'
              )} />
            )}
            <div className="flex items-start gap-3 py-1">
              <div className="shrink-0 mt-0.5 z-10">
                <StepIcon status={isLast && terminal && !satisfied ? 'failed' : status} />
              </div>
              <div className="flex-1">
                <div className="flex items-center gap-2">
                  <span className={cn('text-sm font-medium', {
                    done:    'text-slate-800',
                    active:  'text-blue-700',
                    pending: 'text-slate-400',
                    failed:  'text-red-600',
                  }[status])}>
                    {label}
                  </span>
                  {status === 'active' && !terminal && (
                    <span className="text-[10px] font-medium text-blue-600 bg-blue-50 px-1.5 py-0.5 rounded-full">
                      In progress
                    </span>
                  )}
                  {isLast && terminal && (
                    <span className={cn(
                      'text-[10px] font-medium px-1.5 py-0.5 rounded-full',
                      satisfied ? 'text-green-600 bg-green-50' : 'text-red-600 bg-red-50'
                    )}>
                      {satisfied ? 'Satisfied ✓' : 'Violated ✗'}
                    </span>
                  )}
                </div>
                <p className="text-xs text-slate-400">{desc}</p>
              </div>
            </div>
          </div>
        );
      })}

      {/* Terminal summary */}
      {terminal && (
        <div className={cn(
          'mt-4 px-4 py-3 rounded-xl text-xs font-medium flex items-center gap-2',
          satisfied
            ? 'bg-green-50 border border-green-200 text-green-700'
            : 'bg-red-50 border border-red-200 text-red-700'
        )}>
          {satisfied
            ? <><CheckCircle2 size={14} className="shrink-0" /> Intent completed and satisfied all policy constraints</>
            : <><XCircle      size={14} className="shrink-0" /> Intent did not satisfy policy constraints</>
          }
        </div>
      )}
    </div>
  );
}
