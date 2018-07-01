(ns flowbot.data.datahike
  (:require [datahike.api :as d]
            [integrant.core :as ig]
            [clojure.spec.alpha :as s]
            [java-time :as time]
            [flowbot.registrar :as reg]))

(s/def :db/id pos-int?)

;; Should probably bring this out into a type ns
(def schema {:flowbot.mafia.data.game/events {:db/cardinality :db.cardinality/many
                                              :db/valueType :db.type/ref
                                              :db/isComponent true}
             :flowbot.mafia.data.game/days {:db/cardinality :db.cardinality/many
                                            :db/valueType :db.type/ref
                                            :db/isComponent true}
             :flowbot.mafia.data.game/votes {:db/cardinality :db.cardinality/many
                                             :db/valueType :db.type/ref
                                             :db/isComponent true}})

(def ^:private inject-int-name ::inject-conn)

(defn inject-conn
  "Given a database connection, returns an interceptor that injects the database
  connection under the key :flowbot.data.datahike/conn."
  [conn]
  {:name inject-int-name
   :enter (fn [ctx]
            (assoc ctx ::conn conn))})

(defmethod ig/init-key :datahike/connection [_ {:keys [uri]}]
  (d/create-database-with-schema uri schema)
  (let [conn (d/connect uri)]
    (reg/register! :interceptor inject-int-name (inject-conn conn))
    conn))

(defmethod ig/halt-key! :datahike/connection [_ conn]
  (reg/unregister! :interceptor inject-int-name)
  (d/release conn))

(defn decorate-tx [tx]
  "Annotates the tx with :db/txInstant"
  (conj tx {:db/id (d/tempid :db.part/tx)
            :db.txInstant (time/instant)}))
