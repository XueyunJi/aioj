import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5176,
    host: "0.0.0.0"
  },
  build: {
    chunkSizeWarningLimit: 1300,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes("node_modules")) return;
          if (id.includes("@uiw/react-markdown-preview") || id.includes("rehype-katex") || id.includes("remark-math") || id.includes("katex")) {
            return "markdown";
          }
          if (id.includes("jszip")) {
            return "jszip";
          }
          if (id.includes("react-dom") || id.includes("@tanstack")) {
            return "vendor-react";
          }
        }
      }
    }
  }
});
