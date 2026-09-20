import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  test: {
    // jsdom for every test, not just the component ones. The pure-function tests do not
    // need a document and do not notice one; splitting the suite in two environments
    // would cost a per-file docblock and the chance to forget it.
    environment: "jsdom",
  },
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
