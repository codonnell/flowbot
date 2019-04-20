(ns flowbot.core
  (:require [flowbot.config :as config]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]

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
            flowbot.botc.command)
  (:gen-class))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (log/info "Starting system...")
  (ig/init (config/system)))
