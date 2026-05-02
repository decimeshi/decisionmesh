import { useState, useRef, useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  Bell, LogOut, User, ChevronRight, PanelLeftOpen,
  Settings, UserPlus, ChevronDown, Palette, CreditCard, Zap,
} from 'lucide-react';
import { shortId } from '../lib/utils';
import { useProject } from '../context/ProjectContext';
import { useCredits } from '../context/CreditContext';

const LABELS = {
  '/':                    'Dashboard',
  '/playground':          'Playground',
  '/intents':             'Intents',
  '/executions':          'Execution Monitor',
  '/adapters':            'Adapters',
  '/policies':            'Policy Builder',
  '/analytics/cost':      'Cost Analytics',
  '/analytics/drift':     'Drift Dashboard',
  '/api-keys':            'API Keys',
  '/audit':               'Audit Log',
  '/invite':              'Team & Invitations',
  '/projects':            'Projects',
  '/profile':             'Account Settings',
  '/org/branding':        'Organisation Branding',
  '/billing':             'Billing & Plans',
  '/credits':             'Credit Ledger',
};

function useBreadcrumbs() {
  const { pathname } = useLocation();
  if (LABELS[pathname]) return [{ label: LABELS[pathname] }];
  const parts = pathname.split('/').filter(Boolean);
  return parts.map((part, i) => {
    const path = '/' + parts.slice(0, i + 1).join('/');
    return { label: LABELS[path] ?? (part.length === 36 ? shortId(part) : part) };
  });
}

function CreditPill() {
  const navigate = useNavigate();
  const { balance, allocated, isLow, isEmpty, statusColor } = useCredits();
  if (balance === null) return null;

  const pct = allocated ? Math.min(100, (balance / allocated) * 100) : 100;

  return (
    <button
      onClick={() => navigate('/billing?tab=credits')}
      title={`${balance?.toLocaleString()} credits remaining — click to top up`}
      className="hidden sm:flex items-center gap-2 px-2.5 py-1.5 rounded-lg bg-slate-50 border border-slate-200 hover:border-slate-300 transition-colors"
    >
      <Zap size={12} style={{ color: statusColor }} />
      <div className="flex flex-col items-end gap-0.5">
        <span className="text-xs font-semibold leading-none" style={{ color: statusColor }}>
          {balance?.toLocaleString()}
          <span className="font-normal text-slate-400"> cr</span>
        </span>
        <div className="w-14 h-1 bg-slate-200 rounded-full overflow-hidden">
          <div className="h-full rounded-full transition-all"
            style={{ width: `${pct}%`, backgroundColor: statusColor }} />
        </div>
      </div>
    </button>
  );
}

