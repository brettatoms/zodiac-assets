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
            [reitit.ring]))

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
    [:package-json-dir :string]]))

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

(defmethod ig/init-key ::npm-install [_ {:keys [package-json-dir]}]
  (log/debug "$ npm clean-install")

  (when-not package-json-dir
    (log/warn "No package.json directory provided."))
  (let [p (process/start {:dir package-json-dir}
                         "npm" "clean-install")]
    (capture-output p ::npm-install)
    @(process/exit-ref p) ;; wait for the process to finish
    p))

(defmethod ig/init-key ::vite [_ {:keys [config-file]}]
  (when-not (fs/which "npx")
    (log/warn "Could not find path to npx. Starting vite will probably fail."))
  (let [vite-cmd (cond-> ["npx" "vite" "build"]
                   config-file (concat ["--config" config-file]))
        _ (log/debug (str "Starting vite...\n" (str/join " " vite-cmd)))
        p (apply process/start vite-cmd)]
    ;; capture-output returns the process
    (capture-output p ::vite)))

(defmethod ig/halt-key! ::vite [_ p]
  ;; Kill the vite process
  (log/debug "Stopping vite...\n")
  (.destroy p))

(defmethod ig/init-key ::assets [_ {:keys [manifest-path cache-manifest?]}]
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
      url-for)))

(defn init [{:keys [asset-resource-path asset-url-path build? context-key]
             :or {asset-resource-path ""
                  asset-url-path "/assets"
                  build? true
                  context-key ::assets}
             :as options}]

  (when-not (m/validate Options options)
    (let [errors (me/humanize (m/explain Options options))]
      (log/error (str "Invalid zodiac-assets options: " errors))
      (throw (ex-info "Invalid zodiac-assets options" {:validation-errors errors}))))

  (fn [config]
    (let [config (cond-> config
                   build?
                   (assoc ::vite options
                          ::npm-install options))
          ;; Install the dependencies and build the assets when Zodiac starts
          options (cond-> options
                    build?
                    (assoc :__depends (ig/ref ::npm-install)))
          resource-handler (reitit.ring/create-resource-handler {:path asset-url-path
                                                                 :root asset-resource-path})]
      (-> config
          (assoc ::assets options)
          (assoc-in [::z/middleware :context context-key] (ig/ref ::assets))
          (update-in [::z/app :default-handlers] #(cons resource-handler %))))))
