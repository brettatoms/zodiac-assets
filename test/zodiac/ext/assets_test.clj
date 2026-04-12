(ns zodiac.ext.assets-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [spy.core :as spy]
            [zodiac.ext.assets :as z.assets]))

;; Test project paths
(def test-project-dir "test/resources/test-project")
(def test-manifest-path "test-project/dist/.vite/manifest.json")

;; Test fixture - builds assets once before all tests
(defn build-test-assets [f]
  (let [test-dir test-project-dir]
    ;; Run npm install if node_modules doesn't exist
    (when-not (.exists (io/file test-dir "node_modules"))
      (let [{:keys [exit err]} (shell/sh "npm" "install" :dir test-dir)]
        (when-not (zero? exit)
          (throw (ex-info "npm install failed" {:exit exit :err err})))))
    ;; Run vite build to generate manifest
    (let [{:keys [exit err]} (shell/sh "npx" "vite" "build" :dir test-dir)]
      (when-not (zero? exit)
        (throw (ex-info "vite build failed" {:exit exit :err err}))))
    (f)))

(use-fixtures :once build-test-assets)

;; ============================================================================
;; Asset URL Resolution Tests
;; ============================================================================

(deftest url-for-basic-lookup-test
  (testing "url-for returns correct URL for JS entry"
    (let [url-for (ig/init-key ::z.assets/assets
                               {:manifest-path test-manifest-path
                                :cache-manifest? false})]
      (is (some? (url-for "src/app.js")))
      (is (re-matches #"/assets/app-[a-zA-Z0-9]+\.js" (url-for "src/app.js"))))))

(deftest url-for-css-entry-test
  (testing "url-for returns correct URL for CSS entry"
    (let [url-for (ig/init-key ::z.assets/assets
                               {:manifest-path test-manifest-path
                                :cache-manifest? false})]
      (is (some? (url-for "src/style.css")))
      (is (re-matches #"/assets/style-[a-zA-Z0-9]+\.css" (url-for "src/style.css"))))))

(deftest url-for-leading-slash-test
  (testing "url-for always returns URLs with leading slash"
    (let [url-for (ig/init-key ::z.assets/assets
                               {:manifest-path test-manifest-path
                                :cache-manifest? false})]
      (is (= \/ (first (url-for "src/app.js"))))
      (is (= \/ (first (url-for "src/style.css")))))))

(deftest url-for-missing-asset-test
  (testing "url-for returns nil for missing asset"
    (let [url-for (ig/init-key ::z.assets/assets
                               {:manifest-path test-manifest-path
                                :cache-manifest? false})]
      (is (nil? (url-for "nonexistent.js")))
      (is (nil? (url-for "src/missing.ts"))))))

(deftest url-for-nonexistent-manifest-test
  (testing "url-for returns nil when manifest doesn't exist"
    (let [url-for (ig/init-key ::z.assets/assets
                               {:manifest-path "nonexistent/.vite/manifest.json"
                                :cache-manifest? false})]
      (is (nil? (url-for "src/app.js"))))))

;; ============================================================================
;; Manifest Caching Tests
;; ============================================================================

(deftest url-for-caching-disabled-test
  (testing "with cache-manifest? false, manifest is re-read on each call"
    (let [url-for (ig/init-key ::z.assets/assets
                               {:manifest-path test-manifest-path
                                :cache-manifest? false})
          slurp-spy (spy/spy slurp)]
      (with-redefs [slurp slurp-spy]
        (url-for "src/app.js")
        (url-for "src/app.js")
        (url-for "src/app.js")
        ;; Each call should trigger a slurp
        (is (= 3 (count (spy/calls slurp-spy))))))))

(deftest url-for-caching-enabled-test
  (testing "with cache-manifest? true, manifest is read once and memoized"
    (let [url-for (ig/init-key ::z.assets/assets
                               {:manifest-path test-manifest-path
                                :cache-manifest? true})
          slurp-spy (spy/spy slurp)]
      (with-redefs [slurp slurp-spy]
        (url-for "src/app.js")
        (url-for "src/app.js")
        (url-for "src/app.js")
        ;; Only one slurp call due to memoization
        (is (= 1 (count (spy/calls slurp-spy))))))))

;; ============================================================================
;; Init Function Config Transformation Tests
;; ============================================================================

(deftest init-defaults-test
  (testing "init uses correct defaults"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"})
          config (config-fn {})]
      ;; vite defaults to {:mode :build}, so ::vite and ::npm-install should be present
      (is (contains? config ::z.assets/vite))
      (is (contains? config ::z.assets/npm-install))
      (is (contains? config ::z.assets/assets)))))

(deftest init-vite-nil-test
  (testing "with vite nil, ::vite and ::npm-install are not added"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite nil})
          config (config-fn {})]
      (is (not (contains? config ::z.assets/vite)))
      (is (not (contains? config ::z.assets/npm-install)))
      (is (contains? config ::z.assets/assets)))))

(deftest init-vite-build-test
  (testing "with vite {:mode :build}, ::vite and ::npm-install are added"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite {:mode :build
                                           :config-file "/path/to/vite.config.js"}})
          config (config-fn {})]
      (is (contains? config ::z.assets/vite))
      (is (contains? config ::z.assets/npm-install))
      ;; Check that assets refs vite
      (is (= (ig/ref ::z.assets/vite)
             (get-in config [::z.assets/assets :vite]))))))

