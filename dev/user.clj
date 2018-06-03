(ns user
  (:require [flowbot.config :as config]
            [integrant.repl :as ig.repl]))

(ig.repl/set-prep! config/system)
