(ns flowbot.mafia.interceptor
  (:require [flowbot.mafia.game :as game]
            [flowbot.mafia.data.event :as data.event]
            [flowbot.mafia.data.game :as data.game]
            [flowbot.data.postgres :as pg]
            [flowbot.data.datahike :as dh]
            [flowbot.discord.action :as discord.action]
            [flowbot.interceptor.registrar :as int.reg]
            [io.pedestal.interceptor.chain :as int.chain]
            [datahike.api :as d]
            [clojure.data]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [clojure.string :as str]))

(defn- db-event->event [db-event]
  (-> db-event
      :payload
      (update ::game/type keyword)))

(defn- restore-game [db-game db-events]
  (let [game (game/init-game db-game)
        events (mapv db-event->event db-events)]
    (game/replay-events game events)))

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
            (let [channel-id (get-in event [:channel :id])
                  game (data.game/get-unfinished-mafia-game-by-channel-id conn {:channel-id channel-id})
                  events (when game
                           (data.event/get-mafia-events-by-mafia-game-id conn {:mafia-game-id (:id game)}))]
              (cond-> ctx
                game (assoc ctx ::game/game (restore-game game events)))))
   :leave (fn [{:keys [::pg/conn event ::game/game effects] :as ctx}]
            (if-some [updated-game (::game/update-game effects)]
              (let [[_ nils-then-new-events _] (clojure.data/diff (::game/events game)
                                                                  (::game/events updated-game))]
                (jdbc/with-db-transaction [tx conn]
                  (doseq [new-event (drop-while nil? nils-then-new-events)]
                    (data.event/insert-mafia-event! tx {:mafia-game-id (:id game)
                                                        :payload new-event}))))
              ctx))})

(defn- add-reply-text [ctx text]
  (update ctx :effects assoc ::discord.action/reply {:content text}))

(defn- terminate-with-reply
  [ctx text]
  (int.chain/terminate (add-reply-text ctx text)))

(def no-game-running
  (int.reg/interceptor
   {:name ::no-game-running
    :enter (fn [{:keys [::dh/conn event] :as ctx}]
             (let [game (d/q `[:find ?g .
                               :where
                               [?g ::data.game/channel-id ~(:channel-id event)]
                               [?g ::data.game/finished? false]]
                             (d/db conn))]
               (if-not game
                 ctx
                 (terminate-with-reply ctx "There is a game currently running."))))}))

(def dh-current-game-lens
  {:name ::dh-inject-current-game
   :enter (fn [{:keys [::dh/conn event] :as ctx}]
            (let [game (d/q `[:find (pull ?g [* {::data.game/events [*]}]) .
                              :where
                              [?g ::data.game/channel-id ~(:channel-id event)]
                              [?g ::data.game/finished? false]]
                            (d/db conn))]
              (if game
                (assoc ctx ::game/game game)
                (terminate-with-reply ctx "There is no game currently running."))))
   :leave (fn [{:keys [::dh/conn effects] :as ctx}]
            (when-some [updated-game (::game/update-game effects)]
              @(d/transact conn [updated-game]))
            (update ctx :effects dissoc ::game/update-game))})

(defn- or-join [xs]
  (let [first-xs (butlast xs)
        last-x (last xs)]
    (str (str/join ", " first-xs) ", or " last-x)))

(defn- pr-kw-coll [kw-coll]
  (cond (= 1 (count kw-coll)) (name (first kw-coll))
        (= 2 (count kw-coll)) (str (name (first kw-coll)) " or " (name (second kw-coll)))
        :else (or-join (mapv name kw-coll))))

(defn stage
  "Interceptor that takes a set of stages
  from :flowbot.mafia.data.game/{registration,role-distribution,day,night} and
  terminates with an error message if the game is not in one of the prescribed
  stages. Assumes the game is associated to the :flowbot.mafia.data.game key in
  the context map."
  [stages]
  {:pre [(set? stages) (pos? (count stages))]}
  {:name ::stage
   :enter (fn [{:keys [::game/game] :as ctx}]
            (if (stages (::data.game/stage game))
              ctx
              (terminate-with-reply ctx (str "The stage must be "
                                             (pr-kw-coll stages)
                                             " for that command."))))})

(defmulti has-role? (fn [_game _user-id role] role))

(defmethod has-role? :default [_ _ _] false)

(defmethod has-role? :moderator [{:keys [::data.game/moderator-id]} user-id _]
  (= moderator-id user-id))

(defmethod has-role? :player [{:keys [::data.game/registered-players]} user-id _]
  (boolean (registered-players user-id)))

(defmethod has-role? :alive [{:keys [::data.game/players]} user-id _]
  (boolean (players user-id)))

(defmethod has-role? :dead [{:keys [::data.game/players ::data.game/registered-players]}
                            user-id _]
  (boolean (and (registered-players user-id)
                (not (players user-id)))))

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
     (let [user-id (get-in event [:author :id])]
       (if (some #(has-role? game user-id %) roles)
         ctx
         (terminate-with-reply
          ctx
          (str "You must be "
               (string/replace (pr-kw-coll roles) "player" "a player")
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
     (let [mentioned-id (-> event :user-mentions first :id)]
       (if (some #(has-role? game mentioned-id %) roles)
         ctx
         (terminate-with-reply
          ctx
          (str "You must mention (using @) someone who's "
               (string/replace (pr-kw-coll roles) "player" "a player")
               " for that command.")))))})