(deftest init-vite-dev-server-test
  (testing "with vite {:mode :dev-server}, ::vite is configured for dev server"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite {:mode :dev-server
                                           :host "0.0.0.0"
                                           :port 3000}})
          config (config-fn {})]
      (is (contains? config ::z.assets/vite))
      (is (= :dev-server (get-in config [::z.assets/vite :mode])))
      (is (= "0.0.0.0" (get-in config [::z.assets/vite :host])))
      (is (= 3000 (get-in config [::z.assets/vite :port]))))))

(deftest init-vite-dev-server-injects-client-middleware-test
  (testing "dev-server mode adds ::vite-client-middleware and wires it as user-middleware"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite {:mode :dev-server}})
          config (config-fn {})]
      (is (contains? config ::z.assets/vite-client-middleware))
      (is (= (ig/ref ::z.assets/vite)
             (get-in config [::z.assets/vite-client-middleware :vite])))
      (is (some #(= % (ig/ref ::z.assets/vite-client-middleware))
                (get-in config [:zodiac.core/app :user-middleware]))))))

(deftest init-vite-build-no-client-middleware-test
  (testing "build mode does not add ::vite-client-middleware"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite {:mode :build}})
          config (config-fn {})]
      (is (not (contains? config ::z.assets/vite-client-middleware))))))

(deftest init-vite-empty-map-defaults-to-build-test
  (testing "empty vite map defaults to :mode :build"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite {}})
          config (config-fn {})]
      (is (contains? config ::z.assets/vite))
      (is (= :build (get-in config [::z.assets/vite :mode]))))))

(deftest init-vite-dev-server-defaults-test
  (testing "dev-server mode gets default host and port"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite {:mode :dev-server}})
          config (config-fn {})]
      (is (= :dev-server (get-in config [::z.assets/vite :mode])))
      (is (= "localhost" (get-in config [::z.assets/vite :host])))
      (is (= 5173 (get-in config [::z.assets/vite :port]))))))

(deftest init-vite-dev-server-partial-override-test
  (testing "dev-server mode with partial overrides keeps other defaults"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite {:mode :dev-server :port 3000}})
          config (config-fn {})]
      (is (= "localhost" (get-in config [::z.assets/vite :host])))
      (is (= 3000 (get-in config [::z.assets/vite :port]))))))

(deftest init-vite-build-no-host-port-test
  (testing "build mode does not get host or port"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite {:mode :build}})
          config (config-fn {})]
      (is (= :build (get-in config [::z.assets/vite :mode])))
      (is (nil? (get-in config [::z.assets/vite :host])))
      (is (nil? (get-in config [::z.assets/vite :port]))))))

