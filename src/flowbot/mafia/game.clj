(ns flowbot.mafia.game
  (:require [clojure.tools.logging :as log]
            [clojure.spec.alpha :as s]
            [flowbot.mafia.data.game :as game]
            [flowbot.mafia.data.event :as event]))


;; Game data manipulation
;; ----------------------------------------

(defn init-game [{:keys [moderator-id] :as game}]
  (assoc game
         ::game/stage ::game/registration
         ::game/registered-players #{}
         ::game/past-days []))

(defn unstarted? [{:keys [::game/stage] :as game}]
  (#{::game/registration ::game/role-distribution} stage))

(def started? (complement unstarted?))

(defn end-registration [{:keys [::game/registered-players] :as game}]
  (assoc game
         ::game/stage ::game/role-distribution
         ::game/players registered-players))

(defn- registration-open? [{:keys [::game/stage]}]
  (= ::game/registration stage))

(defn join-game [game player-id]
  (cond-> game
    (registration-open? game) (update ::game/registered-players conj player-id)))

(defn leave-game [game player-id]
  (cond-> game
    (registration-open? game) (update ::game/registered-players disj player-id)))

(defn day? [{:keys [::game/stage]}]
  (= ::game/day stage))

(defn night? [{:keys [::game/stage]}]
  (= ::game/night stage))

(defn start-day [{:keys [::game/past-days ::game/stage] :as game}]
  (if (#{::game/role-distribution ::game/night} stage)
    (assoc game
           ::game/current-day {::game/votes {}}
           ::game/stage ::game/day)
    game))

(defn end-day [{:keys [::game/current-day] :as game}]
  (if (day? game)
    (-> game
        (assoc ::game/stage ::game/night)
        (update ::game/past-days conj current-day)
        (dissoc ::game/current-day))
    game))

(defn kill [game player-id]
  (-> game
      (update ::game/players disj player-id)
      (update-in [::game/current-day ::game/votes] dissoc player-id)))

(defn revive [{:keys [::game/registered-players] :as game} player-id]
  (cond-> game
    (registered-players player-id) (update ::game/players conj player-id)))

(defn- alive? [{:keys [::game/players]} player-id]
  (players player-id))

(defn vote [{:keys [::game/players] :as game} voter-id votee-id]
  (if (and (alive? game voter-id) (day? game))
    (update-in game [::game/current-day ::game/votes] assoc voter-id votee-id)
    game))

(defn unvote [game voter-id]
  (if (and (alive? game voter-id) (day? game))
    (update-in game [::game/current-day ::game/votes] dissoc voter-id)))

(defn vote-totals [votes]
  (reduce-kv (fn [totals _ votee-id]
               (update totals votee-id (fnil inc 0)))
             {}
             votes))

(defn yesterday-totals [{:keys [::game/past-days]}]
  (when-not (empty? past-days)
    (-> past-days peek ::game/votes vote-totals)))

(defn today-totals [{:keys [::game/current-day]}]
  (when (some? current-day)
    (-> current-day ::game/votes vote-totals)))

(defn nonvoters [{:keys [::game/players ::game/current-day]
                  {:keys [::game/votes]} ::game/current-day}]
  (when (some? current-day)
    (into #{} (remove #(contains? votes %)) players)))


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
  [game {:keys [::event/player-id]}]
  (join-game game player-id))

(defmethod process-event* ::event/leave-game
  [game {:keys [::event/player-id]}]
  (leave-game game player-id))

(defmethod process-event* ::event/vote
  [game {:keys [::event/voter ::event/votee]}]
  (vote game voter votee))

(defmethod process-event* ::event/start-day
  [game _]
  (start-day game))

(defmethod process-event* ::event/end-day
  [game _]
  (end-day game))

(defmethod process-event* ::event/kill
  [game {:keys [::event/player-id]}]
  (kill game player-id))

(defmethod process-event* ::event/revive
  [game {:keys [::event/player-id]}]
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

