# Configuration

## init Options

The `zodiac.ext.assets/init` function accepts a map with the following keys:

### :manifest-path

Resource path to the Vite `manifest.json`. This is a **classpath resource path**, not a filesystem path.

```clojure
(z.assets/init {:manifest-path "myapp/.vite/manifest.json"
                ...})
```

Vite writes the manifest to `<outDir>/.vite/manifest.json` by default.

### :asset-resource-path

Resource path to the built assets directory. Defaults to `""`.

```clojure
(z.assets/init {:manifest-path "myapp/.vite/manifest.json"
                :asset-resource-path "myapp/assets"
                ...})
```

### :asset-url-path

URL prefix for serving assets. Defaults to `"/assets"`.

```clojure
(z.assets/init {:asset-url-path "/static"
                ...})
```

### :config-file

Filesystem path to the Vite config file. Required if `:build?` is true.

```clojure
(z.assets/init {:config-file "/path/to/project/vite.config.js"
                ...})
```

**Note:** This must be an absolute filesystem path, not a resource path. It cannot be inside a JAR.

### :build?

Whether to run `npm install` and `vite build` when Zodiac starts. Defaults to `true`.

```clojure
;; Skip building (use pre-built assets)
(z.assets/init {:build? false
                ...})
```

### :cache-manifest?

Cache the manifest file after first read. Defaults to `false`.

| Environment | Setting | Why |
|-------------|---------|-----|
| Development | `false` | Manifest changes when Vite rebuilds |
| Production | `true` | Better performance, manifest is static |

```clojure
(z.assets/init {:cache-manifest? (= (System/getenv "ENV") "production")
                ...})
```

### :context-key

Key for the assets function in the Zodiac request context. Defaults to `:zodiac.ext.assets/assets`.

```clojure
(z.assets/init {:context-key :my-app/assets
                ...})

;; Access in handler
(defn handler [{:keys [::z/context]}]
  (let [assets (:my-app/assets context)]
    [:link {:href (assets "src/style.css")}]))
```

### :package-json-dir

Directory containing `package.json` for `npm install`. Defaults to current working directory.

```clojure
(z.assets/init {:package-json-dir "/path/to/frontend"
                ...})
```

## Path Types

zodiac-assets uses two types of paths:

| Option | Path Type | Example |
|--------|-----------|---------|
| `:manifest-path` | Classpath resource | `"myapp/.vite/manifest.json"` |
| `:asset-resource-path` | Classpath resource | `"myapp/assets"` |
| `:config-file` | Filesystem | `"/home/user/project/vite.config.js"` |
| `:package-json-dir` | Filesystem | `"/home/user/project"` |

**Classpath resource paths** are relative to directories on your classpath (typically `resources/`). They work inside JARs.

**Filesystem paths** are absolute paths on your system. They cannot be inside JARs.

## Production vs Development

```clojure
(let [production? (= (System/getenv "ENV") "production")
      assets-ext (z.assets/init
                  {:manifest-path "myapp/.vite/manifest.json"
                   :asset-resource-path "myapp/assets"
                   :config-file (if production?
                                  "/app/vite.config.js"
                                  "/app/vite.config.dev.js")
                   :build? (not production?)  ; Pre-build for production
                   :cache-manifest? production?})]
  (z/start {:extensions [assets-ext]
            ...}))
```

See [Vite Setup](vite-setup.md) for configuring separate development and production Vite configs.
