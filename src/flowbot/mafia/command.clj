(ns flowbot.mafia.command
  (:require [flowbot.mafia.interceptor :as mafia.int]
            [flowbot.mafia.data.game :as data.game]
            [flowbot.data.datahike :as dh]
            [flowbot.registrar :as reg]
            [flowbot.discord.action :as discord.action]
            [flowbot.interceptor.registrar :as int.reg]
            [integrant.core :as ig]
            [clojure.tools.logging :as log]
            [datahike.api :as d]))

(def start-game-command
  {:name :start-game
   :interceptors [discord.action/reply-interceptor ::dh/inject-conn mafia.int/no-game-running]
   :handler-fn
   (int.reg/interceptor
    {:name ::start-game-handler
     :enter (fn [{:keys [event ::dh/conn] :as ctx}]
              (update ctx :effects assoc
                      ::start-game {::data.game/moderator-id (get-in event [:author :id])
                                    ::data.game/channel-id (get-in event [:channel :id])
                                    ::dh/conn conn}))})})

(def start-game-effect
  {:name ::start-game
   :f (fn [{:keys [::dh/conn ::data.game/moderator-id ::data.game/channel-id]}]
        (try
          (let [send-message (:f (reg/get :effect ::discord.action/send-message))]
            @(d/transact conn (dh/decorate-tx
                               [{::data.game/channel-id channel-id
                                 ::data.game/moderator-id moderator-id
                                 ::data.game/finished? false}]))
            (send-message [channel-id "A mafia game has started! Type !join to join."]))
          (catch Throwable e
            (log/error (ex-data e)))))})

(defmethod ig/init-key :mafia/command [_ _]
  (let [registry {:effect {::start-game start-game-effect}
                  :command {:start-game start-game-command}}]
    (reg/add-registry! registry)
    registry))

(defmethod ig/halt-key! :mafia/command [_ registry]
  (reg/remove-registry! registry))
