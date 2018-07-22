(ns flowbot.mafia.data.game
  (:require [hugsql.core :as hugsql]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [flowbot.mafia.data.player :as player]
            [flowbot.data.postgres :as pg]
            [flowbot.util :as util])
  (:import [flowbot.data.postgres Conn]))

(defprotocol Game
  (insert-mafia-game! [this game])
  (get-mafia-game-by-id [this id])
  (get-unfinished-mafia-game-by-channel-id [this channel-id])
  (finish-mafia-game-by-id! [this id]))

(hugsql/def-db-fns "flowbot/mafia/data/game.sql" {:quoting :ansi})

(let [q-ns "flowbot.mafia.data.game"]
  (extend Conn
    Game
    {:insert-mafia-game! (pg/wrap-query #'insert-mafia-game!* q-ns)
     :get-mafia-game-by-id (pg/wrap-query #'get-mafia-game-by-id* q-ns
                                          {:to-db (fn [id] {:id id})})
     :get-unfinished-mafia-game-by-channel-id (pg/wrap-query #'get-unfinished-mafia-game-by-channel-id*
                                                             q-ns
                                                             {:to-db (fn [id] {:channel-id id})})
     :finish-mafia-game-by-id! (pg/wrap-query #'finish-mafia-game-by-id!*
                                              q-ns
                                              {:to-db (fn [id] {:id id})})}))

;; Game data definition
;; ----------------------------------------

;; Note that we have not added events to the game spec. Events are a way of
;; recording changes to game state, but their existence is orthogonal to the
;; definition of current game state. While we add events to the game state map,
;; game functions should not know about or rely upon events.

(s/def ::players (s/map-of ::player/id ::player/player))
(s/def ::registered-players (s/map-of ::player/id ::player/player))

(s/def ::voter-id ::player/id)
(s/def ::votee-id (s/or :player ::player/id
                        :no-one #{::no-one}
                        :invalidated #{::invalidated}))
(s/def ::vote (s/keys :req [::voter-id ::votee-id]))
(s/def ::votes (s/coll-of ::vote :kind vector?))

(s/def ::day (s/keys :req [::votes]))
(s/def ::current-day (s/keys :req-un [::votes ::players]))
(s/def ::past-days (s/coll-of ::day :kind vector?))

(s/def ::stage #{::registration ::role-distribution ::day ::night ::finished})
(s/def ::moderator-id ::player/id)
(s/def ::channel-id pos-int?)
(s/def ::id uuid?)
(s/def ::created-at inst?)
(s/def ::finished-at inst?)

(s/def ::db-game (s/keys :req [::channel-id ::moderator-id ::id ::created-at]
                         :opt [::finished-at]))

(defmulti game-stage ::stage)

(defmethod game-stage ::registration [_]
  (s/keys :req [::stage ::registered-players ::channel-id ::moderator-id ::id ::created-at]))

(defmethod game-stage ::role-distribution [_]
  (s/keys :req [::stage ::registered-players ::players ::channel-id ::moderator-id ::id ::created-at]))

(defmethod game-stage ::day [_]
  (s/keys :req [::stage ::registered-players ::players ::channel-id ::moderator-id ::id ::created-at ::current-day ::past-days]))

(defmethod game-stage ::day [_]
  (s/keys :req [::stage ::registered-players ::players ::channel-id ::moderator-id ::id ::created-at ::past-days]))

(defmethod game-stage ::finished [_]
  (s/keys :req [::stage ::registered-players ::players ::channel-id ::moderator-id ::id ::created-at ::past-days ::finished-at]))

(s/def ::game (s/multi-spec game-stage ::stage))

