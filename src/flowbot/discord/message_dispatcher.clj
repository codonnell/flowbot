(ns flowbot.discord.message-dispatcher
  (:require [integrant.core :as ig]
            [manifold.bus :as bus]
            [discord.bot :as bot]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defn command-name
  [content prefix]
  (-> content
      (str/split #"\s")
      first
      (subs (count prefix))))

(defmethod ig/init-key :discord/message-dispatcher [_ {:keys [event-bus bot]}]
  (let [dispatcher (fn dispatch-message [prefix client {:keys [content] :as message}]
                     (log/info "Received message" message)
                     (when (str/starts-with? content prefix)
                       (bus/publish! event-bus :command (assoc message :command (keyword (command-name content prefix)))))
                     (bus/publish! event-bus :message message))]
    (bot/add-handler! dispatcher)
    dispatcher))

(defmethod ig/halt-key! :discord/message-dispatcher [_ dispatch-fn]
  (swap! bot/message-handlers #(into [] (remove #{dispatch-fn}) %)))
