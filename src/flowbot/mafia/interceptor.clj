(ns flowbot.mafia.interceptor
  (:require [flowbot.mafia.game :as game]
            [flowbot.mafia.data.event :as data.event]
            [flowbot.mafia.data.game :as data.game]
            [flowbot.data.postgres :as pg]
            [flowbot.discord.action :as discord.action]
            [flowbot.interceptor.registrar :as int.reg]
            [flowbot.util :as util]
            [io.pedestal.interceptor.chain :as int.chain]
            [clojure.data]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defn- msg->channel-id [{:keys [channel-id]}]
  (Long/parseLong channel-id))

(defn- db-event->event [db-event]
  (-> db-event
      ::data.event/payload
      (update ::data.event/type keyword)))

(defn- restore-game [db-game db-events]
  (let [game (game/init-game db-game)
        events (mapv db-event->event db-events)]
    (game/replay-events game events)))

(defn- add-reply-text [ctx text]
  (update ctx :effects assoc ::discord.action/reply {:content text}))

(defn- terminate-with-reply
  [ctx text]
  (int.chain/terminate (add-reply-text ctx text)))

(def current-game-lens
  "Interceptor that injects the current game state as a value assoc'd to the
  :flowbot.mafia.game/game key on :enter. Assumes there is a database connection
  assoc'd to the :flowbot.data.postgres/conn key and a event assoc'd to the
  :event key.

  On :leave, inserts into the database any new events assoc'd to
  [:effects :flowbot.mafia.game/update-game]. Does nothing if the update-game
  value is nil."
  {:name ::inject-current-game
   :enter (fn [{:keys [::pg/conn event] :as ctx}]
            (let [channel-id (msg->channel-id event)
                  game (data.game/get-unfinished-mafia-game-by-channel-id conn channel-id)
                  events (when game
                           (data.event/get-mafia-events-by-mafia-game-id conn (::data.game/id game)))]
              (if game
                (assoc ctx ::game/game (restore-game game events))
                (terminate-with-reply ctx "There is no game currently running."))))
   :leave (fn [{:keys [::pg/conn event ::game/game effects] :as ctx}]
            (if-some [updated-game (::game/update-game effects)]
              (let [[_ nils-then-new-events _] (clojure.data/diff (::data.event/events game)
                                                                  (::data.event/events updated-game))]
                (jdbc/with-db-transaction [tx conn]
                  (doseq [new-event (drop-while nil? nils-then-new-events)]
                    (data.event/insert-mafia-event! tx {:mafia-game-id (::data.game/id game)
                                                        :payload new-event})))
                (update ctx :effects dissoc ::game/update-game))
              ctx))})

(def no-game-running
  {:name ::no-gaming-running
   :enter (fn [{:keys [::pg/conn event] :as ctx}]
            (let [channel-id (msg->channel-id event)
                  game (data.game/get-unfinished-mafia-game-by-channel-id conn channel-id)]
              (if-not (seq game)
                ctx
                (terminate-with-reply ctx "There is a game currently running."))))})

(defn- or-join [xs]
  (let [first-xs (butlast xs)
        last-x (last xs)]
    (str (str/join ", " first-xs) ", or " last-x)))

(defn- pr-kw [kw]
  (-> kw name (str/replace "-" " ")))

(defn- pr-kw-coll [kw-coll]
  (cond (= 1 (count kw-coll)) (pr-kw (first kw-coll))
        (= 2 (count kw-coll)) (str (pr-kw (first kw-coll)) " or " (pr-kw (second kw-coll)))
        :else (or-join (mapv pr-kw kw-coll))))

(defn stage
  "Interceptor that takes a set of stages
  from :flowbot.mafia.data.game/{registration,role-distribution,day,night} and
  terminates with an error message if the game is not in one of the prescribed
  stages. Assumes the game is associated to the :flowbot.mafia.data.game key in
  the context map."
  [stages]
  {:pre [(set? stages) (pos? (count stages))]}
  {:name ::stage
   :enter (fn [{::game/keys [game] :as ctx}]
            (if (stages (::data.game/stage game))
              ctx
              (terminate-with-reply ctx (str "The stage must be "
                                             (pr-kw-coll stages)
                                             " for that command."))))})

(defmulti has-role? (fn [_game _user-id role] role))

(defmethod has-role? :default [_ _ _] false)

(defmethod has-role? ::data.game/moderator [{::data.game/keys [moderator-id]} user-id _]
  (= moderator-id user-id))

(defmethod has-role? ::data.game/player [{::data.game/keys [registered-players]} user-id _]
  (contains? registered-players user-id))

(defmethod has-role? ::data.game/not-player [{::data.game/keys [registered-players] :as game} user-id _]
  (not (contains? registered-players user-id)))

(defmethod has-role? ::data.game/alive [{::data.game/keys [players]} user-id _]
  (contains? players user-id))

(defmethod has-role? ::data.game/dead [{::data.game/keys [players registered-players]} user-id _]
  (and (contains? registered-players user-id) (not (contains? players user-id))))

(defn role
  "Interceptor that takes a set of roles
  from :flowbot.mafia.data.game/{moderator,player,alive,dead} and
  terminates with an error message if the user is not in one of the prescribed
  roles. Assumes the game is associated to the :flowbot.mafia.data.game key in
  the context map."
  [roles]
  {:pre [(pos? (count roles))]}
  {:name ::role
   :enter
   (fn [{:keys [::game/game event] :as ctx}]
     (let [user-id (util/parse-long (get-in event [:author :id]))]
       (if (some #(has-role? game user-id %) roles)
         ctx
         (terminate-with-reply
          ctx
          (str "You must be "
               (str/replace (pr-kw-coll roles) "player" "a player")
               " for that command.")))))})

(defn mentions-role
  "Interceptor that takes a set of roles from
  :flowbot.mafia.data.game/{moderator,player,alive,dead} and terminates with an
  error message if the first mention is not in one of the prescribed roles.
  Assumes the game is associated to the :flowbot.mafia.data.game key in the
  context map."
  [roles]
  {:pre [(pos? (count roles))]}
  {:name ::mentions-role
   :enter
   (fn [{:keys [::game/game event] :as ctx}]
     (let [mentioned-id (some-> event :mentions first :id util/parse-long)]
       (if (and mentioned-id (some #(has-role? game mentioned-id %) roles))
         ctx
         (terminate-with-reply
          ctx
          (str "You must mention (using @) someone who's "
               (str/replace (pr-kw-coll roles) "player" "a player")
               " for that command.")))))})
