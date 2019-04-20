(ns flowbot.botc.command
  (:require [flowbot.botc.interceptor :as botc.int]
            [flowbot.botc.data.game :as data.game]
            [flowbot.botc.data.event :as data.event]
            [flowbot.botc.data.player :as data.player]
            [flowbot.botc.game :as game]
            [flowbot.data.postgres :as pg]
            [flowbot.plugin :as plugin]
            [flowbot.registrar :as reg]
            [flowbot.discord.action :as discord.action]
            [flowbot.interceptor.registrar :as int.reg]
            [flowbot.util :as util]
            [net.cgrand.xforms :as x]
            [integrant.core :as ig]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defn author-id [message]
  (util/parse-long (get-in message [:author :id])))

(defn channel-id [{:keys [channel-id]}]
  (util/parse-long channel-id))

(defn mention-id [message]
  (some-> message :mentions first :id util/parse-long))

(def start-game-command
  {:name :start-game
   :interceptors [discord.action/reply-interceptor ::pg/inject-conn botc.int/no-game-running]
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
            (data.game/insert-botc-game! conn {:moderator-id moderator-id :channel-id channel-id})
            (send-message [channel-id "A blood on the clocktower game has started! Type !join to join."]))
          (catch Throwable e
            (log/error e))))})

(def end-game-command
  {:name :end-game
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  botc.int/current-game-lens
                  (int.reg/or-auth
                   discord.action/owner-role
                   (botc.int/role #{::data.game/moderator}))]
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
            (data.game/finish-botc-game-by-id! conn id)
            (send-message [channel-id "The blood on the clocktower game has finished."]))
          (catch Throwable e
            (log/error e))))})

;; Most of these are just boilerplate around:
;; * command name
;; * auth interceptors
;; * botc event using data from message and game
;; * success message string from message and game
;; This is for the 80% case--don't try to make it too general when it doesn't have to be

;; (command {:name val
;;           :role val -- optional, can we get around loss of ordering? is it acceptable?
;;           :stage val -- optional
;;           :mentions-role -- optional
;;           :effect-fn (fn [message game] {:event val :reply val}) -- or maybe this to share computation?

(defn command [{:keys [cmd-name role stage mentions-role effect-fn allow-dm?]}]
  {:name         cmd-name
   :interceptors (cond-> [discord.action/reply-interceptor
                          ::pg/inject-conn]
                   allow-dm?     (conj botc.int/dm-inject-channel-id)
                   true          (conj botc.int/current-game-lens)
                   role          (conj (botc.int/role role))
                   stage         (conj (botc.int/stage stage))
                   mentions-role (conj (botc.int/mentions-role mentions-role)))
   :handler-fn
   {:name  (keyword "flowbot.botc.command" (str (name cmd-name) "-handler"))
    :leave (fn [{::game/keys [game] :keys [event] :as ctx}]
             (let [event                 (assoc event
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
                                    :reply "Blood on the clocktower registration has been closed."})}))

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

(def end-day-command
  (command {:cmd-name :end-day
            :stage #{::data.game/day}
            :role #{::data.game/moderator}
            :effect-fn (constantly {:event #::data.event{:type ::data.event/end-day}
                                    :reply "The sun disappears beyond the horizon as night begins."})}))

(def nominate-command
  (command {:cmd-name :nominate
            :stage #{::data.game/day}
            :role #{::data.game/alive}
            :mentions-role #{::data.game/player ::data.game/moderator}
            :effect-fn (fn [{:keys [author-id mention-id]} game]
                         (cond
                           (not (game/can-nominate? game author-id))
                           {:reply "You've already nominated someone today."}
                           (not (game/can-be-nominated? game mention-id))
                           {:reply "That player has already been nominated today"}
                           :else
                           {:event #::data.event{:type ::data.event/nominate
                                                 :nominator-id author-id
                                                 :nominated-id mention-id}
                            :reply "Your nomination has been registered."}))}))

(def vote-command
  (command {:cmd-name :vote
            :stage #{::data.game/day}
            :role #{::data.game/player}
            :mentions-role #{::data.game/nominated}
            :effect-fn (fn [{:keys [author-id mention-id]} game]
                         (cond
                           (not (game/can-vote? game author-id))
                           {:reply "You cannot vote. You're dead and already used your dead vote."}
                           (game/voted-for? game author-id mention-id)
                           {:reply "You already voted for them today."}
                           :else
                           {:event #::data.event{:type ::data.event/vote
                                                 :voter-id author-id
                                                 :votee-id mention-id}
                            :reply "Your vote has been registered."}))}))

(defn format-votee [registered-players votee-id]
  (get-in registered-players [votee-id ::data.player/username] "moderator"))

(def kill-command
  (command {:cmd-name :kill
            :stage #{::data.game/day ::data.game/night}
            :role #{::data.game/moderator}
            :mentions-role #{::data.game/alive}
            :effect-fn (fn [{:keys [mention-id]} {::data.game/keys [registered-players]}]
                         {:event #::data.event{:type ::data.event/kill
                                               :player-id mention-id}
                          :reply (str (format-votee registered-players mention-id)
                                      " has met an unforunate demise.")})}))

(def execute-command
  (command {:cmd-name :execute
            :stage #{::data.game/day}
            :role #{::data.game/moderator}
            :effect-fn (fn [_ {::data.game/keys [registered-players] :as game}]
                         (if-let [dead-id (game/who-dies game)]
                           {:event #::data.event{:type ::data.event/kill
                                                 :player-id dead-id}
                            :reply (str (format-votee registered-players dead-id)
                                        " has been executed.")}
                           {:reply "No one has been executed"}))}))

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

