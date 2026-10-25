import { toast } from "@/components/ui/toast";
import { create } from "zustand";
import { persist } from "zustand/middleware";
type View = "workspace" | "models" | "experiences" | "jobs";
interface UiState {
  projectId: string | null;
  sessionId: string | null;
  view: View;
  navigationCollapsed: boolean;
  contextCollapsed: boolean;
  collapsedProjectIds: string[];
  setNavigationCollapsed: (collapsed: boolean) => void;
  setContextCollapsed: (collapsed: boolean) => void;
  toggleProjectCollapsed: (id: string) => void;
  selectProject: (id: string) => void;
  selectSession: (projectId: string, id: string) => void;
  setView: (view: View) => void;
  notify: (message: string, error?: boolean) => void;
  goHome: () => void;
}
export const useUi = create<UiState>()(
  persist(
    (set) => ({
      projectId: null,
      sessionId: null,
      view: "workspace",
      navigationCollapsed: false,
      contextCollapsed: false,
      collapsedProjectIds: [],
      setNavigationCollapsed: (navigationCollapsed) => set({ navigationCollapsed }),
      setContextCollapsed: (contextCollapsed) => set({ contextCollapsed }),
      toggleProjectCollapsed: (id) =>
        set(({ collapsedProjectIds }) => ({
          collapsedProjectIds: collapsedProjectIds.includes(id)
            ? collapsedProjectIds.filter((projectId) => projectId !== id)
            : [...collapsedProjectIds, id],
        })),
      goHome: () => set({ projectId: null, sessionId: null, view: "workspace" }),
      selectProject: (id) =>
        set(({ collapsedProjectIds }) => ({
          projectId: id,
          sessionId: null,
          view: "workspace",
          collapsedProjectIds: collapsedProjectIds.filter((projectId) => projectId !== id),
        })),
      selectSession: (projectId, id) =>
        set(({ collapsedProjectIds }) => ({
          projectId,
          sessionId: id,
          view: "workspace",
          collapsedProjectIds: collapsedProjectIds.filter(
            (collapsedId) => collapsedId !== projectId,
          ),
        })),
      setView: (view) => set({ view }),
      notify: (message, error = false) => {
        toast[error ? "error" : "success"](message, {
          duration: error ? 10000 : 5000,
        });
      },
    }),
    {
      name: "review-agent-workspace",
      partialize: ({
        projectId,
        sessionId,
        view,
        navigationCollapsed,
        contextCollapsed,
        collapsedProjectIds,
      }) => ({
        projectId,
        sessionId,
        view,
        navigationCollapsed,
        contextCollapsed,
        collapsedProjectIds,
      }),
    },
  ),
);
