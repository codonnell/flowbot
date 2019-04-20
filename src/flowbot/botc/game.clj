(ns flowbot.botc.game
  (:require [clojure.tools.logging :as log]
            [clojure.spec.alpha :as s]
            [clojure.set :as set]
            [clojure.string :as str]
            [flowbot.botc.data.game :as game]
            [flowbot.botc.data.event :as event]
            [flowbot.botc.data.player :as player]))


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
  (let [players (into {}
                      (map-indexed (fn [idx [id player]]
                                     [id (assoc player ::player/index idx)]))
                      (sort-by (comp str/lower-case ::player/username val) registered-players))]
    (assoc game
           ::game/stage ::game/role-distribution
           ::game/registered-players players
           ::game/players players
           ::game/dead-votes-used #{})))

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

(defn- moderator? [{::game/keys [moderator-id]} id]
  (= moderator-id id))

(defn- player? [{::game/keys [registered-players]} id]
  (registered-players id))

(defn- alive? [{::game/keys [players]} player-id]
  (players player-id))

(defn can-nominate? [game player-id]
  (not (some #(= % player-id) (-> game ::game/current-day ::game/nominations vals))))

(defn nominated? [game player-id]
  (contains? (get-in game [::game/current-day ::game/nominations]) player-id))

(defn can-be-nominated? [game id]
  (and (or (player? game id) (moderator? game id))
       (not (nominated? game id))))

(defn nominate [{::game/keys [players] :as game} nominator-id nominated-id]
  (cond-> game
    (and (alive? game nominator-id)
         (day? game)
         (can-nominate? game nominator-id)
         (can-be-nominated? game nominated-id))
    (assoc-in [::game/current-day ::game/nominations nominated-id] nominator-id)))

(defn voted-for? [game voter-id votee-id]
  (some #(= #::game{:voter-id voter-id :votee-id votee-id} %)
        (get-in game [::game/current-day ::game/votes])))

(defn can-vote? [{::game/keys [dead-votes-used] :as game} voter-id]
  (or (alive? game voter-id)
      (not (dead-votes-used voter-id))))

(defn vote [game voter-id votee-id]
  (let [use-vote? (and (day? game)
                       (nominated? game votee-id)
                       (not (voted-for? game voter-id votee-id))
                       (can-vote? game voter-id))
        use-dead-vote? (and can-vote? (not (alive? game voter-id)))]
    (cond-> game
      use-vote?
      (update-in [::game/current-day ::game/votes] conj #::game{:voter-id voter-id
                                                                :votee-id votee-id})
      use-dead-vote?
      (update ::game/dead-votes-used conj voter-id))))

;; (defn votes-by-voter-id
;;   "Given a vector of votes, returns a map of voter-id to final votee-id."
;;   [votes]
;;   (reduce (fn [totals {::game/keys [voter-id votee-id]}]
;;             (assoc totals voter-id votee-id))
;;           {} votes))

(defn votes-by-votee-id
  "Given a vector of votes, returns a map of votee-id to the number of votes for
  them."
  [votes]
  (transduce (remove ::game/invalidated?)
             (completing (fn [totals {::game/keys [votee-id]}]
                           (update totals votee-id (fnil inc 0))))
             {} votes))

(defn yesterday-totals [{::game/keys [past-days] :as game}]
  (some->> past-days
           peek
           ::game/votes
           votes-by-votee-id))

(defn today-totals [{::game/keys [current-day] :as game}]
  (some->> current-day
           ::game/votes
           votes-by-votee-id))

(defn who-dies [{::game/keys [players] :as game}]
  (let [votes (if (day? game)
                (today-totals game)
                (yesterday-totals game))
        max-votes (apply max (vals votes))
        players-with-max-votes (into []
                                     (keep (fn [[id n]]
                                             (when (= n max-votes)
                                               id)))
                                     votes)]
    (when (and (> max-votes (quot (count players) 2))
               (= 1 (count players-with-max-votes)))
      (first players-with-max-votes))))

(defn nonvoters [{::game/keys [players]
                  {::game/keys [votes]} ::game/current-day
                  :as game}]
  (when (day? game)
    (set/difference (set (keys players)) (into #{} (map ::game/voter-id) votes))))

(defn invalidate-votes-on-player-death [game player-id]
  (cond-> game
    (day? game)
    (update-in [::game/current-day ::game/votes]
               #(mapv (fn [{::game/keys [voter-id votee-id] :as vote}]
                        (cond-> vote
                          (or (= player-id voter-id)
                              (= player-id votee-id))
                          (assoc ::game/invalidated? true)))
                      %))))

(defn kill [game player-id]
  (-> game
      (update ::game/players dissoc player-id)
      (invalidate-votes-on-player-death player-id)))

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

(defmethod process-event* ::event/nominate
  [game {::event/keys [nominator-id nominated-id]}]
  (nominate game nominator-id nominated-id))

(defmethod process-event* ::event/vote
  [game {::event/keys [voter-id votee-id]}]
  (vote game voter-id votee-id))

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
