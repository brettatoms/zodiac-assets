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
  (:require [zodiac.core :as z]
            [zodiac.ext.assets :as z.assets]))

(defn handler [{:keys [::z/context]}]
  ;; The assets function in the request context can be used
  ;; to get the url path to built assets from the Vite manifest.
  (let [{:keys [assets]} context]
    [:html
     [:head
       [:script {:src (assets "src/myapp.ts")}]]
     [:body
       [:div "hello world"]]]))

(defn routes []
  ["/" {:get #'handler}])

(let [project-root (-> *file* fs/parent fs/parent str)
      assets-ext (z.assets/init {;; The config file is used by the vite command
                                 ;; so it needs to be an absolute path on the
                                 ;; filesystem, e.g. not in a jar.
                                 :config-file (str (fs/path project-root "vite.config.js"))
                                 ;; The manifest path is the relative resource
                                 ;; path to the output manifest file. This value doesn't override the build
                                 ;; time value for the output path of the manifest file.  By default
                                 ;; the manifest file is written to <outDir>/.vite/manifest.json
                                 :manifest-path  "myapp/.vite/manifest.json"
                                 ;; The resource path the the built assets. By default the build assets
                                 ;; are written to  <outDir>/assets
                                 :asset-resource-path "myapp/assets"})]
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

- `:manifest-path`: The resource path to the Vite manifest.json json. Required.
- `:asset-resource-path`. The resource path the build assets. This should be the
  same os the `asset-dir` Vite config. Required.
- `:config-file`: The absolute path to the vite config file. This file is
  required if `:build?` is true.
- `:build?`: Whether to run `vite build` to build the assets. Set to false if
  you only want to use this extension to lookup assets from the vite manifest.
  Defaults to `true`.
- `:cache-manifest?`: Set to true to cache the manifest file the first time it
  is read instead of reading it on every request. This will improve the
  performance of resolve the assets paths from the `manifest.json`. Set to
  `false` if running vite in watch mode. Set to `true` in production. Defaults
  to `false`.
