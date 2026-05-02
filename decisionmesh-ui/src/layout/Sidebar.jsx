import { useState } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import {
  LayoutDashboard, FlaskConical, ListOrdered, Cpu,
  Puzzle, ShieldCheck, BarChart3, TrendingUp,
  KeyRound, ScrollText, ChevronLeft, ChevronRight,
  UserPlus, PanelLeftClose, FolderOpen,
  ChevronDown, Check, Plus, Palette, CreditCard, Receipt, Bug, Library,
} from 'lucide-react';
import { cn } from '../lib/utils';
import { useProject } from '../context/ProjectContext';
import { useCredits } from '../context/CreditContext';

const NAV = [
  { label: 'Dashboard',   icon: LayoutDashboard, to: '/' },
  { label: 'Playground',  icon: FlaskConical,    to: '/playground' },
  { label: 'Intent Library', icon: Library,      to: '/intent-library' },
  { label: 'Intents',     icon: ListOrdered,     to: '/intents' },
  { label: 'Executions',  icon: Cpu,             to: '/executions' },
  { label: 'Adapters',    icon: Puzzle,          to: '/adapters' },
  { label: 'Policies',    icon: ShieldCheck,     to: '/policies' },
  { label: 'Cost',        icon: BarChart3,       to: '/analytics/cost' },
  { label: 'Drift',       icon: TrendingUp,      to: '/analytics/drift' },
  { label: 'API Keys',    icon: KeyRound,        to: '/api-keys' },
  { label: 'Audit',       icon: ScrollText,      to: '/audit' },
  { label: 'Invite',      icon: UserPlus,        to: '/invite' },
  { label: 'Credits',     icon: Receipt,         to: '/credits' },
  { label: 'Branding',    icon: Palette,         to: '/org/branding' },
  { label: 'Billing',     icon: CreditCard,      to: '/billing' },
  { label: 'Token Debug', icon: Bug,             to: '/debug/token' },
];

const ENV_DOTS = {
  Production: 'bg-green-500',
  Staging:    'bg-amber-500',
  Dev:        'bg-blue-500',
};

function CreditFooter() {
  const navigate = useNavigate();
  const { balance, allocated, statusColor, isEmpty, isLow } = useCredits();
  if (balance === null) return null;
  const pct = allocated ? Math.min(100, (balance / allocated) * 100) : 100;
  return (
    <div
      onClick={() => navigate('/billing?tab=credits')}
      className="mx-2 mb-1 p-2.5 rounded-lg hover:bg-slate-50 cursor-pointer border border-slate-100 transition-colors"
    >
      <div className="flex items-center justify-between mb-1.5">
        <span className="text-[10px] font-semibold text-slate-500 uppercase tracking-wider">Credits</span>
        <span className="text-xs font-bold" style={{ color: statusColor }}>
          {balance?.toLocaleString()}
        </span>
      </div>
      <div className="h-1.5 bg-slate-100 rounded-full overflow-hidden">
        <div className="h-full rounded-full transition-all"
          style={{ width: `${pct}%`, backgroundColor: statusColor }} />
      </div>
      {(isEmpty || isLow) && (
        <p className="text-[10px] mt-1" style={{ color: statusColor }}>
          {isEmpty ? '⚠ No credits — top up' : '⚠ Running low'}
        </p>
      )}
    </div>
  );
}

