(ns flowbot.discord.message-bus
  (:require [integrant.core :as ig]
            [manifold.bus :as bus]
            [discord.bot :as bot]
            [clojure.string :as str]))

(defmethod ig/init-key :discord/message-bus [_ {:keys [bot]}]
  (let [bus (bus/event-bus)]
    (bot/defhandler message-bus-consumer [prefix client {:keys [content] :as message}]
      (when (str/starts-with? content prefix)
        (let [command (-> content
                          (str/split #"\s")
                          first
                          (substr (count prefix))
                          keyword)]
          (bus/publish! bus command message)))
      (bus/publish! bus ::all message))
    bus))