(deftest init-custom-context-key-test
  (testing "custom context-key is used in middleware context"
    (let [custom-key :my-app/assets
          config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite nil
                                    :context-key custom-key})
          config (config-fn {})]
      ;; The context key should be used in the middleware context
      (is (= (ig/ref ::z.assets/assets)
             (get-in config [:zodiac.core/middleware :context custom-key]))))))

(deftest init-resource-handler-added-test
  (testing "resource handler is prepended to default-handlers"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite nil})
          existing-handler (fn [_] nil)
          config (config-fn {:zodiac.core/app {:default-handlers [existing-handler]}})]
      ;; Should have 2 handlers now (resource handler + existing)
      (is (= 2 (count (get-in config [:zodiac.core/app :default-handlers]))))
      ;; Existing handler should still be there
      (is (some #(= % existing-handler)
                (get-in config [:zodiac.core/app :default-handlers]))))))

;; ============================================================================
;; Path Configuration Edge Cases
;; ============================================================================

;; --- manifest-path variations ---

(deftest manifest-path-leading-slash-test
  (testing "manifest-path with leading slash doesn't find resource"
    ;; io/resource doesn't typically work with leading slashes
    (let [url-for (ig/init-key ::z.assets/assets
                               {:manifest-path "/test-project/dist/.vite/manifest.json"
                                :cache-manifest? false})]
      ;; Should return nil because leading slash breaks io/resource lookup
      (is (nil? (url-for "src/app.js"))))))

(deftest manifest-path-nested-deep-test
  (testing "manifest-path with deeply nested path works"
    (let [url-for (ig/init-key ::z.assets/assets
                               {:manifest-path test-manifest-path
                                :cache-manifest? false})]
      ;; Our test manifest is already nested: test-project/dist/.vite/manifest.json
      (is (some? (url-for "src/app.js"))))))

(deftest manifest-path-empty-string-test
  (testing "manifest-path as empty string throws when looking up assets"
    ;; Empty string causes io/resource to return a directory URL,
    ;; which throws when slurped. This is a gotcha to document.
    (let [url-for (ig/init-key ::z.assets/assets
                               {:manifest-path ""
                                :cache-manifest? false})]
      (is (thrown? java.io.FileNotFoundException (url-for "src/app.js"))))))

(deftest manifest-path-nil-test
  (testing "manifest-path as nil throws NPE during initialization"
    ;; nil manifest-path causes NPE in io/resource during init-key.
    ;; This is a gotcha - manifest-path should be a valid string.
    (is (thrown? NullPointerException
                 (ig/init-key ::z.assets/assets
                              {:manifest-path nil
                               :cache-manifest? false})))))

(deftest manifest-path-with-double-slashes-test
  (testing "manifest-path with double slashes works (io/resource normalizes)"
    ;; Surprisingly, io/resource handles double slashes by normalizing them
    (let [url-for (ig/init-key ::z.assets/assets
                               {:manifest-path "test-project//dist/.vite/manifest.json"
                                :cache-manifest? false})]
      (is (some? (url-for "src/app.js"))))))

;; --- asset-url-path variations ---

(deftest asset-url-path-without-leading-slash-test
  (testing "asset-url-path without leading slash is passed to resource handler"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite nil
                                    :asset-url-path "static"})
          config (config-fn {})]
      ;; Config should be created without error
      (is (contains? config ::z.assets/assets)))))

(deftest asset-url-path-nested-test
  (testing "asset-url-path with nested path works"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite nil
                                    :asset-url-path "/public/assets"})
          config (config-fn {})]
      (is (contains? config ::z.assets/assets)))))

(deftest asset-url-path-root-test
  (testing "asset-url-path as root path works"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite nil
                                    :asset-url-path "/"})
          config (config-fn {})]
      (is (contains? config ::z.assets/assets)))))

(deftest asset-url-path-with-trailing-slash-test
  (testing "asset-url-path with trailing slash works"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite nil
                                    :asset-url-path "/assets/"})
          config (config-fn {})]
      (is (contains? config ::z.assets/assets)))))

;; --- asset-resource-path variations ---