function ProjectSwitcher() {
  const navigate = useNavigate();
  const { org, projects, activeProject, switchProject, loading } = useProject();
  const [open, setOpen] = useState(false);

  if (loading) return <div className="h-12 border-b border-slate-100" />;

  function handleSwitch(project) {
    switchProject(project);
    setOpen(false);
  }

  return (
    <div className="relative px-2 pb-2 border-b border-slate-100">
      <button
        onClick={() => setOpen(o => !o)}
        className="w-full flex items-center gap-2 px-2 py-2 rounded-lg hover:bg-slate-50 transition-colors text-left"
      >
        {/* org initial — was bg-blue-600, now uses CSS variable */}
        <div
          className="w-6 h-6 rounded-md flex items-center justify-center text-white text-[10px] font-bold shrink-0"
          style={{ backgroundColor: 'var(--brand-primary)' }}
        >
          {org.name?.[0]?.toUpperCase() ?? 'O'}
        </div>
        <div className="flex-1 min-w-0">
          <p className="text-[11px] text-slate-400 leading-none truncate">{org.name}</p>
          <div className="flex items-center gap-1 mt-0.5">
            <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${ENV_DOTS[activeProject?.environment] ?? 'bg-slate-400'}`} />
            <p className="text-xs font-medium text-slate-700 leading-none truncate">{activeProject?.name ?? 'No project'}</p>
          </div>
        </div>
        <ChevronDown size={12} className={`text-slate-400 shrink-0 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>

      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
          <div className="absolute left-2 right-2 top-full mt-1 z-20 bg-white rounded-xl border border-slate-200 shadow-lg overflow-hidden">
            <div className="px-3 py-2 border-b border-slate-100">
              <p className="text-[10px] font-semibold text-slate-400 uppercase tracking-wider">Projects</p>
            </div>
            <div className="max-h-52 overflow-y-auto py-1">
              {projects.map(p => (
                <button key={p.id}
                  onClick={() => handleSwitch(p)}
                  className="w-full flex items-center gap-2.5 px-3 py-2 hover:bg-slate-50 transition-colors text-left"
                >
                  <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${ENV_DOTS[p.environment] ?? 'bg-slate-400'}`} />
                  <span className="flex-1 text-sm text-slate-700 truncate">{p.name}</span>
                  {/* ✅ check icon — was text-blue-600, now CSS variable */}
                  {p.id === activeProject?.id && (
                    <Check size={12} className="shrink-0" style={{ color: 'var(--brand-primary)' }} />
                  )}
                </button>
              ))}
            </div>
            <div className="border-t border-slate-100 py-1">
              <button
                onClick={() => { setOpen(false); navigate('/projects'); }}
                className="w-full flex items-center gap-2 px-3 py-2 text-xs text-slate-500 hover:bg-slate-50 transition-colors"
                onMouseEnter={e => e.currentTarget.style.color = 'var(--brand-primary)'}
                onMouseLeave={e => e.currentTarget.style.color = ''}
              >
                <FolderOpen size={12} /> Manage projects
              </button>
              <button
                onClick={() => { setOpen(false); navigate('/projects?new=1'); }}
                className="w-full flex items-center gap-2 px-3 py-2 text-xs text-slate-500 hover:bg-slate-50 transition-colors"
                onMouseEnter={e => e.currentTarget.style.color = 'var(--brand-primary)'}
                onMouseLeave={e => e.currentTarget.style.color = ''}
              >
                <Plus size={12} /> New project
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

export default function Sidebar({ collapsed, onToggle, onHide }) {
  return (
    <aside className={cn(
      'flex flex-col h-screen bg-white border-r border-slate-100 transition-all duration-200 shrink-0',
      collapsed ? 'w-14' : 'w-52'
    )}>
      {/* Header */}
      <div className={cn(
        'flex items-center border-b border-slate-100 shrink-0',
        collapsed ? 'justify-center px-0 py-4' : 'px-3 py-4 gap-2'
      )}>
        {/* DecisionMesh icon — SVG from public/ */}
        <img
          src="/decimeshi-icon.svg"
          alt="DecisionMesh"
          className="w-7 h-7 rounded-lg shrink-0"
        />
        {!collapsed && (
          <>
            <img
              src="/decimeshi-logo-light.svg"
              alt="DecisionMesh"
              className="flex-1 h-6 object-left object-contain"
            />
            <button
              onClick={onHide}
              title="Hide sidebar"
              className="p-1 rounded-md text-slate-300 hover:text-slate-600 hover:bg-slate-100 transition-colors shrink-0"
            >
              <PanelLeftClose size={15} />
            </button>
          </>
        )}
      </div>

      {/* Project switcher */}
      {!collapsed && <ProjectSwitcher />}

      {/* Nav */}
      <nav className="flex-1 py-2 overflow-y-auto scrollbar-thin">
        {NAV.map(({ label, icon: Icon, to }) => (
          <NavLink
            key={to}
            to={to}
            end={to === '/'}
            // ✅ Active state — was bg-blue-50 text-blue-700, now CSS variables
            className={({ isActive }) => cn(
              'flex items-center gap-2.5 py-2 mx-2 px-2.5 rounded-lg text-sm font-medium transition-colors mb-0.5',
              !isActive && 'text-slate-600 hover:bg-slate-50 hover:text-slate-900',
              collapsed && 'justify-center mx-1 px-0'
            )}
            style={({ isActive }) => isActive ? {
              backgroundColor: 'var(--brand-light)',
              color:           'var(--brand-primary)',
            } : {}}
            title={collapsed ? label : undefined}
          >
            <Icon size={15} className="shrink-0" />
            {!collapsed && <span>{label}</span>}
          </NavLink>
        ))}
      </nav>

      {/* Credit footer */}
      {!collapsed && <CreditFooter />}

      {/* Collapse toggle */}
      <button
        onClick={onToggle}
        className={cn(
          'flex items-center gap-2 px-4 py-3 border-t border-slate-100 text-xs text-slate-400 hover:text-slate-700 hover:bg-slate-50 transition-colors',
          collapsed && 'justify-center px-0'
        )}
        title={collapsed ? 'Expand' : 'Collapse'}
      >
        {collapsed
          ? <ChevronRight size={13} />
          : <><ChevronLeft size={13} /><span>Collapse</span></>
        }
      </button>
    </aside>
  );
}
