import react from '@vitejs/plugin-react'
// vitest/config rather than vite: a superset of Vite's own defineConfig that also types the
// `test` block below. The React plugin is shared with the app build, so component tests compile
// JSX the same way the app does rather than through a second, separately-configured transform.
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    // jsdom for every test, not just the component ones: the pure modules reach for browser
    // globals too (auth/roles.ts calls atob), so a single environment keeps them honest.
    environment: 'jsdom',
    // No globals: each test imports describe/it/expect explicitly. That keeps a vitest types
    // entry out of tsconfig.app.json, so `tsc -b` needs no test-specific wiring at all.
    globals: false,
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
