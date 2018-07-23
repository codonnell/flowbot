(ns flowbot.mafia.game
  (:require [clojure.tools.logging :as log]
            [clojure.spec.alpha :as s]
            [clojure.set :as set]
            [flowbot.mafia.data.game :as game]
            [flowbot.mafia.data.event :as event]
            [flowbot.mafia.data.player :as player]))


;; Game data manipulation
;; ----------------------------------------

(defn init-game [{::game/keys [moderator-id] :as game}]
  (assoc game
         ::game/stage ::game/registration
         ::game/registered-players {}
         ::game/past-days []))

(defn unstarted? [{::game/keys [stage] :as game}]
  (#{::game/registration ::game/role-distribution} stage))

(def started? (complement unstarted?))

(defn end-registration [{::game/keys [registered-players] :as game}]
  (assoc game
         ::game/stage ::game/role-distribution
         ::game/players registered-players))

(defn- registration-open? [{:keys [::game/stage]}]
  (= ::game/registration stage))

(defn join-game [game {::player/keys [id] :as player}]
  (cond-> game
    (registration-open? game) (update ::game/registered-players assoc id player)))

(defn leave-game [game player-id]
  (cond-> game
    (registration-open? game) (update ::game/registered-players dissoc player-id)))

(defn day? [{::game/keys [stage]}]
  (= ::game/day stage))

(defn night? [{::game/keys [stage]}]
  (= ::game/night stage))

(defn start-day [{::game/keys [past-days stage] :as game}]
  (if (#{::game/role-distribution ::game/night} stage)
    (assoc game
           ::game/current-day {::game/votes []}
           ::game/stage ::game/day)
    game))

(defn end-day [{::game/keys [current-day] :as game}]
  (if (day? game)
    (-> game
        (assoc ::game/stage ::game/night)
        (update ::game/past-days conj current-day)
        (dissoc ::game/current-day))
    game))

(defn- alive? [{::game/keys [players]} player-id]
  (players player-id))

(defn vote [{::game/keys [players] :as game} voter-id votee-id]
  (if (and (alive? game voter-id) (day? game))
    (update-in game [::game/current-day ::game/votes] conj #::game{:voter-id voter-id
                                                                   :votee-id votee-id})
    game))

(defn unvote [game voter-id]
  (if (and (alive? game voter-id) (day? game))
    (update-in game [::game/current-day ::game/votes] conj #::game{:voter-id voter-id
                                                                   :votee-id ::game/no-one})
    game))

(defn valid-vote? [{::game/keys [players]} {::game/keys [voter-id votee-id]}]
  (boolean (and (players voter-id)
                (or (players votee-id) (= ::game/no-one votee-id)))))

(defn votes-by-voter-id
  "Given a vector of votes, returns a map of voter-id to final votee-id."
  [votes]
  (reduce (fn [totals {::game/keys [voter-id votee-id]}]
            (assoc totals voter-id votee-id))
          {} votes))

(defn votes-by-votee-id
  "Given a vector of votes, returns a map of votee-id to the number of votes for
  them."
  [votes]
  (reduce-kv (fn [totals _ votee-id]
               (update totals votee-id (fnil inc 0)))
             {}
             (votes-by-voter-id votes)))

(defn yesterday-totals [{::game/keys [past-days] :as game}]
  (when-not (empty? past-days)
    (->> past-days
         peek
         ::game/votes
         (filterv (partial valid-vote? game))
         votes-by-votee-id)))

(defn today-totals [{::game/keys [current-day] :as game}]
  (when (day? game)
    (->> current-day
         ::game/votes
         (filterv (partial valid-vote? game))
         votes-by-votee-id)))

(defn nonvoters [{::game/keys [players]
                  {::game/keys [votes]} ::game/current-day
                  :as game}]
  (when (day? game)
    (set/difference (set (keys players)) (into #{} (map ::game/voter-id) votes))))

(defn invalidate-votes-for [{{::game/keys [votes]} ::game/current-day :as game} votee-id]
  (if (day? game)
    (let [votes-by-voter-id (votes-by-voter-id votes)
          voters-for-votee (into #{}
                                 (keep (fn [[voter-id votee-id_]]
                                         (when (= votee-id votee-id_)
                                           voter-id)))
                                 votes-by-voter-id)]
      (update-in game [::game/current-day ::game/votes]
                 into
                 (map (fn [voter-id]
                        #::game{:voter-id voter-id :votee-id ::game/invalidated}))
                 voters-for-votee))
    game))

(defn voted? [{{::game/keys [votes]} ::game/current-day} player-id]
  (seq (filterv (fn [{::game/keys [voter-id]}] (= player-id voter-id)) votes)))

(defn invalidate-vote-by [{{::game/keys [votes]} ::game/current-day :as game} voter-id]
  (if (and (day? game) (voted? game voter-id))
    (update-in game [::game/current-day ::game/votes]
               conj #::game{:voter-id voter-id :votee-id ::game/invalidated})
    game))

(defn kill [game player-id]
  (-> game
      (update ::game/players dissoc player-id)
      (invalidate-votes-for player-id)
      (invalidate-vote-by player-id)))

(defn revive [{::game/keys [registered-players] :as game} player-id]
  (cond-> game
    (registered-players player-id)
    (update ::game/players assoc player-id (registered-players player-id))))


;; Game state update events
;; ----------------------------------------

;; Events
;; [::vote {:voter voter :votee votee}]
;; [::start-day]
;; [::end-day]
;; [::kill {:player-id player-id}]
;; [::revive {:player-id player-id}]
;; [::join-game {:player-id player-id}] -- consider a block feature
;; [::leave-game {:player-id player-id}]
;; [::start-game {:moderator-id moderator-id}] -- assigns roles and starts day

(defmulti process-event* (fn [_ {::event/keys [type]}] type))

(defmethod process-event* ::event/start-game
  [game _]
  (start-day game))

(defmethod process-event* ::event/join-game
  [game {::event/keys [player-id username]}]
  (join-game game #::player{:id player-id :username username}))

(defmethod process-event* ::event/leave-game
  [game {::event/keys [player-id]}]
  (leave-game game player-id))

(defmethod process-event* ::event/vote
  [game {::event/keys [voter-id votee-id]}]
  (vote game voter-id votee-id))

(defmethod process-event* ::event/unvote
  [game {::event/keys [voter-id]}]
  (unvote game voter-id))

(defmethod process-event* ::event/start-day
  [game _]
  (start-day game))

(defmethod process-event* ::event/end-day
  [game _]
  (end-day game))

(defmethod process-event* ::event/kill
  [game {::event/keys [player-id]}]
  (kill game player-id))

(defmethod process-event* ::event/revive
  [game {::event/keys [player-id]}]
  (revive game player-id))

(defmethod process-event* ::event/end-registration
  [game _]
  (end-registration game))

(defn- push-event [game event]
  (update game ::event/events (fnil conj []) event))

(defn process-event [game event]
  (-> game
      (process-event* event)
      (push-event event)))

(defn replay-events
  [game events]
  (reduce process-event game events))

