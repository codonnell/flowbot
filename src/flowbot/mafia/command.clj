(ns flowbot.mafia.command
  (:require [flowbot.mafia.interceptor :as mafia.int]
            [flowbot.mafia.data.game :as data.game]
            [flowbot.mafia.data.event :as data.event]
            [flowbot.mafia.data.player :as data.player]
            [flowbot.mafia.game :as game]
            [flowbot.data.postgres :as pg]
            [flowbot.plugin :as plugin]
            [flowbot.registrar :as reg]
            [flowbot.discord.action :as discord.action]
            [flowbot.interceptor.registrar :as int.reg]
            [flowbot.util :as util]
            [net.cgrand.xforms :as x]
            [integrant.core :as ig]
            [clojure.tools.logging :as log]))

(defn author-id [message]
  (util/parse-long (get-in message [:author :id])))

(defn channel-id [{:keys [channel-id]}]
  (util/parse-long channel-id))

(defn mention-id [message]
  (some-> message :mentions first :id util/parse-long))

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

;; Most of these are just boilerplate around:
;; * command name
;; * auth interceptors
;; * mafia event using data from message and game
;; * success message string from message and game
;; This is for the 80% case--don't try to make it too general when it doesn't have to be

;; (command {:name val
;;           :role val -- optional, can we get around loss of ordering? is it acceptable?
;;           :stage val -- optional
;;           :mentions-role -- optional
;;           :effect-fn (fn [message game] {:event val :reply val}) -- or maybe this to share computation?

(defn command [{:keys [cmd-name role stage mentions-role effect-fn]}]
  {:name         cmd-name
   :interceptors (cond-> [discord.action/reply-interceptor
                          ::pg/inject-conn
                          mafia.int/current-game-lens]
                   role          (conj (mafia.int/role role))
                   stage         (conj (mafia.int/stage stage))
                   mentions-role (conj (mafia.int/mentions-role mentions-role)))
   :handler-fn
   {:name (keyword "flowbot.mafia.command" (str (name cmd-name) "-handler"))
    :leave (fn [{::game/keys [game] :keys [event] :as ctx}]
             (let [event (assoc event
                                :author-id (author-id event)
                                :channel-id (channel-id event)
                                :mention-id (mention-id event))
                   {:keys [event reply]} (effect-fn event game)]
               (update ctx :effects merge
                       (cond-> {::discord.action/reply {:content reply}}
                         event (assoc ::game/update-game (game/process-event game event))))))}})

(def end-registration-command
  (command {:cmd-name  :end-reg
            :stage     #{::data.game/registration}
            :role      #{::data.game/moderator}
            :effect-fn (constantly {:event {::data.event/type ::data.event/end-registration}
                                    :reply "Mafia registration has been closed."})}))

(def join-command
  (command {:cmd-name :join
            :stage    #{::data.game/registration}
            :role     #{::data.game/not-player}
            :effect-fn
            (fn [{:keys [author-id] :as event} game]
              {:event #::data.event {:type      ::data.event/join-game
                                     :player-id author-id
                                     :username  (get-in event [:author :username])}
               :reply "You have joined the game."})}))

(def leave-command
  (command {:cmd-name  :leave
            :stage     #{::data.game/registration}
            :role      #{::data.game/player}
            :effect-fn (fn [{:keys [author-id]} _]
                         {:event #::data.event {:type ::data.event/leave-game :player-id author-id}
                          :reply "You have left the game."})}))

(def start-day-command
  (command {:cmd-name :start-day
            :stage #{::data.game/role-distribution ::data.game/night}
            :role #{::data.game/moderator}
            :effect-fn (constantly {:event #::data.event{:type ::data.event/start-day}
                                    :reply "The sun crests the horizon as a new day begins."})}))

(def start-night-command
  (command {:cmd-name :start-night
            :stage #{::data.game/day}
            :role #{::data.game/moderator}
            :effect-fn (constantly {:event #::data.event{:type ::data.event/start-night}
                                    :reply "The sun disappears beyond the horizon as night begins."})}))

(def vote-command
  (command {:cmd-name :vote
            :stage #{::data.game/day}
            :role #{::data.game/alive}
            :mentions-role #{::data.game/alive}
            :effect-fn (fn [{:keys [author-id mention-id]} _]
                         {:event #::data.event{:type ::data.event/vote
                                               :voter-id author-id
                                               :votee-id mention-id}
                          :reply "Your vote has been registered."})}))

(def unvote-command
  (command {:cmd-name :unvote
            :stage #{::data.game/day}
            :role #{::data.game/alive}
            :effect-fn (fn [{:keys [author-id mention-id]} _]
                         {:event #::data.event{:type ::data.event/unvote
                                               :voter-id author-id}
                          :reply "Your vote has been removed."})}))

(defn format-my-vote [{::data.game/keys [players]} votee]
  (case votee
    ::data.game/no-one "You voted for no one."
    ::data.game/invalidated "Your vote was invalidated."
    nil "You have not voted yet."
    (str "You voted for " (get-in players [votee ::data.player/username]) ".")))

