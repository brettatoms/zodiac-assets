# Zodiac Assets

[![Clojars Project](https://img.shields.io/clojars/v/com.github.brettatoms/zodiac-assets.svg)](https://clojars.org/com.github.brettatoms/zodiac-assets)

An extension for [Zodiac](https://github.com/brettatoms/zodiac) to build and
serve static assets.

This extension is built with [Vite](https://vite.dev/) and doesn't try to
abstract away the features of Vite or the JS platform. Instead this extension
embraces JS as a platform for building static assets and provides very simple
wrappers for serving the static assets.

For example this extension only allows you to set the Vite config file path for
the build step. Any other Vite configuration options should be set in the Vite
config file.

To run Vite in watch mode you will need to set
[build.watch](https://vite.dev/config/build-options.html#build-watch) to true in
the Vite config file.

For an example of how to use this extension see [examples/todo-app](examples/todo-app).


### Getting started

```clojure
(ns myapp
  (:require [babashka.fs :as fs]
            [zodiac.core :as z]
            [zodiac.ext.assets :as z.assets]))

(defn handler [{:keys [::z/context]}]
  ;; The assets function in the request context can be used
  ;; to get the url path to built assets from the Vite manifest.
  (let [{::z.assets/keys [assets]} context]
    [:html
     [:head
       [:script {:src (assets "src/myapp.ts")}]]
       [:link {:rel "stylesheet" :href (assets "src/myapp.css")}]
     [:body
       [:div "hello world"]]]))

(defn routes []
  ["/" {:get #'handler}])

(let [project-root (-> *file* fs/parent fs/parent str) ;; WARNING: *file* only works in the repl
      assets-ext (z.assets/init {;; The manifest path is the relative resource
                                 ;; path to the output manifest file. This value doesn't override the build
                                 ;; time value for the output path of the manifest file.  By default
                                 ;; the manifest file is written to <outDir>/.vite/manifest.json
                                 :manifest-path  "myapp/.vite/manifest.json"
                                 ;; The resource path the the built assets. By default the build assets
                                 ;; are written to  <outDir>/assets
                                 :asset-resource-path "myapp/assets"
                                 ;; Vite configuration. The config file is used
                                 ;; by the vite command so it needs to be an
                                 ;; absolute path on the filesystem, e.g. not
                                 ;; in a jar.
                                 :vite {:config-file (str (fs/path project-root "vite.config.js"))}})]
  (z/start {:extensions [assets-ext]
            :routes routes})
```

### Configuring Vite

You will need to provide a vite config file to run the `vite build` command with this extension.

The very basic config file will need an `outDir` which holds the relative path for where to save the build assets and manifest file.

``` javascript
import { defineConfig } from "vite"

export default defineConfig({
  build: {
    // The resources/ folder will need to be on the Java classpath.  e.g. add it to
    // the :src key in your deps.edn file.
    outDir: "resources/myapp",
    manifest: true,
    rollupOptions: {
      input: [
        "src/myapp.ts",
        "src/myapp.css",
      ],
    },
  },
})
```


### Options

The `zodiac.ext.assets/init` accepts the following options:

- `:manifest-path`: The resource path to the Vite manifest.json. Required.
- `:asset-resource-path`: The resource path to the built assets. This should be the
  same as the `asset-dir` Vite config. Required.
- `:cache-manifest?`: Set to true to cache the manifest file the first time it
  is read instead of reading it on every request. This will improve the
  performance of resolving the asset paths from the `manifest.json`. Set to
  `false` if running vite in watch mode. Set to `true` in production. Defaults
  to `false`.
- `:context-key`: The name of the key to the `(assets)` function in the Zodiac
  request context. Defaults to `:zodiac.ext.assets/assets`.
- `:vite`: Vite configuration map, or `nil` to skip Vite entirely (just serve
  pre-built assets from the manifest). Defaults to `{:mode :build}`. Accepts:
  - `:mode` — `:build` (default, runs `npm install` + `vite build`) or
    `:dev-server` (runs `npm install` + starts the Vite dev server)
  - `:config-file` — absolute path to the vite config file. If omitted, Vite
    will auto-resolve `vite.config.js` (or `.ts`, `.mjs`) in the project root.
  - `:package-json-dir` — directory containing `package.json` for `npm install`
  - `:host` — dev server host (default `"localhost"`, `:dev-server` mode only)
  - `:port` — dev server port (default `5173`, `:dev-server` mode only)

### Dev Server Mode

Set `:vite {:mode :dev-server}` to use the Vite dev server instead of `vite build`.
Assets are served directly from the Vite dev server, providing instant CSS/JS
HMR via Vite's native `@vite/client`.

```clojure
(z.assets/init {:manifest-path "myapp/.vite/manifest.json"
                :asset-resource-path "myapp/assets"
                :vite {:mode :dev-server}})
```

In this mode:
- `(assets "src/app.js")` returns `http://localhost:5173/src/app.js` instead of
  a manifest-resolved path
- CSS/JS changes are handled instantly by Vite's native HMR (no page reload)

### Hot Reload for Clojure Templates

For automatic browser refresh when Clojure source or template files change,
use [ring-hot-reload](https://github.com/brettatoms/ring-hot-reload) alongside
zodiac-assets. The two are independent — zodiac-assets handles Vite/assets,
ring-hot-reload handles server-rendered content reload via DOM morphing.
