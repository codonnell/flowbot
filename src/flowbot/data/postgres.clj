(ns flowbot.data.postgres
  (:require [flowbot.registrar :as reg]
            [integrant.core :as ig]
            [cheshire.core :as json]
            hugsql.adapter
            hugsql.core
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as csk-extras]
            [clojure.java.jdbc :as jdbc])
  (:import com.mchange.v2.c3p0.ComboPooledDataSource
           org.postgresql.util.PGobject))

(def ^:private inject-int-name ::inject-conn)

(defn inject-conn
  "Given a database connection, returns an interceptor that injects the database
  connection under the key :flowbot.data.postgres/conn."
  [conn]
  {:name inject-int-name
   :before (fn [ctx]
             (assoc ctx ::conn conn))})

(defmethod ig/init-key :postgres/connection [_ {:keys [dbtype dbname host port user pass]}]
  (let [url (str "jdbc:" dbtype "://" host ":" port "/" dbname)
        pool {:datasource (doto (ComboPooledDataSource.)
                            (.setDriverClass "org.postgresql.Driver")
                            (.setJdbcUrl url)
                            (.setUser user)
                            (.setPassword pass))}]
    (reg/register! :interceptor inject-int-name (inject-conn pool))
    pool))

(defmethod ig/halt-key! :rainbot/db [_ pool]
  (reg/unregister! :interceptor inject-int-name)
  (.close (:datasource pool)))

(defn value-to-json-pgobject [value]
  (doto (PGobject.)
    (.setType "json")
    (.setValue (json/encode value))))

(extend-protocol jdbc/ISQLValue
  clojure.lang.IPersistentMap
  (sql-value [value] (value-to-json-pgobject value))

  clojure.lang.IPersistentVector
  (sql-value [value] (value-to-json-pgobject value)))

(extend-protocol jdbc/IResultSetReadColumn
  PGobject
  (result-set-read-column [pgobj metadata idx]
    (let [type  (.getType pgobj)
          value (.getValue pgobj)]
      (case type
        ("json" "jsonb") (json/decode value true)
        :else value))))

(def memoized-kebab-keyword-ulate (memoize csk/->kebab-case-keyword))

(defn result-one-snake->kebab
  [this result options]
  (->> (hugsql.adapter/result-one this result options)
       (csk-extras/transform-keys memoized-kebab-keyword-ulate)))

(defn result-many-snake->kebab
  [this result options]
  (->> (hugsql.adapter/result-many this result options)
       (mapv #(csk-extras/transform-keys memoized-kebab-keyword-ulate %))))

(defmethod hugsql.core/hugsql-result-fn :1 [sym] 'flowbot.data.postgres/result-one-snake->kebab)
(defmethod hugsql.core/hugsql-result-fn :one [sym] 'flowbot.data.postgres/result-one-snake->kebab)
(defmethod hugsql.core/hugsql-result-fn :* [sym] 'flowbot.data.postgres/result-many-snake->kebab)
(defmethod hugsql.core/hugsql-result-fn :many [sym] 'flowbot.data.postgres/result-many-snake->kebab)
