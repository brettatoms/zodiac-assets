(ns zodiac.ext.assets-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
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
      ;; build? defaults to true, so ::vite and ::npm-install should be present
      (is (contains? config ::z.assets/vite))
      (is (contains? config ::z.assets/npm-install))
      (is (contains? config ::z.assets/assets)))))

(deftest init-build-false-test
  (testing "with build? false, ::vite and ::npm-install are not added"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :build? false})
          config (config-fn {})]
      (is (not (contains? config ::z.assets/vite)))
      (is (not (contains? config ::z.assets/npm-install)))
      (is (contains? config ::z.assets/assets)))))

(deftest init-build-true-test
  (testing "with build? true, ::vite and ::npm-install are added"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :build? true
                                    :config-file "/path/to/vite.config.js"})
          config (config-fn {})]
      (is (contains? config ::z.assets/vite))
      (is (contains? config ::z.assets/npm-install))
      ;; Check that vite depends on npm-install
      (is (= (ig/ref ::z.assets/npm-install)
             (get-in config [::z.assets/assets :__depends]))))))

(deftest init-custom-context-key-test
  (testing "custom context-key is used in middleware context"
    (let [custom-key :my-app/assets
          config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :build? false
                                    :context-key custom-key})
          config (config-fn {})]
      ;; The context key should be used in the middleware context
      (is (= (ig/ref ::z.assets/assets)
             (get-in config [:zodiac.core/middleware :context custom-key]))))))

(deftest init-resource-handler-added-test
  (testing "resource handler is prepended to default-handlers"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :build? false})
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
                                    :build? false
                                    :asset-url-path "static"})
          config (config-fn {})]
      ;; Config should be created without error
      (is (contains? config ::z.assets/assets)))))

(deftest asset-url-path-nested-test
  (testing "asset-url-path with nested path works"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :build? false
                                    :asset-url-path "/public/assets"})
          config (config-fn {})]
      (is (contains? config ::z.assets/assets)))))

(deftest asset-url-path-root-test
  (testing "asset-url-path as root path works"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :build? false
                                    :asset-url-path "/"})
          config (config-fn {})]
      (is (contains? config ::z.assets/assets)))))

(deftest asset-url-path-with-trailing-slash-test
  (testing "asset-url-path with trailing slash works"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :build? false
                                    :asset-url-path "/assets/"})
          config (config-fn {})]
      (is (contains? config ::z.assets/assets)))))

;; --- asset-resource-path variations ---

(deftest asset-resource-path-empty-string-test
  (testing "asset-resource-path as empty string (default) works"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :build? false
                                    :asset-resource-path ""})
          config (config-fn {})]
      (is (contains? config ::z.assets/assets)))))

(deftest asset-resource-path-nested-test
  (testing "asset-resource-path with nested path works"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :build? false
                                    :asset-resource-path "public/build/assets"})
          config (config-fn {})]
      (is (contains? config ::z.assets/assets)))))

(deftest asset-resource-path-with-leading-slash-test
  (testing "asset-resource-path with leading slash is passed through"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :build? false
                                    :asset-resource-path "/myapp/assets"})
          config (config-fn {})]
      (is (contains? config ::z.assets/assets)))))

;; --- config-file variations ---

(deftest config-file-nil-test
  (testing "config-file as nil doesn't add --config flag to vite command"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :build? true
                                    :config-file nil})
          config (config-fn {})]
      ;; vite config should have nil config-file
      (is (nil? (get-in config [::z.assets/vite :config-file]))))))

(deftest config-file-absolute-path-test
  (testing "config-file with absolute path is stored in config"
    (let [abs-path "/home/user/project/vite.config.js"
          config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :build? true
                                    :config-file abs-path})
          config (config-fn {})]
      (is (= abs-path (get-in config [::z.assets/vite :config-file]))))))

(deftest config-file-relative-path-test
  (testing "config-file with relative path is stored in config"
    (let [rel-path "./vite.config.js"
          config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :build? true
                                    :config-file rel-path})
          config (config-fn {})]
      (is (= rel-path (get-in config [::z.assets/vite :config-file]))))))

;; --- Combined path scenarios ---

(deftest paths-all-options-configured-test
  (testing "all path options can be configured together"
    (let [config-fn (z.assets/init {:manifest-path "myapp/build/.vite/manifest.json"
                                    :asset-resource-path "myapp/build/assets"
                                    :asset-url-path "/static/assets"
                                    :config-file "/project/vite.config.js"
                                    :build? true})
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
                                    :build? false})
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
                                          :build? false})))))

(deftest options-validation-manifest-path-wrong-type-test
  (testing "non-string manifest-path fails validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid zodiac-assets options"
                          (z.assets/init {:manifest-path 123
                                          :build? false})))))

(deftest options-validation-build-wrong-type-test
  (testing "non-boolean build? fails validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid zodiac-assets options"
                          (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                          :build? "true"})))))

(deftest options-validation-context-key-wrong-type-test
  (testing "non-keyword context-key fails validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid zodiac-assets options"
                          (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                          :build? false
                                          :context-key "my-assets"})))))

(deftest options-validation-cache-manifest-wrong-type-test
  (testing "non-boolean cache-manifest? fails validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid zodiac-assets options"
                          (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                          :build? false
                                          :cache-manifest? "false"})))))

(deftest options-validation-valid-options-test
  (testing "valid options pass validation"
    (let [config-fn (z.assets/init {:manifest-path "test/.vite/manifest.json"
                                    :asset-resource-path "test/assets"
                                    :asset-url-path "/static"
                                    :config-file "/path/to/vite.config.js"
                                    :build? true
                                    :cache-manifest? true
                                    :context-key :my-app/assets})]
      (is (fn? config-fn)))))

(deftest options-validation-error-contains-details-test
  (testing "validation error contains details about what failed"
    (try
      (z.assets/init {:manifest-path 123
                      :build? "not-a-boolean"})
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [errors (:validation-errors (ex-data e))]
          (is (some? errors))
          (is (contains? errors :manifest-path))
          (is (contains? errors :build?)))))))
