(ns flowbot.botc.data.event
  (:require [hugsql.core :as hugsql]
            [clojure.spec.alpha :as s]
            [flowbot.botc.data.game :as game]
            [flowbot.botc.data.player :as player]
            [flowbot.data.postgres :as pg]
            [flowbot.util :as util])
  (:import [flowbot.data.postgres Conn]))

(defprotocol Event
  (insert-botc-event! [this event])
  (get-botc-event-by-id [this id])
  (get-botc-events-by-botc-game-id [this botc-game-id])
  (get-botc-events-by-channel-id [this channel-id]))

(hugsql/def-db-fns "flowbot/botc/data/event.sql" {:quoting :ansi})

(let [q-ns "flowbot.botc.data.event"]
  (extend Conn
    Event
    {:insert-botc-event! (pg/wrap-query #'insert-botc-event!* q-ns)
     :get-botc-event-by-id (pg/wrap-query #'get-botc-event-by-id* q-ns
                                          {:to-db (fn [id] {:id id})})
     :get-botc-events-by-botc-game-id (pg/wrap-query #'get-botc-events-by-botc-game-id*
                                                     q-ns
                                                     {:to-db (fn [id] {:botc-game-id id})})
     :get-botc-events-by-channel-id (pg/wrap-query #'get-botc-events-by-channel-id*
                                                   q-ns
                                                   {:to-db (fn [id] {:channel-id id})})}))

;; Events
;; [::vote {:voter-id voter :votee-id votee}]
;; [::nominate {:nominator-id nominator :nominated-id nominated-id}]
;; [::start-day]
;; [::end-day]
;; [::kill {:player-id player-id}]
;; [::revive {:player-id player-id}]
;; [::join-game {:player-id player-id}] -- consider a block feature
;; [::leave-game {:player-id player-id}]
;; [::start-game] -- assigns roles and starts day
;; [::end-registration]

(s/def ::type #{::vote
                ::nominate
                ::start-day
                ::end-day
                ::kill
                ::revive
                ::join-game
                ::leave-game
                ::start-game
                ::end-registration})

(s/def ::player-id ::player/id)
(s/def ::voter-id ::player-id)
(s/def ::votee-id ::player-id)
(s/def ::nominator-id ::player-id)
(s/def ::nominated-id ::player-id)

(defmulti event-type ::type)
(s/def ::event (s/multi-spec event-type ::type))

(defmethod event-type ::vote [_]
  (s/keys :req [::type ::voter-id ::votee-id]))

(defmethod event-type ::nominate [_]
  (s/keys :req [::type ::nominator-id ::nominated-id]))

(defmethod event-type ::start-day [_]
  (s/keys :req [::type]))

(defmethod event-type ::end-day [_]
  (s/keys :req [::type]))

(defmethod event-type ::start-night [_]
  (s/keys :req [::type]))

(defmethod event-type ::kill [_]
  (s/keys :req [::type ::player-id]))

(defmethod event-type ::revive [_]
  (s/keys :req [::type ::player-id]))

(defmethod event-type ::join-game [_]
  (s/keys :req [::type ::player-id]))

(defmethod event-type ::leave-game [_]
  (s/keys :req [::type ::player-id]))

(defmethod event-type ::start-game [_]
  (s/keys :req [::type]))

(defmethod event-type ::end-registration [_]
  (s/keys :req [::type]))


(s/def ::id uuid?)
(s/def ::botc-game-id ::game/id)
(s/def ::created-at inst?)
(s/def ::payload ::event)