(deftest asset-resource-path-empty-string-test
  (testing "asset-resource-path as empty string (default) works"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite nil
                                    :asset-resource-path ""})
          config (config-fn {})]
      (is (contains? config ::z.assets/assets)))))

(deftest asset-resource-path-nested-test
  (testing "asset-resource-path with nested path works"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite nil
                                    :asset-resource-path "public/build/assets"})
          config (config-fn {})]
      (is (contains? config ::z.assets/assets)))))

(deftest asset-resource-path-with-leading-slash-test
  (testing "asset-resource-path with leading slash is passed through"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite nil
                                    :asset-resource-path "/myapp/assets"})
          config (config-fn {})]
      (is (contains? config ::z.assets/assets)))))

;; --- config-file variations ---

(deftest config-file-absent-test
  (testing "config-file absent doesn't add --config flag to vite command"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite {}})
          config (config-fn {})]
      ;; vite config should not have config-file
      (is (nil? (get-in config [::z.assets/vite :config-file]))))))

(deftest config-file-absolute-path-test
  (testing "config-file with absolute path is stored in config"
    (let [abs-path "/home/user/project/vite.config.js"
          config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite {:config-file abs-path}})
          config (config-fn {})]
      (is (= abs-path (get-in config [::z.assets/vite :config-file]))))))

(deftest config-file-relative-path-test
  (testing "config-file with relative path is stored in config"
    (let [rel-path "./vite.config.js"
          config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :vite {:config-file rel-path}})
          config (config-fn {})]
      (is (= rel-path (get-in config [::z.assets/vite :config-file]))))))

;; --- Combined path scenarios ---

(deftest paths-all-options-configured-test
  (testing "all path options can be configured together"
    (let [config-fn (z.assets/init {:manifest-path "myapp/build/.vite/manifest.json"
                                    :asset-resource-path "myapp/build/assets"
                                    :asset-url-path "/static/assets"
                                    :vite {:config-file "/project/vite.config.js"}})
          config (config-fn {})]
      (is (contains? config ::z.assets/assets))
      (is (contains? config ::z.assets/vite))
      (is (= "myapp/build/.vite/manifest.json"
             (get-in config [::z.assets/assets :manifest-path])))
      (is (= "/project/vite.config.js"
             (get-in config [::z.assets/vite :config-file]))))))

(deftest paths-minimal-config-test
  (testing "minimal config with only required manifest-path works"
    (let [config-fn (z.assets/init {:manifest-path "app/.vite/manifest.json"
                                    :vite nil})
          config (config-fn {})]
      (is (contains? config ::z.assets/assets))
      ;; Should use defaults for other paths
      (is (some? (get-in config [:zodiac.core/app :default-handlers]))))))

;; ============================================================================
;; Options Validation Tests
;; ============================================================================

(deftest options-validation-manifest-path-empty-test
  (testing "empty manifest-path fails validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid zodiac-assets options"
                          (z.assets/init {:manifest-path ""
                                          :vite nil})))))

(deftest options-validation-manifest-path-wrong-type-test
  (testing "non-string manifest-path fails validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid zodiac-assets options"
                          (z.assets/init {:manifest-path 123
                                          :vite nil})))))

(deftest options-validation-vite-mode-wrong-type-test
  (testing "invalid vite mode fails validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid zodiac-assets options"
                          (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                          :vite {:mode "build"}})))))

(deftest options-validation-context-key-wrong-type-test
  (testing "non-keyword context-key fails validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid zodiac-assets options"
                          (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                          :vite nil
                                          :context-key "my-assets"})))))

(deftest options-validation-cache-manifest-wrong-type-test
  (testing "non-boolean cache-manifest? fails validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid zodiac-assets options"
                          (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                          :vite nil
                                          :cache-manifest? "false"})))))

(deftest options-validation-valid-options-test
  (testing "valid options pass validation"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :asset-resource-path "test/assets"
                                    :asset-url-path "/static"
                                    :vite {:mode :build
                                           :config-file "/path/to/vite.config.js"}
                                    :cache-manifest? true
                                    :context-key :my-app/assets})]
      (is (fn? config-fn)))))