(defn- bold [s]
  (str "**" s "**"))

(defn format-vote-count [num-players [target n]]
  (cond-> (str target ": " n)
    (> n (quot num-players 2)) bold))

(defn format-votes-by-votee [{::data.game/keys [registered-players players]
                              {::data.game/keys [votes]} ::data.game/current-day}]
  (if (empty? votes)
    "**No Votes Recorded**"
    (str "**Votes**\n"
         (x/str (comp (x/sort-by second (comp - compare))
                      (map (fn [[votee-id n]] [(format-votee registered-players votee-id) n]))
                      (map (partial format-vote-count (count players)))
                      (interpose "\n"))
                (game/votes-by-votee-id votes)))))

(def vote-count-command
  (command {:cmd-name :vote-count
            :stage #{::data.game/day}
            :role #{::data.game/player ::data.game/moderator}
            :effect-fn (fn [_ game]
                         {:reply (format-votes-by-votee game)})}))

(defn format-vote-log-entry [registered-players {::data.game/keys [voter-id votee-id invalidated?]}]
  (let [voter-name (get-in registered-players [voter-id ::data.player/username])]
    (let [message
          (format "_%s_ voted for _%s_"
                  voter-name
                  (format-votee registered-players votee-id))]
      (if invalidated? (str "~~" message "~~") message))))

(defn format-vote-log [{::data.game/keys [registered-players]
                        {::data.game/keys [votes]} ::data.game/current-day}]
  (if (empty? votes)
    "**No Votes Recorded**"
    (str "**Vote Log**\n"
         (x/str (comp (map (partial format-vote-log-entry registered-players))
                      (interpose "\n"))
                votes))))

(def vote-log-command
  (command {:cmd-name :vote-log
            :stage #{::data.game/day}
            :role #{::data.game/player ::data.game/moderator}
            :effect-fn (fn [_ game]
                         {:reply (format-vote-log game)})}))

(def who-dies-command
  (command {:cmd-name :who-dies
            :stage #{::data.game/day ::data.game/night}
            :role #{::data.game/player ::data.game/moderator}
            :effect-fn (fn [_ {::data.game/keys [registered-players] :as game}]
                         {:reply (if-let [dead-id (game/who-dies game)]
                                   (format "_%s_ will be executed."
                                           (format-votee registered-players dead-id))
                                   "No one will be executed.")})}))

