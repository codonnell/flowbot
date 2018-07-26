(ns flowbot.mafia.command
  (:require [flowbot.mafia.interceptor :as mafia.int]
            [flowbot.mafia.data.game :as data.game]
            [flowbot.mafia.data.event :as data.event]
            [flowbot.mafia.data.player :as data.player]
            [flowbot.mafia.game :as game]
            [flowbot.data.postgres :as pg]
            [flowbot.registrar :as reg]
            [flowbot.discord.action :as discord.action]
            [flowbot.interceptor.registrar :as int.reg]
            [flowbot.util :as util]
            [net.cgrand.xforms :as x]
            [integrant.core :as ig]
            [clojure.tools.logging :as log]))

(defn author-id [message]
  (util/parse-long (get-in message [:author :id])))

(defn channel-id [message]
  (util/parse-long (get-in message [:channel :id])))

(defn mention-id [message]
  (-> message :user-mentions first :id util/parse-long))

(def start-game-command
  {:name :start-game
   :interceptors [discord.action/reply-interceptor ::pg/inject-conn mafia.int/no-game-running]
   :handler-fn
   (int.reg/interceptor
    {:name ::start-game-handler
     :leave (fn [{:keys [event ::pg/conn] :as ctx}]
              (update ctx :effects assoc
                      ::start-game {::data.game/moderator-id (author-id event)
                                    ::data.game/channel-id (channel-id event)
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
                  (int.reg/or-auth
                   discord.action/owner-role
                   (mafia.int/role #{::data.game/moderator}))]
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
                                                ::data.event/player-id (author-id event)
                                                ::data.event/username (get-in event [:author :username])})
                      ::discord.action/reply {:content "You have joined the game."}))})})

(def leave-command
  {:name :leave
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/stage #{::data.game/registration})
                  (mafia.int/role #{::data.game/player})]
   :handler-fn
   (int.reg/interceptor
    {:name ::leave-handler
     :leave (fn [{::game/keys [game] :keys [event] :as ctx}]
              (update ctx :effects assoc
                      ::game/update-game (game/process-event
                                          game {::data.event/type ::data.event/leave-game
                                                ::data.event/player-id (author-id event)})
                      ::discord.action/reply {:content "You have left the game."}))})})

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
                                                ::data.event/voter-id (author-id event)
                                                ::data.event/votee-id (mention-id event)})
                      ::discord.action/reply {:content "Your vote has been registered."}))})})

(def unvote-command
  {:name :unvote
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/stage #{::data.game/day})
                  (mafia.int/role #{::data.game/alive})]
   :handler-fn
   (int.reg/interceptor
    {:name ::unvote-handler
     :leave (fn [{::game/keys [game] :keys [event] :as ctx}]
              (update ctx :effects assoc
                      ::game/update-game (game/process-event
                                          game {::data.event/type ::data.event/unvote
                                                ::data.event/voter-id (author-id event)})
                      ::discord.action/reply {:content "Your vote has been removed."}))})})

(defn format-my-vote [{::data.game/keys [players]} votee]
  (case votee
    ::data.game/no-one "You voted for no one."
    ::data.game/invalidated "Your vote was invalidated."
    nil "You have not voted yet."
    (str "You voted for " (get-in players [votee ::data.player/username]) ".")))

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
                                                              (get (author-id event)))]
                                                (format-my-vote game votee))}))})})

