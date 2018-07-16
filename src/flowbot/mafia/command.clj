(ns flowbot.mafia.command
  (:require [flowbot.mafia.interceptor :as mafia.int]
            [flowbot.mafia.data.game :as data.game]
            [flowbot.mafia.data.event :as data.event]
            [flowbot.mafia.game :as game]
            [flowbot.data.postgres :as pg]
            [flowbot.registrar :as reg]
            [flowbot.discord.action :as discord.action]
            [flowbot.interceptor.registrar :as int.reg]
            [integrant.core :as ig]
            [clojure.tools.logging :as log]
            [datahike.api :as d]))

(def start-game-command
  {:name :start-game
   :interceptors [discord.action/reply-interceptor ::pg/inject-conn mafia.int/no-game-running]
   :handler-fn
   (int.reg/interceptor
    {:name ::start-game-handler
     :leave (fn [{:keys [event ::pg/conn] :as ctx}]
              (update ctx :effects assoc
                      ::start-game {::data.game/moderator-id (get-in event [:author :id])
                                    ::data.game/channel-id (get-in event [:channel :id])
                                    ::pg/conn conn}))})})

(def start-game-effect
  {:name ::start-game
   :f (fn [{::pg/keys [conn] ::data.game/keys [moderator-id channel-id]}]
        (try
          (let [send-message (:f (reg/get :effect ::discord.action/send-message))]
            (data.game/insert-mafia-game! conn {:moderator-id moderator-id :channel-id channel-id})
            (send-message [channel-id "A mafia game has started! Type !join to join."]))
          (catch Throwable e
            (log/error (ex-data e)))))})

(def end-registration-command
  {:name :end-registration
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/stage #{::data.game/registration})
                  (mafia.int/role #{::data.game/moderator})]
   :handler-fn
   (int.reg/interceptor
    {:name ::end-registration-handler
     :leave (fn [{::game/keys [game] :as ctx}]
              (update ctx :effects assoc
                      ::game/update-game (game/process-event
                                          game {::data.event/type ::data.event/end-registration})
                      ::discord.action/reply {:content "Mafia registration has been closed."}))})})

(def join-command
  {:name :join
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/stage #{::data.game/registration})
                  (mafia.int/role #{::data.game/not-player})]
   :handler-fn
   (int.reg/interceptor
    {:name ::join-handler
     :leave (fn [{::game/keys [game] :keys [event] :as ctx}]
              (update ctx :effects assoc
                      ::game/update-game (game/process-event
                                          game {::data.event/type ::data.event/join-game
                                                ::data.event/player-id (get-in event [:author :id])})
                      ::discord.action/reply {:content "You have joined the game."}))})})

(defmethod ig/init-key :mafia/command [_ _]
  (let [registry {:effect {::start-game start-game-effect}
                  :command {:start-game start-game-command
                            :end-registration end-registration-command
                            :join join-command}}]
    (reg/add-registry! registry)
    registry))

(defmethod ig/halt-key! :mafia/command [_ registry]
  (reg/remove-registry! registry))
