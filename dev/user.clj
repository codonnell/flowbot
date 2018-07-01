(ns user
  (:require [flowbot.config :as config]
            [integrant.repl :as ig.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.repl.state :refer [system]]
            [cider-nrepl.main]
            ;; for integrant multimethods
            flowbot.data.postgres
            flowbot.data.datahike
            flowbot.event.bus
            flowbot.discord.bot
            flowbot.discord.registrar
            flowbot.discord.message-dispatcher
            flowbot.command.handler
            flowbot.mafia.command))

(ig.repl/set-prep! config/system)

(cider-nrepl.main/init ["cider.nrepl/cider-middleware"])

