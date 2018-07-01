(ns flowbot.mafia.data.event
  (:require [hugsql.core :as hugsql]
            [clojure.spec.alpha :as s]
            [flowbot.mafia.data.game :as game]
            [flowbot.data.postgres :as pg]
            [flowbot.data.datahike :as dh]
            [flowbot.util :as util]))

(hugsql/def-db-fns "flowbot/mafia/data/event.sql" {:quoting :ansi})

(def to-db util/de-ns-map-keys)

(defn from-db [db-event]
  (-> db-event
      (util/ns-map-keys "flowbot.mafia.data.event")
      (update-in [::payload ::type] keyword)))

(pg/def-wrapped-queries {:from-db from-db
                         :to-db to-db
                         :queries [insert-mafia-event!
                                   get-mafia-event-by-id
                                   get-mafia-events-by-mafia-game-id
                                   get-mafia-events-by-channel-id]})

;; Events
;; [::vote {:voter voter :votee votee}]
;; [::start-day]
;; [::end-day]
;; [::kill {:player-id player-id}]
;; [::revive {:player-id player-id}]
;; [::join-game {:player-id player-id}] -- consider a block feature
;; [::leave-game {:player-id player-id}]
;; [::start-game] -- assigns roles and starts day
;; [::end-registration]

(s/def ::type #{::vote
                ::unvote
                ::start-day
                ::end-day
                ::kill
                ::revive
                ::join-game
                ::leave-game
                ::end-registration})

(s/def ::player-id ::game/player-id)
(s/def ::voter-id ::player-id)
(s/def ::votee-id (s/nilable ::player-id))

(defmulti event-type ::type)
(s/def ::event (s/multi-spec event-type ::type))

(defmethod event-type ::vote [_]
  (s/keys :req [:db/id ::type ::voter-id ::votee-id]))

(defmethod event-type ::unvote [_]
  (s/keys :req [:db/id ::type ::voter-id]))

(defmethod event-type ::start-day [_]
  (s/keys :req [:db/id ::type]))

(defmethod event-type ::end-day [_]
  (s/keys :req [:db/id ::type]))

(defmethod event-type ::kill [_]
  (s/keys :req [:db/id ::type ::player-id]))

(defmethod event-type ::revive [_]
  (s/keys :req [:db/id ::type ::player-id]))

(defmethod event-type ::join-game [_]
  (s/keys :req [:db/id ::type ::player-id]))

(defmethod event-type ::leave-game [_]
  (s/keys :req [:db/id ::type ::player-id]))

(defmethod event-type ::end-registration [_]
  (s/keys :req [:db/id ::type]))


(s/def ::mafia-game-id :db/id)
(s/def ::created-at inst?)
(s/def ::payload ::event)
