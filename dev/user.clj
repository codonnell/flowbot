(ns user
  (:require [flowbot.config :as config]
            [integrant.repl :as ig.repl :refer [clear go halt prep init reset reset-all]]
            ;; for integrant multimethods
            flowbot.data.postgres))

(ig.repl/set-prep! config/system)