(def kill-command
  {:name :kill
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/role #{::data.game/moderator})
                  (mafia.int/stage #{::data.game/day ::data.game/night})
                  (mafia.int/mentions-role #{::data.game/alive})]
   :handler-fn
   (int.reg/interceptor
    {:name ::kill-handler
     :leave (fn [{{::data.game/keys [players] :as game} ::game/game
                  :keys [event] :as ctx}]
              (let [target-id (mention-id event)]
                (update ctx :effects assoc
                        ::game/update-game (game/process-event
                                            game {::data.event/type ::data.event/kill
                                                  ::data.event/player-id target-id})
                        ::discord.action/reply {:content (str (get-in players [target-id ::data.player/username])
                                                              " has met an unforunate demise.")})))})})

(def revive-command
  {:name :revive
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/role #{::data.game/moderator})
                  (mafia.int/stage #{::data.game/day ::data.game/night})
                  (mafia.int/mentions-role #{::data.game/dead})]
   :handler-fn
   (int.reg/interceptor
    {:name ::revive-handler
     :leave (fn [{{::data.game/keys [registered-players] :as game} ::game/game
                  :keys [event] :as ctx}]
              (let [target-id (mention-id event)]
                (update ctx :effects assoc
                        ::game/update-game (game/process-event
                                            game {::data.event/type ::data.event/revive
                                                  ::data.event/player-id target-id})
                        ::discord.action/reply {:content (str (get-in registered-players [target-id ::data.player/username])
                                                              " has been brought back to life. Hooray!")})))})})





(defn format-vote-count [[target n]]
  (str target ": " n))

(defn format-votee [registered-players votee-id]
  (case votee-id
    ::data.game/no-one "No one"
    ::data.game/invalidated "Invalidated"
    nil "Not voted"
    (get-in registered-players [votee-id ::data.player/username])))

(defn format-votes-by-votee [{::data.game/keys [registered-players]
                              {::data.game/keys [votes]} ::data.game/current-day}]
  (if (empty? votes)
    "**No Votes Recorded**"
    (str "**Votes**\n"
         (x/str (comp (x/sort-by second (comp - compare))
                      (map (fn [[votee-id n]] [(format-votee registered-players votee-id) n]))
                      (map format-vote-count)
                      (interpose "\n"))
                (game/votes-by-votee-id votes)))))

(def vote-count-command
  {:name :vote-count
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/stage #{::data.game/day})
                  (mafia.int/role #{::data.game/player ::data.game/moderator})]
   :handler-fn
   (int.reg/interceptor
    {:name ::vote-count-handler
     :leave (fn [{::game/keys [game] :as ctx}]
              (update ctx :effects assoc
                      ::discord.action/reply {:content (format-votes-by-votee game)}))})})

(defn format-vote-log-entry [registered-players {::data.game/keys [voter-id votee-id]
                                                 ::keys [final?]}]
  (let [voter-name (get-in registered-players [voter-id ::data.player/username])]
    (let [message
          (case votee-id
            ::data.game/no-one (str "_" voter-name "_ voted for no one")
            ::data.game/invalidated (str "_" voter-name "_'s vote was invalidated")
            (str "_" voter-name "_ voted for _" (get-in registered-players [votee-id ::data.player/username]) "_"))]
      (if final? (str "**" message "**") message))))

(defn mark-final-votes [votes]
  (::votes (reduce (fn [{::keys [final-voter-ids votes]} {::data.game/keys [voter-id] :as vote}]
                     {::final-voter-ids (conj final-voter-ids voter-id)
                      ::votes (conj votes (if (final-voter-ids voter-id)
                                            vote (assoc vote ::final? true)))})
                   {::final-voter-ids #{} ::votes '()}
                   (reverse votes))))

(defn format-vote-log [{::data.game/keys [registered-players]
                        {::data.game/keys [votes]} ::data.game/current-day}]
  (if (empty? votes)
    "**No Votes Recorded**"
    (str "**Vote Log**\n"
         (x/str (comp (map (partial format-vote-log-entry registered-players))
                      (interpose "\n"))
                (mark-final-votes votes)))))

(def vote-log-command
  {:name :vote-log
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/stage #{::data.game/day})
                  (mafia.int/role #{::data.game/player ::data.game/moderator})]
   :handler-fn
   (int.reg/interceptor
    {:name ::vote-log-handler
     :leave (fn [{::game/keys [game] :as ctx}]
              (update ctx :effects assoc
                      ::discord.action/reply {:content (format-vote-log game)}))})})

(defn comma-separated-player-list [registered-players ids]
  (x/str (comp (map #(get-in registered-players [% ::data.player/username]))
               (interpose ", "))
         ids))

(defn comma-separated-ping-list [ids]
  (x/str (comp (map #(str "<@" % ">"))
               (interpose ", "))
         ids))

(def nonvoters-command
  {:name :nonvoters
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/stage #{::data.game/day})
                  (mafia.int/role #{::data.game/player ::data.game/moderator})]
   :handler-fn
   (int.reg/interceptor
    {:name ::nonvoters-handler
     :leave (fn [{{::data.game/keys [registered-players] :as game} ::game/game :as ctx}]
              (update ctx :effects assoc
                      ::discord.action/reply
                      {:content
                       (let [nonvoter-ids (game/nonvoters game)]
                         (str "**Nonvoters**: "
                              (if (empty? nonvoter-ids)
                                "none"
                                (comma-separated-player-list registered-players nonvoter-ids))))}))})})

(def ping-nonvoters-command
  {:name :ping-nonvoters
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/stage #{::data.game/day})
                  (mafia.int/role #{::data.game/player ::data.game/moderator})]
   :handler-fn
   (int.reg/interceptor
    {:name ::ping-nonvoters-handler
     :leave (fn [{{::data.game/keys [registered-players] :as game} ::game/game :as ctx}]
              (update ctx :effects assoc
                      ::discord.action/reply
                      {:content
                       (let [nonvoter-ids (game/nonvoters game)]
                         (str "**Nonvoters**: "
                              (if (empty? nonvoter-ids)
                                "none"
                                (comma-separated-ping-list nonvoter-ids))))}))})})

(def alive-command
  {:name :alive
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/stage #{::data.game/day ::data.game/night})
                  (mafia.int/role #{::data.game/player ::data.game/moderator})]
   :handler-fn
   (int.reg/interceptor
    {:name ::alive-handler
     :leave (fn [{{::data.game/keys [players]} ::game/game :as ctx}]
              (update ctx :effects assoc
                      ::discord.action/reply
                      {:content
                       (str "**Alive Players**: "
                            (if (empty? players)
                              "none"
                              (comma-separated-player-list players (keys players))))}))})})

(def ping-alive-command
  {:name :ping-alive
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  mafia.int/current-game-lens
                  (mafia.int/stage #{::data.game/day ::data.game/night})
                  (mafia.int/role #{::data.game/player ::data.game/moderator})]
   :handler-fn
   (int.reg/interceptor
    {:name ::ping-alive-handler
     :leave (fn [{{::data.game/keys [players]} ::game/game :as ctx}]
              (update ctx :effects assoc
                      ::discord.action/reply
                      {:content
                       (str "**Alive Players**: "
                            (if (empty? players)
                              "none"
                              (comma-separated-ping-list (keys players))))}))})})

(def commands {:start-game start-game-command
               :end-game end-game-command
               :end-registration end-registration-command
               :start-day start-day-command
               :end-day end-day-command
               :join join-command
               :leave leave-command
               :vote vote-command
               :unvote unvote-command
               :my-vote my-vote-command
               :kill kill-command
               :revive revive-command
               :vote-count vote-count-command
               :vote-log vote-log-command
               :nonvoters nonvoters-command
               :ping-nonvoters ping-nonvoters-command
               :alive alive-command
               :ping-alive ping-alive-command})

(def mafia-commands-command
  {:name :mafia-commands
   :interceptors [discord.action/reply-interceptor]
   :handler-fn
   (int.reg/interceptor
    {:name ::mafia-commands-handler
     :leave (fn [ctx]
              (update ctx :effects assoc
                      ::discord.action/reply
                      {:content
                       (x/str (comp (map name)
                                    (x/sort)
                                    (interpose ", "))
                              (keys commands))}))})})

(defmethod ig/init-key :mafia/command [_ _]
  (let [registry {:effect {::start-game start-game-effect
                           ::end-game end-game-effect}
                  :command (assoc commands :mafia-commands mafia-commands-command)}]
    (reg/add-registry! registry)
    registry))

(defmethod ig/halt-key! :mafia/command [_ registry]
  (reg/remove-registry! registry))
