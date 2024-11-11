(ns todo
  (:gen-class)
  (:require [babashka.fs :as fs]
            [clojure.data.json :as json]
            [integrant.core :as ig]
            [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
            [taoensso.telemere :as t]
            [taoensso.telemere.tools-logging :as tt]
            [zodiac.core :as z]
            [zodiac.ext.assets :as assets]))

;; Logging setup
(add-tap println)
(tt/tools-logging->telemere!) ;; send tools.logging to telemere
(t/set-min-level! :debug)
(t/set-min-level! nil "org.eclipse.jetty.*" :warn)

(defn form []
  [:form {:hx-post (z/url-for :root)
          :hx-target "#todo-list"
          :x-on:htmx:after-request.camel "$event.detail.elt.reset()"
          :class "flex flex-row w-1/2 mb-6"}
   [:input {:type "hidden"
            :name "__anti-forgery-token"
            :value (force *anti-forgery-token*)}]
   [:input {:type "text"
            :name "title"
            :class "flex-1 mr-4"}]
   [:button {:type "submit"
             :class "hover:text-gray-700 border border-black hover:border-gray-600 p-2"} "Add"]])

(defn todo-list [db]
  [:ui {:id "todo-list"
        :class "col"}
   (for [item db]
     [:li {:class "flex flex-row border-b-gray-200 border-b py-2"}
      [:span {:class "mr-8 flex-1"}
       (:title item)]
      [:button {:hx-delete (z/url-for :root)
                :hx-headers (json/write-str {:X-CSRF-Token (force *anti-forgery-token*)})
                :hx-target "#todo-list"
                :hx-confirm "Are you sure?"
                :class "text-red-500"
                :name "id"
                :value (:id item)}
       "Delete"]])])

(defn render [& {:keys [assets db]}]
  [:html
   [:head
    [:meta {:name "viewport"
            :content "width=device-width, initial-scale=1"}]
    [:style "[x-cloak] {display: none !important;}"]
    [:link {:rel "stylesheet"
            :href (assets "src/todo.css")}]]
   [:body {:hx-ext "alpine-morph"
           :hx-swap "morph"
           :x-data ""
           :x-cloak true}
    [:div {:class "grid grid-cols-6 gap-4 mt-20"}
       [:div {:class "col-start-2 col-span-4"}
      [:h1 {:class "text-xl mb-2"} "Todo"]
      (form)
      (todo-list @db)]]
     [:script {:src (assets "src/todo.ts")
               :defer true}]]])

(defn handler [{:keys [::z/context request-method form-params]}]
  (let [{:keys [assets db]} context]
    (case request-method
      :get (render :assets assets
                   :db db)

      :post (let [title (get form-params "title")]
              (swap! db conj {:id (rand-int 10000)
                              :title title})
              (todo-list @db))

      :delete (let [id (-> form-params (get "id") (parse-long))]
                (swap! db (partial remove #(= (:id %) id)))
                (todo-list @db)))))

(defn routes []
  ["/" {:name :root
        :handler #'handler}])

(defmethod ig/init-key ::db [_ _]
  (atom []))

(defmethod ig/init-key ::zodiac [_ options]
  (z/start options))

(defmethod ig/halt-key! ::zodiac [_ system]
  (z/stop system))

(defn -main [& _]
  (let [project-root (System/getenv "PWD")
        assets-ext (assets/init {;; The config file is used by the vite command
                                 ;; so it needs to be an absolute path on the
                                 ;; filesystem, e.g. not in a jar.
                                 :config-file (str (fs/path project-root "vite.config.js"))
                                 ;; The manifest path is the relative resource
                                 ;; path to the output manifest file. This value doesn't override the build
                                 ;; time value for the output path of the manifest file.
                                 :manifest-path  "todo/.vite/manifest.json"
                                 :asset-resource-path "todo/assets"})
        system-config {::db {}
                       ::zodiac {:extensions [assets-ext]
                                 :routes #'routes
                                 :request-context {:db (ig/ref ::db)}
                                 :reload-per-request? true}}]
    (ig/load-namespaces system-config)
    (ig/init system-config)))

(comment
  (def ^:dynamic *system* nil)

  ;; Start
  (alter-var-root #'*system* -main)

  ;; Stop
  (ig/halt! *system*)

  ;; Restart
  (do
    (when *system*
      (ig/halt! *system*))
    (alter-var-root #'*system* -main))
  ())
