# Change Log

* 0.6.48 -- 2026-03-23

  **BREAKING CHANGES:**
  - `:build?` removed — use `:vite {:mode :build}` (default) or `:vite nil` to
    skip vite
  - `:hot-reload` removed — use
    [zodiac-hot-reload](https://github.com/brettatoms/zodiac-hot-reload) instead
  - `:config-file` moved into `:vite` map — `:vite {:config-file "..."}`
  - `:package-json-dir` moved into `:vite` map — `:vite {:package-json-dir "..."}`
  - `:vite-host` and `:vite-port` replaced by `:vite {:host "..." :port ...}`
  - `::vite-dev-server` Integrant component removed — use `::vite` with
    `:mode :dev-server`
  - `::hot-reload` and `::hot-reload-middleware` Integrant components removed
  - `ring-hot-reload` dependency removed

  Other changes:
  - Single `:vite` map option replaces multiple top-level keys
  - `::assets` takes a vite ref directly instead of a pre-computed URL
  - Malli `:orn` schema with per-mode validation (`ViteBuild`, `ViteDevServer`)
  - Default values (`:mode`, `:host`, `:port`) driven by malli schema
  - `:config-file` no longer required; Vite auto-resolves config in project root
  - `::vite-client-middleware` injects `@vite/client` in dev-server mode
  - Bump com.cnuernber/charred 1.037 -> 1.038
  - Bump metosin/malli 0.20.0 -> 0.20.1
  - Bump metosin/reitit 0.9.2 -> 0.10.1

* 0.5.40 -- 2026-03-21
  - Add hot reload support using ring-hot-reload

* 0.4.34 -- 2025-12-15
  - Add options validation with Malli schema
  - Add metosin/malli 0.20.0
  - Bump org.clojure/clojure 1.12.0 -> 1.12.4
  - Bump integrant/integrant 0.13.1 -> 1.0.1
  - Bump babashka/fs 0.5.25 -> 0.5.30
  - Bump metosin/reitit 0.9.0 -> 0.9.2
  - Replace org.clojure/data.json with com.cnuernber/charred 1.037
  - More tests and docs

* 0.3.28 -- 2025-07-18
  - Don't `npm install` when build? is false

* 0.3.23 -- 2025-05-26
  - Bump version babashka/fs 0.5.24 -> 0.5.25
  - Bump version metosin/reitit 0.7.2 -> 0.9.0

* 0.2.22 -- 2025-02-09
  - Bump babashka/fs 0.4.19 -> 0.5.24
  - Bump org.clojure/data.json 2.4.0 -> 2.5.1

* 0.2.20 -- 2025-02-07
  - Run "npm install" before starting vite

* 0.1.18 -- 2024-11-17
  - Prefix asset urls with "/"
  - Default context key to :zodiac.ext.assets/assets
  - Make context key configurable

* 0.1.15 -- 2024-11-17
  - Don't throw an exception if the manifest file can't be found on startup.

* 0.1.14 -- 2024-11-16
  - Add missing dep

* 0.1.13 -- 2024-11-16
  - Add missing deps
  - Rebuild docs

* 0.1.6 -- 2024-11-12
  - Initial public version
