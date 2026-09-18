import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    // The lookup service runs separately in dev (./gradlew :service:bootRun). Proxying
    // /api keeps the browser on one origin, so dev needs no CORS config that production
    // — where Cloudflare puts the site and the API on the same zone — would not use.
    proxy: {
      "/api": {
        target: process.env.VITE_API_PROXY ?? "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
