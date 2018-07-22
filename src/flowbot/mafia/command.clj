(ns flowbot.mafia.command
  (:require [flowbot.mafia.interceptor :as mafia.int]
            [flowbot.mafia.data.game :as data.game]
            [flowbot.mafia.data.event :as data.event]
            [flowbot.mafia.game :as game]
            [flowbot.data.postgres :as pg]
            [flowbot.registrar :as reg]
            [flowbot.discord.action :as discord.action]
            [flowbot.interceptor.registrar :as int.reg]
            [flowbot.util :as util]
            [integrant.core :as ig]
            [clojure.tools.logging :as log]))

(def start-game-command
  {:name :start-game
   :interceptors [discord.action/reply-interceptor ::pg/inject-conn mafia.int/no-game-running]
   :handler-fn
   (int.reg/interceptor
    {:name ::start-game-handler
     :leave (fn [{:keys [event ::pg/conn] :as ctx}]
              (update ctx :effects assoc
                      ::start-game {::data.game/moderator-id (util/parse-long (get-in event [:author :id]))
                                    ::data.game/channel-id (util/parse-long (get-in event [:channel :id]))
                                    ::pg/conn conn}))})})

(def start-game-effect
  {:name ::start-game
   :f (fn [{::pg/keys [conn] ::data.game/keys [moderator-id channel-id]}]
        (try
          (let [send-message (:f (reg/get :effect ::discord.action/send-message))]
            (data.game/insert-mafia-game! conn {:moderator-id moderator-id :channel-id channel-id})
            (send-message [channel-id "A mafia game has started! Type !join to join."]))
          (catch Throwable e
            (log/error e))))})

(def end-game-command
  {:name :end-game
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/role #{::data.game/moderator})]
   :handler-fn
   (int.reg/interceptor
    {:name ::end-game-handler
     :leave (fn [{:keys [::game/game ::pg/conn] :as ctx}]
              (update ctx :effects assoc
                      ::end-game {::data.game/id (::data.game/id game)
                                  ::data.game/channel-id (::data.game/channel-id game)
                                  ::pg/conn conn}))})})

(def end-game-effect
  {:name ::end-game
   :f (fn [{::data.game/keys [id channel-id] ::pg/keys [conn]}]
        (try
          (let [send-message (:f (reg/get :effect ::discord.action/send-message))]
            (data.game/finish-mafia-game-by-id! conn id)
            (send-message [channel-id "The mafia game has finished."]))
          (catch Throwable e
            (log/error e))))})

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
                                                ::data.event/player-id (util/parse-long (get-in event [:author :id]))})
                      ::discord.action/reply {:content "You have joined the game."}))})})

(def start-day-command
  {:name :start-day
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/stage #{::data.game/role-distribution ::data.game/night})
                  (mafia.int/role #{::data.game/moderator})]
   :handler-fn
   (int.reg/interceptor
    {:name ::start-day-handler
     :leave (fn [{::game/keys [game] :as ctx}]
              (update ctx :effects assoc
                      ::game/update-game (game/process-event
                                          game {::data.event/type ::data.event/start-day})
                      ::discord.action/reply {:content "The sun crests the horizon as a new day begins."}))})})

(def end-day-command
  {:name :end-day
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/stage #{::data.game/day})
                  (mafia.int/role #{::data.game/moderator})]
   :handler-fn
   (int.reg/interceptor
    {:name ::end-day-handler
     :leave (fn [{::game/keys [game] :as ctx}]
              (update ctx :effects assoc
                      ::game/update-game (game/process-event
                                          game {::data.event/type ::data.event/end-day})
                      ::discord.action/reply {:content "The sun disappears beyond the horizon as night begins."}))})})

(def vote-command
  {:name :vote
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/stage #{::data.game/day})
                  (mafia.int/role #{::data.game/alive})
                  (mafia.int/mentions-role #{::data.game/alive})]
   :handler-fn
   (int.reg/interceptor
    {:name ::vote-handler
     :leave (fn [{::game/keys [game] :keys [event] :as ctx}]
              (update ctx :effects assoc
                      ::game/update-game (game/process-event
                                          game {::data.event/type ::data.event/vote
                                                ::data.event/voter (util/parse-long (get-in event [:author :id]))
                                                ::data.event/votee (-> event :user-mentions first :id util/parse-long)})
                      ::discord.action/reply {:content "Your vote has been registered."}))})})

(defn format-my-vote [votee]
  (case votee
    ::data.game/no-one "You voted for no one."
    ::data.game/invalidated "Your vote was invalidated."
    nil "You have not voted yet."
    (str "You voted for <@!" votee ">.")))

(def my-vote-command
  {:name :my-vote
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/stage #{::data.game/day})
                  (mafia.int/role #{::data.game/alive})]
   :handler-fn
   (int.reg/interceptor
    {:name ::my-vote-handler
     :leave (fn [{::game/keys [game] :keys [event] :as ctx}]
              (update ctx :effects assoc
                      ::discord.action/reply {:content
                                              (let [votee (-> game
                                                              ::data.game/current-day
                                                              ::data.game/votes
                                                              game/votes-by-voter-id
                                                              (get (-> event :author :id util/parse-long)))]
                                                (format-my-vote votee))}))})})

(defmethod ig/init-key :mafia/command [_ _]
  (let [registry {:effect {::start-game start-game-effect
                           ::end-game end-game-effect}
                  :command {:start-game start-game-command
                            :end-registration end-registration-command
                            :start-day start-day-command
                            :end-day end-day-command
                            :join join-command
                            :vote vote-command
                            :my-vote my-vote-command
                            :end-game end-game-command}}]
    (reg/add-registry! registry)
    registry))

(defmethod ig/halt-key! :mafia/command [_ registry]
  (reg/remove-registry! registry))
