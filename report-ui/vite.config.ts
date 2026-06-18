import mdx from "@mdx-js/rollup";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  base: "./",
  plugins: [mdx(), react()],
  build: {
    outDir: "../resources/agraph/report-ui",
    emptyOutDir: true
  },
  test: {
    environment: "jsdom",
    setupFiles: ["src/setupTests.ts"]
  }
});