(deftest options-validation-error-contains-details-test
  (testing "validation error contains details about what failed"
    (try
      (z.assets/init {:manifest-path 123
                      :vite {:mode "not-a-keyword"}})
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [errors (:validation-errors (ex-data e))]
          (is (some? errors))
          (is (contains? errors :manifest-path))
          (is (contains? errors :vite)))))))

;; ============================================================================
;; entry-tags / entry-html Tests
;; ============================================================================

(defn- make-assets-fn
  "Create an assets fn from ::assets init-key for testing."
  ([] (make-assets-fn {}))
  ([opts]
   (ig/init-key ::z.assets/assets
                (merge {:manifest-path test-manifest-path
                        :cache-manifest? false}
                       opts))))

(deftest entry-tags-simple-js-test
  (testing "simple JS entry with no CSS or imports"
    (let [assets (make-assets-fn)
          tags (z.assets/entry-tags assets "src/app.js")]
      ;; Should have exactly one tag: the script
      (is (= 1 (count tags)))
      (let [[tag-name attrs] (first tags)]
        (is (= :script tag-name))
        (is (= "module" (:type attrs)))
        (is (re-matches #"/assets/app-[a-zA-Z0-9]+\.js" (:src attrs)))))))

(deftest entry-tags-css-entry-test
  (testing "CSS entry emits a link stylesheet, not a script"
    (let [assets (make-assets-fn)
          tags (z.assets/entry-tags assets "src/style.css")]
      (is (= 1 (count tags)))
      (let [[tag-name attrs] (first tags)]
        (is (= :link tag-name))
        (is (= "stylesheet" (:rel attrs)))
        (is (re-matches #"/assets/style-[a-zA-Z0-9]+\.css" (:href attrs)))))))

(deftest entry-tags-js-with-css-test
  (testing "JS entry with own CSS and imported chunk CSS"
    (let [assets (make-assets-fn)
          tags (vec (z.assets/entry-tags assets "src/app-with-css.js"))]
      ;; Should have:
      ;; 1. link stylesheet for app-with-css's own CSS
      ;; 2. link stylesheet for shared chunk's CSS
      ;; 3. script for app-with-css.js
      ;; 4. modulepreload for shared.js
      (is (= 4 (count tags)))

      ;; First two should be link stylesheets
      (is (= :link (first (nth tags 0))))
      (is (= "stylesheet" (:rel (second (nth tags 0)))))
      (is (re-matches #"/assets/app-with-css-[a-zA-Z0-9]+\.css"
                      (:href (second (nth tags 0)))))

      (is (= :link (first (nth tags 1))))
      (is (= "stylesheet" (:rel (second (nth tags 1)))))
      (is (re-matches #"/assets/shared-[a-zA-Z0-9]+\.css"
                      (:href (second (nth tags 1)))))

      ;; Third should be the script
      (is (= :script (first (nth tags 2))))
      (is (= "module" (:type (second (nth tags 2)))))

      ;; Fourth should be modulepreload for shared chunk
      (is (= :link (first (nth tags 3))))
      (is (= "modulepreload" (:rel (second (nth tags 3)))))
      (is (re-matches #"/assets/shared-[a-zA-Z0-9]+\.js"
                      (:href (second (nth tags 3))))))))

(deftest entry-tags-imports-only-test
  (testing "JS entry with no own CSS but imports chunk that has CSS"
    (let [assets (make-assets-fn)
          tags (vec (z.assets/entry-tags assets "src/page.js"))]
      ;; Should have:
      ;; 1. link stylesheet for shared chunk's CSS (from imports)
      ;; 2. script for page.js
      ;; 3. modulepreload for shared.js
      (is (= 3 (count tags)))

      ;; First: shared CSS
      (is (= :link (first (nth tags 0))))
      (is (= "stylesheet" (:rel (second (nth tags 0)))))
      (is (re-matches #"/assets/shared-[a-zA-Z0-9]+\.css"
                      (:href (second (nth tags 0)))))

      ;; Second: the script
      (is (= :script (first (nth tags 1))))

      ;; Third: modulepreload
      (is (= :link (first (nth tags 2))))
      (is (= "modulepreload" (:rel (second (nth tags 2))))))))

(deftest entry-tags-missing-entry-test
  (testing "missing entry returns nil"
    (let [assets (make-assets-fn)
          tags (z.assets/entry-tags assets "nonexistent.js")]
      (is (nil? tags)))))

(deftest entry-tags-dev-server-test
  (testing "in dev-server mode, only emits script tag"
    (let [assets (ig/init-key ::z.assets/assets
                              {:manifest-path test-manifest-path
                               :cache-manifest? false
                               :vite {:url "http://localhost:5173"}})
          tags (vec (z.assets/entry-tags assets "src/app-with-css.js"))]
      ;; Dev mode: just one script tag, no CSS links or modulepreload
      (is (= 1 (count tags)))
      (let [[tag-name attrs] (first tags)]
        (is (= :script tag-name))
        (is (= "module" (:type attrs)))
        (is (= "http://localhost:5173/src/app-with-css.js" (:src attrs)))))))

(deftest entry-tags-css-order-test
  (testing "entry's own CSS comes before imported chunk CSS"
    (let [assets (make-assets-fn)
          tags (vec (z.assets/entry-tags assets "src/app-with-css.js"))
          css-tags (filterv #(and (= :link (first %))
                                  (= "stylesheet" (:rel (second %))))
                            tags)
          css-hrefs (mapv #(:href (second %)) css-tags)]
      ;; app-with-css's own CSS should come before shared's CSS
      (is (= 2 (count css-hrefs)))
      (is (re-matches #"/assets/app-with-css-.*\.css" (first css-hrefs)))
      (is (re-matches #"/assets/shared-.*\.css" (second css-hrefs))))))

;; ============================================================================
;; entry-html Tests
;; ============================================================================

(deftest entry-html-simple-js-test
  (testing "simple JS entry produces a script tag"
    (let [assets (make-assets-fn)
          html (z.assets/entry-html assets "src/app.js")]
      (is (re-matches #"<script type=\"module\" src=\"/assets/app-[a-zA-Z0-9]+\.js\"></script>"
                      html)))))

(deftest entry-html-css-entry-test
  (testing "CSS entry produces a link tag"
    (let [assets (make-assets-fn)
          html (z.assets/entry-html assets "src/style.css")]
      (is (re-matches #"<link rel=\"stylesheet\" href=\"/assets/style-[a-zA-Z0-9]+\.css\" />"
                      html)))))

(deftest entry-html-js-with-css-test
  (testing "JS entry with CSS produces multi-line HTML in correct order"
    (let [assets (make-assets-fn)
          html (z.assets/entry-html assets "src/app-with-css.js")
          lines (str/split-lines html)]
      (is (= 4 (count lines)))
      (is (re-matches #"<link rel=\"stylesheet\" href=\"/assets/app-with-css-.*\.css\" />"
                      (nth lines 0)))
      (is (re-matches #"<link rel=\"stylesheet\" href=\"/assets/shared-.*\.css\" />"
                      (nth lines 1)))
      (is (re-matches #"<script type=\"module\" src=\"/assets/app-with-css-.*\.js\"></script>"
                      (nth lines 2)))
      (is (re-matches #"<link rel=\"modulepreload\" href=\"/assets/shared-.*\.js\" />"
                      (nth lines 3))))))

(deftest entry-html-missing-entry-test
  (testing "missing entry returns nil"
    (let [assets (make-assets-fn)]
      (is (nil? (z.assets/entry-html assets "nonexistent.js"))))))

(deftest entry-html-dev-server-test
  (testing "dev-server mode produces a single script tag"
    (let [assets (ig/init-key ::z.assets/assets
                              {:manifest-path test-manifest-path
                               :cache-manifest? false
                               :vite {:url "http://localhost:5173"}})
          html (z.assets/entry-html assets "src/app-with-css.js")]
      (is (= "<script type=\"module\" src=\"http://localhost:5173/src/app-with-css.js\"></script>"
             html)))))
