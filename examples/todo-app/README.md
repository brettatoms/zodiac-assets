# Todo App — zodiac-assets example

A todo app built with Zodiac, htmx, Alpine.js, and Tailwind CSS. Demonstrates
zodiac-assets with Vite dev server integration and ring-hot-reload for automatic
browser updates on file changes.

## Running

```bash
cd examples/todo-app
clojure -M:main
```

This starts:
- `npm install` to install JS dependencies
- Vite dev server on port 5173 for CSS/JS HMR
- Zodiac (Ring + Jetty) on port 3000 with hot reload enabled

Open http://localhost:3000.

## Things to try

- **Add, check, and delete todos** — htmx handles these as partial updates
- **Edit `src/todo.clj`** and eval the changed form in your REPL — the page
  hot reloads immediately (no save needed, via the nREPL middleware in `.nrepl.edn`).
  Saving also triggers a reload for template/static file changes.
- **Edit `src/todo.css`** and save — Tailwind/CSS changes are applied instantly
  by Vite's native HMR, no page reload at all

## How it works

Two independent mechanisms handle updates:

1. **ring-hot-reload** watches `.clj`, `.cljc`, `.edn`, `.html` files. On file
   save, it notifies the browser via WebSocket. The browser re-fetches the page
   and morphs the DOM in place using idiomorph. For Clojure source changes to
   take effect, the changed code must be evaluated in the REPL first — saving
   the file alone only triggers the browser re-fetch.
2. **Vite dev server** handles `.css`, `.ts`, `.js` files natively. The injected
   `@vite/client` script receives HMR updates and patches styles/modules instantly.

Asset URLs are resolved to the Vite dev server automatically:
`(assets "src/todo.css")` → `http://localhost:5173/src/todo.css`
