import { QueryClientProvider } from "@tanstack/react-query";
import React from "react";
import { TooltipProvider } from "@dub/ui";
import ReactDOM from "react-dom/client";
import App from "./App";
import { Toaster } from "./components/ui/toast";
import { queryClient } from "./lib/api";
import "./styles.css";
const root = document.getElementById("root");
if (!root) {
  throw new Error("页面缺少 root 挂载节点");
}

ReactDOM.createRoot(root).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <App />
        <Toaster closeButton position="bottom-right" />
      </TooltipProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
