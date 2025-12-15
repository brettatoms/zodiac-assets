# Vite Setup

## Minimal Configuration

zodiac-assets requires Vite to output a manifest file. Here's a minimal `vite.config.js`:

```javascript
import { defineConfig } from "vite"

export default defineConfig({
  build: {
    outDir: "resources/myapp",
    manifest: true,
    rollupOptions: {
      input: [
        "src/app.js",
        "src/style.css",
      ],
    },
  },
})
```

See `examples/todo-app/vite.config.js` for a working example.

## Required Settings

| Setting | Purpose |
|---------|---------|
| `build.outDir` | Where Vite writes built assets. Must be on your classpath. |
| `build.manifest` | Generates `.vite/manifest.json` for asset resolution. |
| `build.rollupOptions.input` | Entry points to bundle. |

## Environment-Specific Configs

Separate development and production Vite configurations allow different build behaviors.

### Production Config (vite.config.js)

```javascript
import { defineConfig } from "vite"

export default defineConfig({
  build: {
    outDir: "resources/myapp",
    manifest: true,
    rollupOptions: {
      input: ["src/app.js", "src/style.css"],
    },
  },
})
```

### Development Config (vite.config.dev.js)

```javascript
import { defineConfig, mergeConfig } from "vite"
import baseConfig from "./vite.config.js"

export default mergeConfig(baseConfig, defineConfig({
  mode: "development",
  build: {
    watch: {},
  },
}))
```

### Selecting Config in Clojure

```clojure
(let [dev? (= (System/getenv "ENV") "development")
      assets-ext (z.assets/init
                  {:manifest-path "myapp/.vite/manifest.json"
                   :asset-resource-path "myapp"
                   :config-file (if dev?
                                  "/app/vite.config.dev.js"
                                  "/app/vite.config.js")
                   :build? true
                   :cache-manifest? (not dev?)})]
  (z/start {:extensions [assets-ext]}))
```

## Watch Mode

For development, enable `build.watch` so Vite rebuilds when source files change:

```javascript
build: {
  watch: {},  // empty object enables watch mode
}
```

When using watch mode:
- Set `:cache-manifest? false` so zodiac-assets reads fresh manifests
- Vite runs as a long-lived process
- Changes to source files trigger automatic rebuilds

## Output Directory Structure

With `outDir: "resources/myapp"`, Vite creates:

```
resources/myapp/
  .vite/
    manifest.json    # Asset manifest
  assets/
    app-[hash].js    # Bundled JavaScript
    style-[hash].css # Bundled CSS
```

Configure zodiac-assets to match:

```clojure
(z.assets/init {:manifest-path "myapp/.vite/manifest.json"
                :asset-resource-path "myapp"
                ...})
```

## Common Patterns

### TypeScript

```javascript
import { defineConfig } from "vite"

export default defineConfig({
  build: {
    outDir: "resources/myapp",
    manifest: true,
    rollupOptions: {
      input: ["src/app.ts"],
    },
  },
})
```

Vite handles TypeScript compilation automatically.

### Tailwind CSS

Install Tailwind and configure `postcss.config.js`:

```javascript
// postcss.config.js
export default {
  plugins: {
    tailwindcss: {},
    autoprefixer: {},
  },
}
```

No changes needed to `vite.config.js`—Vite picks up PostCSS config automatically.

### Multiple Entry Points

```javascript
rollupOptions: {
  input: [
    "src/admin.js",
    "src/public.js",
    "src/shared.css",
  ],
}
```

Each entry point gets its own manifest entry:

```clojure
(assets "src/admin.js")  ; => "/assets/admin-abc123.js"
(assets "src/public.js") ; => "/assets/public-def456.js"
```

## Troubleshooting

### Manifest not found

Verify the manifest exists at your configured path:

```clojure
(io/resource "myapp/.vite/manifest.json")
```

Common causes:
- `outDir` doesn't match `:manifest-path`
- Output directory not on classpath
- Vite build hasn't run

### Assets return nil

The asset name must match the original source path in `rollupOptions.input`:

```clojure
;; If input is "src/app.js"
(assets "src/app.js")  ; correct
(assets "app.js")      ; returns nil
```
