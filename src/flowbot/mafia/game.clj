(ns flowbot.mafia.game
  (:require [clojure.tools.logging :as log]))


;; Game state manipulation
;; ----------------------------------------

(defn init-game [{:keys [moderator-id] :as game}]
  (assoc game
         :players #{}
         :past-days []))

(defn unstarted? [{:keys [current-day past-days] :as game}]
  (let [ret (and (nil? current-day) (empty? past-days))]
    (log/info {:game game
               :unstarted? ret})
    ret))

(def started? (complement unstarted?))

(defn join-game [game user-id]
  (cond-> game
    (unstarted? game) (update :players conj user-id)))

(defn leave-game [game user-id]
  (cond-> game
    (unstarted? game) (update :players disj user-id)))

(defn- day? [game]
  (contains? game :current-day))

(def ^:private night? (complement day?))

(defn start-day [{:keys [players] :as game}]
  (if (night? game)
    (assoc game :current-day {:votes {} :players players})
    game))

(defn end-day [{:keys [current-day] :as game}]
  (if (day? game)
    (-> game
        (update :past-days conj current-day)
        (dissoc :current-day))
    game))

(defn kill [game user-id]
  (update game :players disj user-id))

(defn revive [game user-id]
  (update game :players conj user-id))

(defn- alive? [{:keys [players]} user-id]
  (players user-id))

(defn vote [{:keys [players] :as game} user-id votee-id]
  (if (alive? game user-id)
    (update-in game [:current-day :votes] assoc user-id votee-id)
    game))

(defn unvote [game user-id]
  (vote game user-id nil))

(defn vote-totals [votes]
  (dissoc (reduce-kv (fn [totals _ votee-id]
                       (update totals votee-id (fnil inc 0)))
                     {}
                     votes)
          nil))

(defn yesterday-totals [{:keys [past-days]}]
  (when-not (empty? past-days)
    (-> past-days peek :votes vote-totals)))

(defn today-totals [{:keys [current-day]}]
  (when (some? current-day)
    (-> current-day :votes vote-totals)))

(defn nonvoters [{:keys [players]
                  {:keys [votes]} :current-day}]
  (filterv #(nil? (get votes %)) players))


;; Game state update events
;; ----------------------------------------

(defmulti process-event* (fn [_ {:keys [type]}] type))

(defmethod process-event* ::start-game
  [game _]
  (start-day game))

(defmethod process-event* ::join-game
  [game {:keys [player-id]}]
  (join-game game player-id))

(defmethod process-event* ::leave-game
  [game {:keys [player-id]}]
  (leave-game game player-id))

(defmethod process-event* ::vote
  [game {:keys [voter votee]}]
  (vote game voter votee))

(defmethod process-event* ::start-day
  [game _]
  (start-day game))

(defmethod process-event* ::end-day
  [game _]
  (end-day game))

(defmethod process-event* ::kill
  [game {:keys [player-id]}]
  (kill game player-id))

(defmethod process-event* ::revive
  [game {:keys [player-id]}]
  (revive game player-id))

(defn- push-event [game event]
  (update game ::events (fnil conj []) event))

(defn process-event [game event]
  (-> game
      (process-event* event)
      (push-event event)))

(defn replay-events
  [game events]
  (reduce process-event game events))

