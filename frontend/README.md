# Reconis frontend

Vue 3 and TypeScript interface for the public product page and role-based payment workspace.

- `src/pages/`: orders, refunds, reconciliation, cases, analytics and administration.
- `src/components/`: shared navigation, forms, charts and visual elements.
- `src/stores/`: session state.
- `tests/`: unit tests; `e2e/`: browser workflows.

Run commands in this directory with Node.js 24 and the pnpm version declared in `package.json`:

```sh
pnpm install --frozen-lockfile
pnpm dev
pnpm test
pnpm build
```

The development server proxies API requests to the local backend. `pnpm build` checks TypeScript and produces the production bundle. See the [runbook](../docs/runbook.md) for backend startup and browser test setup.
