(ns zodiac.ext.assets
  (:require [babashka.fs :as fs]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.process :as process]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [reitit.ring]))

(create-ns 'zodiac.core)
(alias 'z 'zodiac.core)

(defn- capture-output [process namespace]
  (let [stdout (process/stdout process)
        stderr (process/stderr process)]
    (future
      ;; pipe stderr to the error log
      (->> (log/log-stream :error namespace)
           (.transferTo stderr)))
    (future
      ;; pipe stdout to the error log
      (->> (log/log-stream :info namespace)
           (.transferTo stdout)))
    ;; Return the process
    process))

(defmethod ig/init-key ::npm-install [_ {:keys [package-json-dir]}]
  (log/info "$ npm clean-install")
  (when-not package-json-dir
    (log/warn "No package.json directory provided."))
  (let [p (process/start {:dir package-json-dir}
                         "npm" "clean-install")
        p-ref (process/exit-ref p)]
    (capture-output p ::npm-install)
    @(process/exit-ref p) ;; wait for the process to finish
    p))

(defmethod ig/init-key ::vite [_ {:keys [config-file]}]
  (when-not (fs/which "npx")
    (log/warn "Could not find path to npx. Starting vite will probably fail."))
  (let [vite-cmd (cond-> ["npx" "vite" "build"]
                   config-file (concat ["--config" config-file]))
        _ (log/debug (str "Starting vite...\n") (str/join " " vite-cmd))
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
                                         (json/read-str))]
                    (when-let [url (get-in manifest [asset-name "file"])]
                      (str "/" url))))]
    (if cache-manifest?
      (memoize url-for)
      url-for)))

(defn init [{:keys [asset-resource-path asset-url-path build? context-key package-json-dir]
             :or {asset-resource-path ""
                  asset-url-path "/assets"
                  build? true
                  context-key ::assets}
             :as options}]
  (fn [config]
    (let [config (cond-> config
                   ;; ;; Start the ::vite component with zodiac
                   build? (assoc ::vite options))
          resource-handler (reitit.ring/create-resource-handler {:path asset-url-path
                                                                 :root asset-resource-path})]
      (-> config
          (assoc
           ;; Start the ::assets component with zodiac
           ::assets (assoc options :__depends (ig/ref ::npm-install))
           ::npm-install options)
          (assoc-in [::z/middleware :context context-key] (ig/ref ::assets))
          (update-in [::z/app :default-handlers]  #(cons resource-handler %))))))
