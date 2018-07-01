(ns flowbot.mafia.game
  (:require [clojure.tools.logging :as log]
            [clojure.spec.alpha :as s]
            [clojure.set :as set]
            [flowbot.mafia.data.game :as game]
            [flowbot.mafia.data.event :as event]))


;; Game data manipulation
;; ----------------------------------------

(defn init-game [game]
  (assoc game
         ::game/stage ::game/registration
         ::game/registered-players #{}
         ::game/days []))

(defn unstarted? [{::game/keys [stage] :as game}]
  (#{::game/registration ::game/role-distribution} stage))

(def started? (complement unstarted?))

(defn end-registration [{::game/keys [registered-players] :as game}]
  (assoc game
         ::game/stage ::game/role-distribution
         ::game/players registered-players))

(defn- registration-open? [{::game/keys [stage]}]
  (= ::game/registration stage))

(defn join-game [game player-id]
  (cond-> game
    (registration-open? game) (update ::game/registered-players conj player-id)))

(defn leave-game [game player-id]
  (cond-> game
    (registration-open? game) (update ::game/registered-players disj player-id)))

(defn day? [{::game/keys [stage]}]
  (= ::game/day stage))

(defn night? [{::game/keys [stage]}]
  (= ::game/night stage))

(defn start-day [{::game/keys [past-days stage] :as game}]
  (if (#{::game/role-distribution ::game/night} stage)
    (-> game
        (update ::game/days conj {::game/votes []})
        (assoc ::game/stage ::game/day))
    game))

(defn end-day [{::game/keys [current-day] :as game}]
  (if (day? game)
    (assoc game ::game/stage ::game/night)
    game))

(defn- update-current-day [{::game/keys [days] :as game} f & args]
  (update-in game [::game/days (dec (count days))] #(apply f % args)))

(defn- update-current-votes [game f & args]
  (update-current-day game update ::game/votes #(apply f % args)))

(defn- alive? [{::game/keys [players] :as game} player-id]
  (players player-id))

(defn vote [{::game/keys [players] :as game} voter-id votee-id]
  (if (and (alive? game voter-id) (day? game))
    (update-current-votes game conj #::game{:voter-id voter-id
                                            :votee-id votee-id})
    game))

(defn unvote [game voter-id]
  (if (and (alive? game voter-id) (day? game))
    (update-current-votes game conj #::game{:voter-id voter-id
                                            :votee-id nil})
    game))

(defn today-votes [{::game/keys [days]}]
  (::game/votes (peek days)))

(defn valid-today-votes [{::game/keys [players] :as game}]
  (filterv (comp players ::game/voter-id) (today-votes game)))

(defn votes-by-voter-id [votes]
  (reduce (fn [votes-by-voter {::game/keys [voter-id votee-id] :as vote}]
            (assoc votes-by-voter voter-id votee-id))
          {}
          votes))

(defn vote-totals [votes]
  (reduce-kv (fn [totals _ votee-id]
               (update totals votee-id (fnil inc 0)))
             nil
             (votes-by-voter-id votes)))

(defn yesterday-totals [{::game/keys [days stage]}]
  (cond-> days
    (= ::game/day stage) pop
    true (-> peek ::game/votes vote-totals)))

(defn today-totals [{::game/keys [stage] :as game}]
  (when (= ::game/day stage)
    (or (vote-totals (valid-today-votes game)) {})))

(defn nonvoters [{::game/keys [players days stage] :as game}]
  (when (= ::game/day stage)
    (let [votes (valid-today-votes game)]
      (set/difference players
                      (into #{}
                            (keep (fn [[voter-id votee-id]] (when votee-id voter-id)))
                            (votes-by-voter-id votes))))))

(defn kill [game player-id]
  (update game ::game/players disj player-id))

(defn revive [{:keys [::game/registered-players] :as game} player-id]
  (cond-> game
    (registered-players player-id) (update ::game/players conj player-id)))


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

(defmulti process-event* (fn [_ {:keys [::event/type]}] type))

(defmethod process-event* ::event/end-registration
  [game _]
  (end-registration game))

(defmethod process-event* ::event/join-game
  [game {:keys [::event/player-id]}]
  (join-game game player-id))

(defmethod process-event* ::event/leave-game
  [game {:keys [::event/player-id]}]
  (leave-game game player-id))

(defmethod process-event* ::event/vote
  [game {:keys [::event/voter-id ::event/votee-id]}]
  (vote game voter-id votee-id))

(defmethod process-event* ::event/unvote
  [game {:keys [::event/voter-id]}]
  (unvote game voter-id))

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

(defn- push-event [game event]
  (update game ::event/events (fnil conj []) event))

(defn process-event [game event]
  (-> game
      (process-event* event)
      (push-event event)))

(defn replay-events
  [game events]
  (reduce process-event game events))

