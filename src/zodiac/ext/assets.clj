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

(defmethod ig/init-key ::vite [_ {:keys [config-file]}]
  (when-not (fs/which "npx")
    (log/warn "Could not find path to npx. Starting vite will probably fail."))

  (let [vite-cmd (cond-> ["npx" "vite" "build"]
                   config-file (concat ["--config" config-file]))
        _ (log/debug (str "Starting vite...\n") (str/join " " vite-cmd))
        p (apply process/start vite-cmd)
        stdout (process/stdout p)
        stderr (process/stderr p)]
    (future
      ;; pipe stderr to the error log
      (->> (log/log-stream :error ::vite)
           (.transferTo stderr)))
    (future
      ;; pipe stdout to the error log
      (->> (log/log-stream :info ::vite)
           (.transferTo stdout)))
    p))

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

(defn init [{:keys [asset-resource-path asset-url-path build? context-key]
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
           ::assets options)
          (assoc-in [::z/middleware :context context-key] (ig/ref ::assets))
          (update-in [::z/app :default-handlers]  #(cons resource-handler %))))))