(def my-vote-command
  (command {:cmd-name :my-vote
            :stage #{::data.game/day}
            :role #{::data.game/alive}
            :effect-fn (fn [{:keys [author-id]} game]
                         (let [votee (-> game
                                         ::data.game/current-day
                                         ::data.game/votes
                                         game/votes-by-voter-id
                                         (get author-id))]
                           {:reply (format-my-vote game votee)}))}))

(def kill-command
  (command {:cmd-name :kill
            :stage #{::data.game/day ::data.game/night}
            :role #{::data.game/moderator}
            :mentions-role #{::data.game/alive}
            :effect-fn (fn [{:keys [mention-id]} {::data.game/keys [players]}]
                         {:event #::data.event{:type ::data.event/kill
                                               :player-id mention-id}
                          :reply (str (get-in players [mention-id ::data.player/username])
                                      " has met an unforunate demise.")})}))

(def revive-command
  (command {:cmd-name :revive
            :role #{::data.game/moderator}
            :stage #{::data.game/day ::data.game/night}
            :mentions-role #{::data.game/dead}
            :effect-fn (fn [{:keys [mention-id]} {::data.game/keys [registered-players]}]
                         {:event #::data.event{:type ::data.event/revive
                                               :player-id mention-id}
                          :reply (str (get-in registered-players [mention-id ::data.player/username])
                                      " has been brought back to life. Hooray!")})}))

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
  (command {:cmd-name :vote-count
            :stage #{::data.game/day}
            :role #{::data.game/player ::data.game/moderator}
            :effect-fn (fn [_ game]
                         {:reply (format-votes-by-votee game)})}))

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
  (command {:cmd-name :vote-log
            :stage #{::data.game/day}
            :role #{::data.game/player ::data.game/moderator}
            :effect-fn (fn [_ game]
                         {:reply (format-vote-log game)})}))

(defn comma-separated-player-list [registered-players ids]
  (x/str (comp (map #(get-in registered-players [% ::data.player/username]))
               (interpose ", "))
         ids))

(defn comma-separated-ping-list [ids]
  (x/str (comp (map #(str "<@" % ">"))
               (interpose ", "))
         ids))

(def nonvoters-command
  (command {:cmd-name :nonvoters
            :stage #{::data.game/day}
            :role #{::data.game/player ::data.game/moderator}
            :effect-fn (fn [_ {::data.game/keys [registered-players] :as game}]
                         {:reply (let [nonvoter-ids (game/nonvoters game)]
                                   (str "**Nonvoters**: "
                                        (if (empty? nonvoter-ids)
                                          "none"
                                          (comma-separated-player-list registered-players nonvoter-ids))))})}))

(def ping-nonvoters-command
  (command {:cmd-name :ping-nonvoters
            :stage #{::data.game/day}
            :role #{::data.game/player ::data.game/moderator}
            :effect-fn (fn [_ {::data.game/keys [registered-players] :as game}]
                         {:reply (let [nonvoter-ids (game/nonvoters game)]
                                   (str "**Nonvoters**: "
                                        (if (empty? nonvoter-ids)
                                          "none"
                                          (comma-separated-ping-list nonvoter-ids))))})}))

(def alive-command
  (command {:cmd-name :alive
            :stage #{::data.game/day ::data.game/night}
            :role #{::data.game/player ::data.game/moderator}
            :effect-fn (fn [_ {::data.game/keys [players]}]
                         {:reply (str "**Alive Players**: "
                                      (if (empty? players)
                                        "none"
                                        (comma-separated-player-list players (keys players))))})}))

(def ping-alive-command
  (command {:cmd-name :ping-alive
            :stage #{::data.game/day ::data.game/night}
            :role #{::data.game/player ::data.game/moderator}
            :effect-fn (fn [_ {::data.game/keys [players]}]
                         {:reply (str "**Alive Players**: "
                                      (if (empty? players)
                                        "none"
                                        (comma-separated-ping-list (keys players))))})}))

(def signups-command
  (command {:cmd-name :signups
            :stage #{::data.game/registration ::data.game/role-distribution}
            :effect-fn (fn [_ {::data.game/keys [registered-players]}]
                         {:reply (str "**Signups**: "
                                      (if (empty? registered-players)
                                        "none"
                                        (comma-separated-player-list registered-players (keys registered-players))))})}))

(def ping-signups-command
  (command {:cmd-name :ping-signups
            :stage #{::data.game/registration ::data.game/role-distribution}
            :effect-fn (fn [_ {::data.game/keys [registered-players]}]
                         {:reply (str "**Signups**: "
                                      (if (empty? registered-players)
                                        "none"
                                        (comma-separated-ping-list (keys registered-players))))})}))

(def commands {:start-game start-game-command
               :end-game end-game-command
               :end-reg end-registration-command
               :start-day start-day-command
               :start-night start-night-command
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
               :ping-alive ping-alive-command
               :signups signups-command
               :ping-signups ping-signups-command})

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

(def registry {:effect {::start-game start-game-effect
                        ::end-game end-game-effect}
               :command (assoc commands :mafia-commands mafia-commands-command)})

(defmethod plugin/enable "mafia" [_]
  (reg/add-registry! registry)
  {::discord.action/reply {:content "Mafia plugin enabled."}})

(defmethod plugin/disable "mafia" [_]
  (reg/remove-registry! registry)
  {::discord.action/reply {:content "Mafia plugin disabled."}})
