import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
export default defineConfig({
  plugins: [react(), tailwindcss()],
  // Dub bundles Next-oriented utilities that read process.env at import time.
  // Keep those optional settings empty in the browser; never expose host env.
  define: { "process.env": "{}" },
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes("node_modules")) {
            return;
          }
          if (/\/node_modules\/(react|react-dom|scheduler)\//.test(id)) {
            return "react-runtime";
          }
          if (id.includes("/zod/")) {
            return "validation";
          }
          return undefined;
        },
      },
    },
  },
  server: {
    host: "127.0.0.1",
    port: 5173,
    proxy: { "/api": `http://127.0.0.1:${process.env.PORT || 3100}` },
  },
});
