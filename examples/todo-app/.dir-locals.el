;;; -*- no-byte-compile: t -*-
((clojure-mode . ((cider-clojure-cli-aliases . ":dev:test")
                  (clojure-preferred-build-tool . clojure-cli)
                  (eval . (progn
                            (make-local-variable 'cider-jack-in-nrepl-middlewares)
                            (add-to-list 'cider-jack-in-nrepl-middlewares "ring.hot-reload.nrepl/wrap-hot-reload-nrepl"))))))
