import { createContext, useContext, useState, useEffect } from 'react';
import { getOrg, listProjects } from '../utils/api';

// ── Default data ──────────────────────────────────────────────────────────────
// Used as fallback when the API hasn't returned yet or isn't available.

const DEFAULT_ORG = {
  id:          'org-default',
  name:        'My Organisation',
  plan:        'Pro',
  logoInitial: 'M',
};

const DEFAULT_PROJECT = {
  id:          'proj-default',
  name:        'Default Project',
  environment: 'Production',
  description: 'Default project',
  isDefault:   true,
};

// ── Context ───────────────────────────────────────────────────────────────────

const ProjectContext = createContext(null);

export function ProjectProvider({ keycloak, children }) {
  const [org,           setOrg]           = useState(DEFAULT_ORG);
  const [projects,      setProjects]      = useState([DEFAULT_PROJECT]);
  const [activeProject, setActiveProject] = useState(DEFAULT_PROJECT);
  const [loading,       setLoading]       = useState(true);

  useEffect(() => {
    let active = true;

    async function load() {
      if (!keycloak?.authenticated || !keycloak?.token) {
        setLoading(false);
        return;
      }
      try {
        // Uses the shared request() helper (token refresh + 401 handling)
        // instead of duplicate fetch calls with a hardcoded URL.
        const [orgRes, projRes] = await Promise.allSettled([
          getOrg(keycloak),
          listProjects(keycloak),
        ]);

        if (!active) return;

        if (orgRes.status === 'fulfilled' && orgRes.value) {
          setOrg({ ...DEFAULT_ORG, ...orgRes.value });
        }

        if (projRes.status === 'fulfilled' && projRes.value) {
          const data = projRes.value;
          const list = Array.isArray(data) ? data : (data.content ?? []);
          if (list.length > 0) {
            setProjects(list);
            // Restore last selected project from localStorage
            const saved = localStorage.getItem('dm_active_project');
            const found = saved ? list.find(p => p.id === saved) : null;
            setActiveProject(found ?? list.find(p => p.isDefault) ?? list[0]);
          }
        }
      } catch {
        // API not ready — keep defaults
      } finally {
        if (active) setLoading(false);
      }
    }

    load();
    return () => { active = false; };
  }, [keycloak?.authenticated]);

  function switchProject(project) {
    setActiveProject(project);
    localStorage.setItem('dm_active_project', project.id);
  }

  function addProject(project) {
    const newList = [...projects, project];
    setProjects(newList);
    switchProject(project);
  }

  function updateProject(updated) {
    setProjects(ps => ps.map(p => p.id === updated.id ? { ...p, ...updated } : p));
    if (activeProject.id === updated.id) setActiveProject(p => ({ ...p, ...updated }));
  }

  return (
    <ProjectContext.Provider value={{
      org, setOrg,
      projects, setProjects,
      activeProject, switchProject,
      addProject, updateProject,
      loading,
    }}>
      {children}
    </ProjectContext.Provider>
  );
}

export function useProject() {
  const ctx = useContext(ProjectContext);
  if (!ctx) throw new Error('useProject must be used inside ProjectProvider');
  return ctx;
}
