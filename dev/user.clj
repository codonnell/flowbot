(ns user
  (:require [flowbot.config :as config]
            [integrant.repl :as ig.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.repl.state :refer [system]]
            ;; for integrant multimethods
            flowbot.data.postgres
            flowbot.discord.bot
            flowbot.discord.message-bus
            flowbot.interceptor.registrar))

(ig.repl/set-prep! config/system)
