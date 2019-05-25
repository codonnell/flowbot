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

(defn day? [{::game/keys [stage]}]
  (= ::game/day stage))

(defn night? [{::game/keys [stage]}]
  (= ::game/night stage))

(defn end-registration [{::game/keys [registered-players] :as game}]
  (assoc game
         ::game/stage ::game/role-distribution
         ::game/players registered-players
         ::game/dead-votes-used #{}))

(defn- registration-open? [{:keys [::game/stage]}]
  (= ::game/registration stage))

(defn join-game [{::game/keys [stage registered-players] :as game} {::player/keys [id] :as player}]
  (let [past-registration? (not= ::game/registration stage)
        new-player (assoc player ::player/index (count registered-players))]
    (cond-> game
      true (update ::game/registered-players assoc id new-player)
      past-registration? (update ::game/players assoc id new-player))))

(defn invalidate-votes-for-and-by-player [game player-id]
  (cond-> game
    (day? game)
    (update-in [::game/current-day ::game/votes]
               #(mapv (fn [{::game/keys [voter-id votee-id] :as vote}]
                        (cond-> vote
                          (or (= player-id voter-id)
                              (= player-id votee-id))
                          (assoc ::game/invalidated? true)))
                      %))))

(defn leave-game [{::game/keys [stage current-day] :as game} player-id]
  (cond-> game
    true (update ::game/registered-players dissoc player-id)
    (not= ::game/registration stage) (update ::game/players dissoc player-id)
    true (invalidate-votes-for-and-by-player player-id)))

(def daily-modifiers
  #{::witch})

(defn get-modifier [game player-id modifier-key]
  (get-in game [::game/registered-players player-id ::player/modifiers modifier-key]))

(defn assoc-modifier [game player-id modifier-key modifier-val]
  (cond-> game
    (contains? (::game/registered-players game) player-id)
    (assoc-in [::game/registered-players player-id ::player/modifiers modifier-key] modifier-val)))

(defn dissoc-modifier [game player-id modifier-key]
  (cond-> game
    (contains? (::game/registered-players game) player-id)
    (update-in [::game/registered-players player-id ::player/modifiers] dissoc modifier-key)))

(defn update-modifier [game player-id modifier-key update-fn]
  (cond-> game
    (contains? (::game/registered-players game) player-id)
    (update-in [::game/registered-players player-id ::player/modifiers modifier-key] update-fn)))

(defn start-day [{::game/keys [past-days stage] :as game}]
  (if (night? game)
    (assoc game
           ::game/current-day {::game/votes []}
           ::game/stage ::game/day)
    game))

(defn- remove-modifiers [game modifier-pred]
  (update game ::game/registered-players
          (fn [players]
            (into {}
                  (map (fn [[player-id player]]
                         [player-id (update player ::player/modifiers
                                            (fn [modifier-map]
                                              (into {}
                                                    (remove (fn [[k v]]
                                                              (modifier-pred k)))
                                                    modifier-map)))])
                       players)))))

(defn start-night [{::game/keys [current-day stage] :as game}]
  (if (#{::game/role-distribution ::game/day} stage)
    (cond-> game
      true (assoc ::game/stage ::game/night)
      current-day (update ::game/past-days conj current-day)
      true (dissoc ::game/current-day)
      true (remove-modifiers daily-modifiers))
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

(declare kill)

(defn is-nominator-witched?
  [{::game/keys [players] :as game} nominator-id]
  (and (true? (get-modifier game nominator-id ::witch))
       (< 3 (count players))))

(defn nominate [{::game/keys [players] :as game} nominator-id nominated-id]
  (let [will-nominate? (and (alive? game nominator-id)
                            (day? game)
                            (can-nominate? game nominator-id)
                            (can-be-nominated? game nominated-id))
        nominator-witched? (is-nominator-witched? game nominator-id)]
    (cond-> game
      will-nominate?
      (assoc-in [::game/current-day ::game/nominations nominated-id] nominator-id)
      (and will-nominate? nominator-witched?)
      (kill nominator-id))))

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
    (when (and (>= max-votes (/ (count players) 2))
               (= 1 (count players-with-max-votes)))
      (first players-with-max-votes))))

(defn nonvoters [{::game/keys [players]
                  {::game/keys [votes]} ::game/current-day
                  :as game}]
  (when (day? game)
    (set/difference (set (keys players)) (into #{} (map ::game/voter-id) votes))))

(defn kill [game player-id]
  (-> game
      (update ::game/players dissoc player-id)
      (invalidate-votes-for-and-by-player player-id)))

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

(defmethod process-event* ::event/witch
  [game {::event/keys [player-id]}]
  (assoc-modifier game player-id ::witch true))

(defmethod process-event* ::event/unwitch
  [game {::event/keys [player-id]}]
  (dissoc-modifier game player-id ::witch))

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
  (start-night game))

(defmethod process-event* ::event/start-night
  [game _]
  (start-night game))

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

