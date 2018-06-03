(ns flowbot.discord.bot
  (:require [discord.bot :as bot]
            [discord.types :as types]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defmethod ig/init-key :discord/bot [_ {:keys [prefix token name]}]
  (let [bot (bot/create-bot name [] prefix (types/->SimpleAuth token))]
    (bot/defhandler printer [prefix client message]
      (log/info message))
    (log/info {:bot bot})
    bot))

(defmethod ig/halt-key! :discord/bot [_ bot]
  (.close bot))
