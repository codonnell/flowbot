(ns flowbot.mafia.data.game
  (:require [hugsql.core :as hugsql]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [flowbot.data.postgres :as pg]
            [flowbot.data.datahike :as dh]
            [flowbot.util :as util]))

(hugsql/def-db-fns "flowbot/mafia/data/game.sql" {:quoting :ansi})

(def to-db util/de-ns-map-keys)

(defn from-db [db-game]
  (-> db-game
      util/remove-nil-vals
      (util/ns-map-keys "flowbot.mafia.data.game")))

(pg/def-wrapped-queries {:from-db from-db
                         :to-db to-db
                         :queries [insert-mafia-game!
                                   get-mafia-game-by-id
                                   get-latest-mafia-game-by-channel-id
                                   get-unfinished-mafia-game-by-channel-id
                                   get-unfinished-mafia-games
                                   finish-mafia-game-by-id!]})

;; Game data definition
;; ----------------------------------------

;; Note that we have not added events to the game spec. Events are a way of
;; recording changes to game state, but their existence is orthogonal to the
;; definition of current game state. While we add events to the game state map,
;; game functions should not know about or rely upon events.

(s/def ::player-id pos-int?)
(s/def ::players (s/coll-of ::player-id :kind set?))
(s/def ::registered-players (s/coll-of ::player-id :kind set?))

(s/def ::voter ::player-id)
(s/def ::votee (s/nilable ::player-id))
(s/def ::vote (s/keys :req [::voter ::votee]
                      :opt [:db/id]))
(s/def ::votes (s/coll-of ::vote))

(s/def ::day (s/keys :req [::votes]
                     :opt [:db/id]))
(s/def ::days (s/coll-of ::day))

(s/def ::stage #{::registration ::role-distribution ::day ::night ::finished})
(s/def ::moderator-id ::player-id)
(s/def ::channel-id pos-int?)
(s/def ::created-at inst?)
(s/def ::finished-at inst?)

(defmulti game-stage ::stage)

(defmethod game-stage ::registration [_]
  (s/keys :req [::stage ::registered-players ::channel-id ::moderator-id ::created-at]
          :opt [:db/id]))

(defmethod game-stage ::role-distribution [_]
  (s/keys :req [::stage ::registered-players ::players ::channel-id ::moderator-id ::created-at]
          :opt [:db/id]))

(defmethod game-stage ::day [_]
  (s/keys :req [::stage ::registered-players ::players ::channel-id ::moderator-id ::created-at ::days]
          :opt [:db/id]))

(defmethod game-stage ::night [_]
  (s/keys :req [::stage ::registered-players ::players ::channel-id ::moderator-id ::created-at ::days]
          :opt [:db/id]))

(defmethod game-stage ::finished [_]
  (s/keys :req [::stage ::registered-players ::players ::channel-id ::moderator-id ::created-at ::days ::finished-at]
          :opt [:db/id]))

(s/def ::game (s/multi-spec game-stage ::stage))

(s/def ::initial-game
  (s/keys :req [::channel-id ::moderator-id ::created-at]))

