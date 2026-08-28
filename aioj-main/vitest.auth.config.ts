import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "jsdom",
    include: [
      "packages/api-client/src/**/*.test.ts",
      "packages/ui-react/src/**/*.test.tsx",
      "apps/web-user-react/src/lib/**/*.test.tsx"
    ],
    pool: "threads",
    clearMocks: true,
    restoreMocks: true
  }
});
