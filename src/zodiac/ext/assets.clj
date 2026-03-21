(ns zodiac.ext.assets
  (:require [babashka.fs :as fs]
            [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.java.process :as process]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [malli.core :as m]
            [malli.error :as me]
            [malli.util :as mu]
            [reitit.ring]
            [ring.hot-reload.core :as hot]))

(create-ns 'zodiac.core)
(alias 'z 'zodiac.core)

(def Options
  "Malli schema for zodiac-assets options."
  (mu/optional-keys
   [:map
    ;; Required: Resource path to the Vite manifest.json
    [:manifest-path [:string {:min 1}]]
    ;; Required: Resource path to built assets directory
    [:asset-resource-path :string]
    ;; URL path prefix for serving assets
    [:asset-url-path :string]
    ;; Absolute path to vite.config.js (required if build? is true)
    [:config-file [:maybe :string]]
    ;; Whether to run npm install and vite build
    [:build? :boolean]
    ;; Cache manifest reads (set true for production)
    [:cache-manifest? :boolean]
    ;; Context key for the assets function
    [:context-key :keyword]
    ;; Directory containing package.json for npm install
    [:package-json-dir :string]
    ;; Hot reload mode: :build (standalone WS) or :dev-server (Vite WS)
    [:hot-reload [:maybe [:enum :build :dev-server]]]
    ;; Directories to watch for file changes (hot reload)
    [:watch-paths [:maybe [:sequential :string]]]
    ;; Vite dev server port (default 5173, used in :dev-server mode)
    [:vite-port [:maybe :int]]
    ;; Vite dev server host (default "localhost")
    [:vite-host [:maybe :string]]]))

(defn- capture-output [process namespace]
  (let [stdout (process/stdout process)
        stderr (process/stderr process)]
    (future
      ;; pipe stderr to the error log
      (->> (log/log-stream :error namespace)
           (.transferTo stderr)))
    (future
      ;; pipe stdout to the error log
      (->> (log/log-stream :debug namespace)
           (.transferTo stdout)))
    ;; Return the process
    process))

;; TODO: document package-json-dir options

(defmethod ig/init-key ::npm-install [_ {:keys [package-json-dir]}]
  (log/debug "$ npm clean-install")

  (when-not package-json-dir
    (log/warn "No package.json directory provided."))
  (let [p (process/start {:dir package-json-dir}
                         "npm" "clean-install")]
    (capture-output p ::npm-install)
    @(process/exit-ref p) ;; wait for the process to finish
    p))

(defmethod ig/init-key ::vite [_ {:keys [config-file package-json-dir]}]
  (when-not (fs/which "npx")
    (log/warn "Could not find path to npx. Starting vite will probably fail."))
  (let [vite-cmd (cond-> ["npx" "vite" "build"]
                   config-file (concat ["--config" config-file]))
        _ (log/debug (str "Starting vite...\n" (str/join " " vite-cmd)))
        p (apply process/start {:dir package-json-dir} vite-cmd)]
    ;; capture-output returns the process
    (capture-output p ::vite)))

(defmethod ig/halt-key! ::vite [_ p]
  ;; Kill the vite process
  (log/debug "Stopping vite...\n")
  (.destroy p))

(defmethod ig/init-key ::vite-dev-server [_ {:keys [config-file package-json-dir]}]
  (when-not (fs/which "npx")
    (log/warn "Could not find path to npx. Starting Vite dev server will probably fail."))
  (let [vite-cmd (cond-> ["npx" "vite"]
                   config-file (concat ["--config" config-file]))
        _ (log/info (str "Starting Vite dev server...\n" (str/join " " vite-cmd)))
        p (apply process/start {:dir package-json-dir} vite-cmd)]
    (capture-output p ::vite-dev-server)
    p))

(defmethod ig/halt-key! ::vite-dev-server [_ p]
  (log/info "Stopping Vite dev server...")
  (.destroy p))

(defmethod ig/init-key ::assets [_ {:keys [manifest-path cache-manifest?
                                           vite-dev-server-url]}]
  (if vite-dev-server-url
    ;; Dev server mode: resolve assets to Vite dev server URLs
    (fn [asset-name]
      (str vite-dev-server-url "/" asset-name))
    ;; Build mode: resolve assets from manifest
    (do
      (when-not (io/resource manifest-path)
        (log/warn "Could not load find the manifest on the classpath: " manifest-path))
      (let [url-for (fn [asset-name]
                      (let [manifest (some-> manifest-path
                                             (io/resource)
                                             (slurp)
                                             (json/read-json))]
                        (when-let [url (get-in manifest [asset-name "file"])]
                          (str "/" url))))]
        (if cache-manifest?
          (memoize url-for)
          url-for)))))

;; ---------------------------------------------------------------------------
;; Hot reload
;; ---------------------------------------------------------------------------

(defn- html-page-response?
  "Returns true if the response is a full HTML page with a string body."
  [{:keys [headers body]}]
  (and (string? body)
       (some-> (get headers "Content-Type"
                    (get headers "content-type"))
               (str/includes? "text/html"))
       (str/includes? body "<html")))

(defn- inject-vite-client-middleware
  "Ring middleware that injects the Vite client script tag into full HTML page
   responses for CSS HMR support in dev-server mode."
  [handler vite-client-url]
  (let [tag (str "<script type=\"module\" src=\"" vite-client-url "\"></script>")]
    (fn [request]
      (let [response (handler request)]
        (if (and (html-page-response? response)
                 (str/includes? (:body response) "<head"))
          (update response :body
                  #(str/replace-first % #"<head[^>]*>" (str "$0\n" tag)))
          response)))))

(def ^:private dev-server-watch-extensions
  "Watch extensions for dev-server mode. Only server-side files — Vite handles
   CSS/JS HMR natively via @vite/client."
  #{".clj" ".cljc" ".edn" ".html"})

(defmethod ig/init-key ::hot-reload
  [_ {:keys [handler watch-paths watch-extensions debounce-ms
             mode vite-host vite-port]}]
  (log/debug "Starting hot reload watcher...")
  (let [;; In dev-server mode, wrap handler with Vite client injection for CSS HMR
        ;; and narrow watch extensions to server-side files only
        handler (if (= mode :dev-server)
                  (inject-vite-client-middleware
                   handler
                   (str "http://" (or vite-host "localhost")
                        ":" (or vite-port 5173) "/@vite/client"))
                  handler)
        watch-extensions (if (= mode :dev-server)
                           (or watch-extensions dev-server-watch-extensions)
                           watch-extensions)
        {:keys [handler start! stop!]}
        (hot/wrap-hot-reload handler
                             (cond-> {:watch-paths watch-paths}
                               watch-extensions (assoc :watch-extensions watch-extensions)
                               debounce-ms (assoc :debounce-ms debounce-ms)))
        watcher-handle (start!)]
    (log/info (str "Hot reload active (" (name mode) " mode), watching: " (pr-str watch-paths)))
    (log/info "For REPL-eval reloading, add to .nrepl.edn: {:middleware [ring.hot-reload.nrepl/wrap-hot-reload-nrepl]}")
    (with-meta handler {::stop! stop! ::watcher-handle watcher-handle})))

(defmethod ig/halt-key! ::hot-reload [_ handler]
  (log/debug "Stopping hot reload watcher...")
  (let [{::keys [stop! watcher-handle]} (meta handler)]
    (when (and stop! watcher-handle)
      (stop! watcher-handle))))

;; ---------------------------------------------------------------------------

(defn- default-watch-paths
  "Returns the default watch paths for hot reload. Includes 'src' and the
   Vite output directory (resolved from asset-resource-path) if available."
  [asset-resource-path]
  (let [paths ["src"]]
    (if (and asset-resource-path (not (str/blank? asset-resource-path)))
      (let [resource-dir (str "resources/" asset-resource-path)]
        (if (fs/exists? resource-dir)
          (conj paths resource-dir)
          paths))
      paths)))

(defn init [{:keys [asset-resource-path asset-url-path build? config-file context-key
                    hot-reload watch-paths vite-port vite-host]
             :or {asset-resource-path ""
                  asset-url-path "/assets"
                  build? true
                  context-key ::assets
                  vite-port 5173
                  vite-host "localhost"}
             :as options}]

  (when-not (m/validate Options options)
    (let [errors (me/humanize (m/explain Options options))]
      (log/error (str "Invalid zodiac-assets options: " errors))
      (throw (ex-info "Invalid zodiac-assets options" {:validation-errors errors}))))

  ;; Validate option combinations
  (when (and build? (not config-file) (not= hot-reload :dev-server))
    (log/warn ":build? is true but no :config-file provided. Vite build may fail."))
  (when (and (= hot-reload :dev-server) build?)
    (log/warn ":hot-reload :dev-server with :build? true — build will be skipped in dev-server mode."))

  (let [dev-server? (= hot-reload :dev-server)
        vite-dev-server-url (when dev-server?
                              (str "http://" vite-host ":" vite-port))]
    (fn [config]
      (let [npm-opts (select-keys options [:package-json-dir])
            vite-opts (select-keys options [:config-file :package-json-dir])
            config (cond-> config
                     ;; In build mode, run npm install + vite build
                     (and build? (not dev-server?))
                     (assoc ::vite (assoc vite-opts :__depends (ig/ref ::npm-install))
                            ::npm-install npm-opts)
                     ;; In dev-server mode, run npm install + start Vite dev server
                     dev-server?
                     (assoc ::npm-install npm-opts
                            ::vite-dev-server
                            (assoc vite-opts :__depends (ig/ref ::npm-install))))
            assets-opts (cond-> (select-keys options [:manifest-path :cache-manifest?])
                          (and build? (not dev-server?))
                          (assoc :__depends (ig/ref ::vite))
                          dev-server?
                          (assoc :vite-dev-server-url vite-dev-server-url
                                 :__depends (ig/ref ::vite-dev-server)))
            resource-handler (reitit.ring/create-resource-handler {:path asset-url-path
                                                                   :root asset-resource-path})
            config (-> config
                       (assoc ::assets assets-opts)
                       (assoc-in [::z/middleware :context context-key] (ig/ref ::assets))
                       (update-in [::z/app :default-handlers] #(cons resource-handler %)))]
        (if hot-reload
          (-> config
              (assoc ::hot-reload
                     (cond-> {:handler (ig/ref ::z/app)
                              :mode hot-reload
                              :watch-paths (or watch-paths
                                               (default-watch-paths asset-resource-path))}
                       dev-server?
                       (assoc :vite-host vite-host
                              :vite-port vite-port
                              :__depends (ig/ref ::vite-dev-server))))
              (assoc-in [::z/jetty :handler] (ig/ref ::hot-reload)))
          config)))))