(defn comma-separated-player-list [registered-players ids]
  (x/str (comp (map #(get-in registered-players [% ::data.player/username] "moderator"))
               (interpose ", "))
         ids))

(defn comma-separated-ping-list [ids]
  (x/str (comp (map #(str "<@" % ">"))
               (interpose ", "))
         ids))

(def nominations-command
  (command {:cmd-name :nominations
            :stage #{::data.game/day}
            :role #{::data.game/player ::data.game/moderator}
            :effect-fn (fn [_ {::data.game/keys [registered-players] :as game}]
                         (let [nominated-ids (-> game ::data.game/current-day ::data.game/nominations keys)]
                           {:reply (if nominated-ids
                                     (str "**Nominations:** "
                                          (comma-separated-player-list registered-players nominated-ids))
                                     "**No Nominations Recorded**")}))}))

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

(def players-command
  (command {:cmd-name :players
            :stage #{::data.game/role-distribution ::data.game/day ::data.game/night ::data.game/finished}
            :allow-dm? true
            :effect-fn (fn [_ {::data.game/keys [registered-players] :as game}]
                         (log/warn {:game game})
                         {:reply (str "**Players**"
                                      (if (seq registered-players)
                                        (x/str (map (fn [[_ {::data.player/keys [index username]}]]
                                                      (format "\n%d. %s" index username)))
                                               (sort-by (comp ::data.player/index val) registered-players))
                                        ": none"))})}))

(def dm-command
  {:name :dm
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  botc.int/dm-inject-channel-id
                  botc.int/current-game-lens
                  (botc.int/role #{::data.game/player})
                  (botc.int/stage #{::data.game/day})]
   :handler-fn
   {:name ::dm-handler
    :leave (fn [{::game/keys [game] :keys [event] :as ctx}]
             (let [[_ recipient-index message] (str/split (:content event) #"\s+" 3)
                   author-username (get-in game [::data.game/registered-players (author-id event) ::data.player/username])]
               (update ctx :effects merge
                       (if-let [recipient (try (and recipient-index
                                                    message
                                                    (some (fn [[_ {::data.player/keys [index] :as player}]]
                                                            (when (= (util/parse-long recipient-index) index)
                                                              player))
                                                          (::data.game/registered-players game)))
                                               (catch Throwable _ nil))]
                         {::discord.action/reply {:content (format "Message sent to %s"
                                                                   (::data.player/username recipient))}
                          ::discord.action/send-messages
                          [{:channel-id (::botc.int/channel-id event)
                            :content (format "**DM** %s -> %s"
                                             author-username
                                             (::data.player/username recipient))}]

                          ::discord.action/send-dms [{:user-id (::data.player/id recipient)
                                                      :content (format "Message from %s: %s" author-username message)}
                                                     {:user-id (::data.game/moderator-id game)
                                                      :content (format "**DM** %s -> %s: %s"
                                                                       author-username
                                                                       (::data.player/username recipient)
                                                                       message)}]}
                         {::discord.action/reply {:content "Usage: !dm player-number message\nType !players in the game channel to see all player numbers."}}))))}})


(def commands {:start-game start-game-command
               :end-game end-game-command
               :end-reg end-registration-command
               :start-day start-day-command
               :end-day end-day-command
               :join join-command
               :leave leave-command
               :nominate nominate-command
               :vote vote-command
               :kill kill-command
               :execute execute-command
               :revive revive-command
               :nominations nominations-command
               :vote-count vote-count-command
               :vote-log vote-log-command
               :who-dies who-dies-command
               :nonvoters nonvoters-command
               :ping-nonvoters ping-nonvoters-command
               :alive alive-command
               :ping-alive ping-alive-command
               :signups signups-command
               :ping-signups ping-signups-command
               :players players-command
               :dm dm-command})

(def botc-commands-command
  {:name :botc-commands
   :interceptors [discord.action/reply-interceptor]
   :handler-fn
   (int.reg/interceptor
    {:name ::botc-commands-handler
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
               :command (assoc commands :botc-commands botc-commands-command)})

(defmethod plugin/enable "botc" [_]
  (reg/add-registry! registry)
  {::discord.action/reply {:content "Blood on the clocktower plugin enabled."}})

(defmethod plugin/disable "botc" [_]
  (reg/remove-registry! registry)
  {::discord.action/reply {:content "Blood on the clocktower plugin disabled."}})