(ns user
  (:require [flowbot.config :as config]
            [integrant.repl :as ig.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.repl.state :refer [system]]
            [cider-nrepl.main]
            [flowbot.mafia.data.game :as d.g]
            [flowbot.mafia.data.event :as d.e]
            [clojure.repl :refer :all]
            ;; For integrant multimethods
            flowbot.data.postgres
            flowbot.event.bus
            flowbot.discord.bot
            flowbot.discord.registrar
            flowbot.plugin
            flowbot.command.handler
            flowbot.command.custom
            ;; For plugin multimethods
            flowbot.mafia.command
            flowbot.botc.command))

(ig.repl/set-prep! config/system)

(cider-nrepl.main/init ["cider.nrepl/cider-middleware"])

(defn conn [] (:postgres/connection system))

