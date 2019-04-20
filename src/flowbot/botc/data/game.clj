(ns flowbot.botc.data.game
  (:require [hugsql.core :as hugsql]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [flowbot.botc.data.player :as player]
            [flowbot.data.postgres :as pg]
            [flowbot.util :as util])
  (:import [flowbot.data.postgres Conn]))

(defprotocol Game
  (insert-botc-game! [this game])
  (get-botc-game-by-id [this id])
  (get-unfinished-botc-game-by-channel-id [this channel-id])
  (get-unfinished-botc-games [this])
  (finish-botc-game-by-id! [this id]))

(hugsql/def-db-fns "flowbot/botc/data/game.sql" {:quoting :ansi})

(let [q-ns "flowbot.botc.data.game"]
  (extend Conn
    Game
    {:insert-botc-game! (pg/wrap-query #'insert-botc-game!* q-ns)
     :get-botc-game-by-id (pg/wrap-query #'get-botc-game-by-id* q-ns
                                         {:to-db (fn [id] {:id id})})
     :get-unfinished-botc-game-by-channel-id (pg/wrap-query #'get-unfinished-botc-game-by-channel-id*
                                                            q-ns
                                                            {:to-db (fn [id] {:channel-id id})})
     :get-unfinished-botc-games (pg/wrap-query #'get-unfinished-botc-games*
                                               q-ns)
     :finish-botc-game-by-id! (pg/wrap-query #'finish-botc-game-by-id!*
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
(s/def ::votee-id ::player/id)
(s/def ::invalidated? boolean)
(s/def ::vote (s/keys :req [::voter-id ::votee-id]
                      :opt [::invalidated?]))
(s/def ::votes (s/coll-of ::vote :kind vector?))

(s/def ::dead-votes-used (s/coll-of ::player/id :kind set?))

(s/def ::nominations (s/map-of ::player/id ::player/id))

(s/def ::day (s/keys :req [::votes ::nominations]))
(s/def ::current-day (s/keys :req-un [::votes ::nominations ::players]))
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
  (s/keys :req [::stage ::registered-players ::players ::channel-id ::moderator-id ::id ::created-at ::dead-votes-used]))

(defmethod game-stage ::day [_]
  (s/keys :req [::stage ::registered-players ::players ::channel-id ::moderator-id ::id ::created-at ::current-day ::past-days ::dead-votes-used]))

(defmethod game-stage ::night [_]
  (s/keys :req [::stage ::registered-players ::players ::channel-id ::moderator-id ::id ::created-at ::past-days ::dead-votes-used]))

(defmethod game-stage ::finished [_]
  (s/keys :req [::stage ::registered-players ::players ::channel-id ::moderator-id ::id ::created-at ::past-days ::finished-at ::dead-votes-used]))

(s/def ::game (s/multi-spec game-stage ::stage))