function UserMenu({ keycloak }) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const ref  = useRef(null);
  const user = keycloak?.tokenParsed;

  useEffect(() => {
    const h = e => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, []);

  const initials = [user?.given_name?.[0], user?.family_name?.[0]]
    .filter(Boolean).join('').toUpperCase() || user?.preferred_username?.[0]?.toUpperCase() || '?';

  const MENU = [
    {
      group: 'Account',
      items: [
        { icon: User,       label: 'Profile settings', action: () => navigate('/profile') },
        { icon: Settings,   label: 'Security',          action: () => navigate('/profile?tab=security') },
      ],
    },
    {
      group: 'Organisation',
      items: [
        { icon: Palette,    label: 'Branding',          action: () => navigate('/org/branding') },
        { icon: CreditCard, label: 'Billing & Plans',   action: () => navigate('/billing') },
        { icon: Zap,        label: 'Credit ledger',     action: () => navigate('/credits') },
      ],
    },
    {
      group: 'Team',
      items: [
        { icon: UserPlus,   label: 'Invite users',      action: () => navigate('/invite') },
      ],
    },
  ];

  return (
    <div ref={ref} className="relative">
      <button
        onClick={() => setOpen(o => !o)}
        className="flex items-center gap-2 pl-2 border-l border-slate-100 hover:bg-slate-50 rounded-lg px-2 py-1 transition-colors"
      >
        {/* ✅ User avatar — was bg-gradient from-blue-500 to-indigo-600, now CSS variables */}
        <div
          className="w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold text-white shrink-0"
          style={{
            background: 'linear-gradient(to bottom right, var(--brand-primary), var(--brand-dark))',
          }}
        >
          {initials}
        </div>
        <div className="hidden sm:block text-left">
          <p className="text-xs font-medium text-slate-700 leading-none">
            {user?.preferred_username ?? user?.name ?? '—'}
          </p>
          <p className="text-[11px] text-slate-400 mt-0.5 truncate max-w-28">{user?.email ?? ''}</p>
        </div>
        <ChevronDown size={12} className={`text-slate-400 hidden sm:block transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>

      {open && (
        <div className="absolute right-0 top-full mt-2 w-56 bg-white rounded-xl border border-slate-200 shadow-lg overflow-hidden z-50">
          <div className="px-4 py-3 border-b border-slate-100 bg-slate-50">
            <p className="text-xs font-semibold text-slate-700 truncate">{user?.name ?? user?.preferred_username}</p>
            <p className="text-[11px] text-slate-400 mt-0.5 truncate">{user?.email}</p>
          </div>
          {MENU.map(({ group, items }) => (
            <div key={group} className="py-1 border-b border-slate-100 last:border-0">
              <p className="px-3 py-1.5 text-[10px] font-semibold text-slate-400 uppercase tracking-wider">{group}</p>
              {items.map(({ icon: Icon, label, action }) => (
                <button key={label} onClick={() => { action(); setOpen(false); }}
                  className="w-full flex items-center gap-2.5 px-3 py-2 text-sm text-slate-600 hover:bg-slate-50 hover:text-slate-900 transition-colors text-left">
                  <Icon size={14} className="text-slate-400 shrink-0" />
                  {label}
                </button>
              ))}
            </div>
          ))}
          <div className="py-1">
            <button onClick={() => keycloak?.logout()}
              className="w-full flex items-center gap-2.5 px-3 py-2 text-sm text-red-600 hover:bg-red-50 transition-colors text-left">
              <LogOut size={14} className="shrink-0" />
              Sign out
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

export default function TopBar({ keycloak, sidebarHidden, onToggleSidebar }) {
  const crumbs = useBreadcrumbs();
  const { activeProject, loading: projectLoading } = useProject();

  return (
    <header className="flex items-center justify-between bg-white border-b border-slate-100 shrink-0"
      style={{ height: 48, paddingLeft: sidebarHidden ? 12 : 16, paddingRight: 12 }}>

      <div className="flex items-center gap-2 min-w-0">
        {sidebarHidden && (
          <button onClick={onToggleSidebar} title="Show sidebar"
            className="p-1 rounded-md text-slate-400 hover:text-slate-700 hover:bg-slate-100 transition-colors shrink-0">
            <PanelLeftOpen size={15} />
          </button>
        )}
        <nav className="flex items-center gap-1 text-sm min-w-0">
          {crumbs.map((c, i) => (
            <span key={i} className="flex items-center gap-1 min-w-0">
              {i > 0 && <ChevronRight size={12} className="text-slate-300 shrink-0" />}
              <span className={`truncate ${i === crumbs.length - 1 ? 'font-medium text-slate-800' : 'text-slate-400 text-xs'}`}>
                {c.label}
              </span>
            </span>
          ))}
        </nav>
      </div>

      <div className="flex items-center gap-2 shrink-0">
        <CreditPill />

        {!projectLoading && activeProject && (
          <span className="hidden lg:flex items-center gap-1 text-xs px-2 py-0.5 rounded-full bg-slate-100 text-slate-500 max-w-28">
            <span className="w-1.5 h-1.5 rounded-full bg-green-500 shrink-0" />
            <span className="truncate">{activeProject.name}</span>
          </span>
        )}

        <button className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400 transition-colors">
          <Bell size={14} />
        </button>

        <UserMenu keycloak={keycloak} />
      </div>
    </header>
  );
}
