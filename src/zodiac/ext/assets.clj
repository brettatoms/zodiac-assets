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
            [malli.transform :as mt]
            [malli.util :as mu]
            [reitit.ring]))

(create-ns 'zodiac.core)
(alias 'z 'zodiac.core)

(def ViteBuild
  "Schema for vite build mode options."
  (mu/optional-keys
   [:map
    [:mode {:default :build} [:= :build]]
    ;; Absolute path to vite.config.js
    [:config-file :string]
    ;; Directory containing package.json for npm install
    [:package-json-dir :string]]))

(def ViteDevServer
  "Schema for vite dev-server mode options."
  (mu/optional-keys
   [:map
    [:mode {:default :dev-server} [:= :dev-server]]
    ;; Absolute path to vite.config.js
    [:config-file :string]
    ;; Directory containing package.json for npm install
    [:package-json-dir :string]
    ;; Dev server host (default "localhost")
    [:host {:default "localhost"} :string]
    ;; Dev server port (default 5173)
    [:port {:default 5173} :int]]))

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
    ;; Cache manifest reads (set true for production)
    [:cache-manifest? :boolean]
    ;; Context key for the assets function
    [:context-key :keyword]
    ;; Vite configuration. nil to skip vite entirely.
    ;; Defaults to {:mode :build} when omitted.
    [:vite [:orn
            [:disabled :nil]
            [:build ViteBuild]
            [:dev-server ViteDevServer]]]]))

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

(defmethod ig/init-key ::vite [_ {:keys [config-file package-json-dir mode host port]
                                  :or {mode :build
                                       host "localhost"
                                       port 5173}}]
  (when-not (fs/which "npx")
    (log/warn "Could not find path to npx. Starting vite will probably fail."))
  (let [vite-cmd (case mode
                   :build      (cond-> ["npx" "vite" "build"]
                                 config-file (concat ["--config" config-file]))
                   :dev-server (cond-> ["npx" "vite"]
                                 config-file (concat ["--config" config-file])))
        _ (log/info (str "Starting vite (" (name mode) " mode)...\n"
                         package-json-dir "$ " (str/join " " vite-cmd)))
        p (apply process/start {:dir package-json-dir} vite-cmd)]
    (capture-output p ::vite)
    {:process p
     :mode    mode
     :url     (when (= mode :dev-server)
                (str "http://" host ":" port))}))

(defmethod ig/halt-key! ::vite [_ {:keys [process]}]
  (log/info "Stopping vite...")
  (.destroy process))

(defn- read-manifest [manifest-path]
  (some-> manifest-path
          (io/resource)
          (slurp)
          (json/read-json)))

