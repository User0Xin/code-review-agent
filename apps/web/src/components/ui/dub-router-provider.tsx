import {
  AppRouterContext,
  type AppRouterInstance,
} from "next/dist/shared/lib/app-router-context.shared-runtime";
import { useContext, type ReactNode } from "react";

// Dub's Modal reads Next's router even when showModal/setShowModal control it.
// Use Next's actual context so it also works in this Vite-hosted React app.
const browserRouter: AppRouterInstance = {
  back: () => window.history.back(),
  forward: () => window.history.forward(),
  refresh: () => window.location.reload(),
  push: (href) => window.location.assign(href),
  replace: (href) => window.location.replace(href),
  prefetch: (href) => {
    const link = document.createElement("link");
    link.rel = "prefetch";
    link.href = href;
    document.head.appendChild(link);
  },
};

export function DubRouterProvider({ children }: { children: ReactNode }) {
  const router = useContext(AppRouterContext);
  return (
    <AppRouterContext.Provider value={router ?? browserRouter}>
      {children}
    </AppRouterContext.Provider>
  );
}
