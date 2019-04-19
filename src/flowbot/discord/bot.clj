(ns flowbot.discord.bot
  (:require [harmony.core :as discord]
            [harmony.rest :as rest]
            [manifold.bus :as bus]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defn command-name
  [content prefix]
  (-> content
      (str/split #"\s")
      first
      (subs (count prefix))))

(defmulti event-handler (fn [_ _ event] (:t event)))

(defmethod event-handler "MESSAGE_CREATE"
  [event-bus {:keys [prefix rest-client]} {:keys [d]}]
  (log/info "Received message" d)
  (let [{:keys [content] :as message} d]
    (when (str/starts-with? content prefix)
      (bus/publish! event-bus :command (assoc message :command (keyword (command-name content prefix)))))
    (bus/publish! event-bus :message message)))

(defmethod event-handler :default
  [event-bus bot event]
  nil)

(defmethod ig/init-key :discord/bot [_ {:keys [prefix token name event-bus]}]
  (let [bot (discord/connect! (discord/init-bot {:token token :prefix prefix :name name
                                                 :on-event (partial event-handler event-bus)}))]
    (log/info "Started bot" {:bot bot})
    bot))

(defmethod ig/halt-key! :discord/bot [_ bot]
  (discord/disconnect! bot))