(defn- imported-chunks
  "Recursively collect all imported chunks for a manifest entry.
   Returns a seq of chunk maps for the entry's imports (not the entry itself),
   following the imports graph depth-first."
  [manifest entry-name]
  (let [seen (volatile! #{})
        walk (fn walk [name]
               (when-not (@seen name)
                 (vswap! seen conj name)
                 (when-let [chunk (get manifest name)]
                   (let [children (mapcat #(walk %) (get chunk "imports"))]
                     (concat children [chunk])))))]
    ;; Walk each import of the entry, but not the entry itself
    (mapcat walk (get-in manifest [entry-name "imports"]))))

(defn entry-tags
  "Given an assets fn (from ::assets init-key) and an entry name, returns a list
   of hiccup elements following Vite's recommended tag order:

   1. <link rel=\"stylesheet\"> for each CSS file in the entry's css list
   2. <link rel=\"stylesheet\"> for CSS files from recursively imported chunks
   3. <script type=\"module\"> for JS entries, or <link rel=\"stylesheet\"> for CSS entries
   4. <link rel=\"modulepreload\"> for each imported JS chunk (recursive)

   Returns nil if entry-name is not found in the manifest.

   In dev-server mode, only emits a <script type=\"module\"> tag pointing to the
   Vite dev server (which handles CSS injection and HMR)."
  [assets-fn entry-name]
  (if (::dev-server? (meta assets-fn))
    ;; Dev server mode: just the script tag
    (list [:script {:type "module" :src (assets-fn entry-name)}])
    ;; Build mode: full tag generation from manifest
    (let [manifest  ((::manifest (meta assets-fn)))
          entry     (get manifest entry-name)]
      (when entry
        (let [imports   (imported-chunks manifest entry-name)
              css-file? (str/ends-with? (get entry "file" "") ".css")]
          (concat
           ;; 1. CSS from the entry itself
           (for [css (get entry "css")]
             [:link {:rel "stylesheet" :href (str "/" css)}])
           ;; 2. CSS from imported chunks
           (for [chunk imports
                 css   (get chunk "css")]
             [:link {:rel "stylesheet" :href (str "/" css)}])
           ;; 3. The entry itself
           (if css-file?
             (list [:link {:rel "stylesheet" :href (str "/" (get entry "file"))}])
             (list [:script {:type "module" :src (str "/" (get entry "file"))}]))
           ;; 4. Modulepreload for imported JS chunks
           (for [chunk imports
                 :let [file (get chunk "file")]
                 :when (and file (not (str/ends-with? file ".css")))]
             [:link {:rel "modulepreload" :href (str "/" file)}])))))))

(defn- tag->html [[tag attrs]]
  (case tag
    :link   (str "<link rel=\"" (:rel attrs) "\" href=\"" (:href attrs) "\" />")
    :script (str "<script type=\"" (:type attrs) "\" src=\"" (:src attrs) "\"></script>")))

(defn entry-html
  "Like entry-tags, but returns an HTML string instead of hiccup.
   Returns nil if entry-name is not found in the manifest."
  [assets-fn entry-name]
  (when-let [tags (entry-tags assets-fn entry-name)]
    (str/join "\n" (map tag->html tags))))

(defmethod ig/init-key ::assets [_ {:keys [manifest-path cache-manifest? vite]}]
  (let [vite-dev-server-url (:url vite)]
    (if vite-dev-server-url
      ;; Dev server mode: resolve assets to Vite dev server URLs
      (vary-meta (fn [asset-name]
                   (str vite-dev-server-url "/" asset-name))
                 assoc ::dev-server? true)
      ;; Build mode: resolve assets from manifest
      (do
        (when-not (io/resource manifest-path)
          (log/warn "Could not find the manifest on the classpath: " manifest-path))
        (let [read-manifest* (if cache-manifest?
                               (memoize #(read-manifest manifest-path))
                               #(read-manifest manifest-path))
              url-for (fn [asset-name]
                        (when-let [url (get-in (read-manifest*) [asset-name "file"])]
                          (str "/" url)))]
          (vary-meta url-for assoc ::manifest read-manifest*))))))

(defmethod ig/init-key ::vite-client-middleware [_ {:keys [vite]}]
  (let [vite-client-url (str (:url vite) "/@vite/client")
        tag (str "<script type=\"module\" src=\"" vite-client-url "\"></script>")]
    (fn [handler]
      (fn [request]
        (let [response (handler request)]
          (if (and (string? (:body response))
                   (some-> (get-in response [:headers "Content-Type"]
                                   (get-in response [:headers "content-type"]))
                           (str/includes? "text/html"))
                   (str/includes? (:body response) "<head"))
            (update response :body
                    #(str/replace-first % #"<head[^>]*>" (str "$0\n" tag)))
            response))))))

(defn init [{:keys [asset-resource-path asset-url-path context-key vite]
             :or {asset-resource-path ""
                  asset-url-path "/assets"
                  context-key ::assets
                  vite {:mode :build}}
             :as options}]

  (when-not (m/validate Options options)
    (let [errors (me/humanize (m/explain Options options))]
      (log/error (str "Invalid zodiac-assets options: " errors))
      (throw (ex-info "Invalid zodiac-assets options" {:validation-errors errors}))))

  (let [vite (when vite
               (:vite (m/decode Options {:vite vite} (mt/default-value-transformer
                                                      {::mt/add-optional-keys true}))))]
    (fn [config]
      (let [config (cond-> config
                     vite
                     (assoc ::npm-install (select-keys vite [:package-json-dir])
                            ::vite (assoc (select-keys vite [:config-file :package-json-dir
                                                             :mode :host :port])
                                          :__depends (ig/ref ::npm-install))))
            assets-opts (cond-> (select-keys options [:manifest-path :cache-manifest?])
                          vite (assoc :vite (ig/ref ::vite)))
            resource-handler (reitit.ring/create-resource-handler {:path asset-url-path
                                                                   :root asset-resource-path})]
        (cond-> config
          true
          (-> (assoc ::assets assets-opts)
              (assoc-in [::z/middleware :context context-key] (ig/ref ::assets))
              (update-in [::z/app :default-handlers] #(cons resource-handler %)))
          ;; In dev-server mode, inject @vite/client script into HTML responses
          (= (:mode vite) :dev-server)
          (-> (assoc ::vite-client-middleware {:vite (ig/ref ::vite)})
              (update-in [::z/app :user-middleware]
                         #(vec (cons (ig/ref ::vite-client-middleware) %)))))))))
