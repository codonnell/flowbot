(ns user
  (:require [flowbot.config :as config]
            [integrant.repl :as ig.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.repl.state :refer [system]]
            [cider-nrepl.main]
            [flowbot.mafia.data.game :as d.g]
            [flowbot.mafia.data.event :as d.e]
            ;; for integrant multimethods
            flowbot.data.postgres
            flowbot.event.bus
            flowbot.discord.bot
            flowbot.discord.registrar
            flowbot.discord.message-dispatcher
            flowbot.mafia.command
            flowbot.command.handler))

(ig.repl/set-prep! config/system)

(cider-nrepl.main/init ["cider.nrepl/cider-middleware"])

(defn conn [] (:postgres/connection system))

