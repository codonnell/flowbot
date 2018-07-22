(ns flowbot.data.postgres
  (:require [flowbot.registrar :as reg]
            [flowbot.util :as util]
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
   :enter (fn [ctx]
            (assoc ctx ::conn conn))})

(defrecord Conn [datasource])

(defmethod ig/init-key :postgres/connection [_ {:keys [dbtype dbname host port user password]}]
  (let [url (str "jdbc:" dbtype "://" host ":" port "/" dbname)
        pool (map->Conn
              {:datasource (doto (ComboPooledDataSource.)
                             (.setDriverClass "org.postgresql.Driver")
                             (.setJdbcUrl url)
                             (.setUser user)
                             (.setPassword password))})]
    (reg/register! :interceptor inject-int-name (inject-conn pool))
    pool))

(defmethod ig/halt-key! :rainbot/db [_ pool]
  (reg/unregister! :interceptor inject-int-name)
  (.close (:datasource pool)))

(defn value-to-json-pgobject [value]
  (doto (PGobject.)
    (.setType "json")
    (.setValue (json/encode value))))

(extend-protocol clojure.java.jdbc/ISQLParameter
  clojure.lang.IPersistentVector
  (set-parameter [v ^java.sql.PreparedStatement stmt ^long i]
    (let [conn (.getConnection stmt)
          meta (.getParameterMetaData stmt)
          type-name (.getParameterTypeName meta i)]
      (if-let [elem-type (when (= (first type-name) \_) (apply str (rest type-name)))]
        (.setObject stmt i (.createArrayOf conn elem-type (to-array v)))
        (.setObject stmt i v)))))

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
        :else value)))

  java.sql.Array
  (result-set-read-column [val _ _]
    (into [] (.getArray val))))

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

(defn wrap-query
  "Given a var which derefs to a hugsql query, returns a function wrapping it with
  `to-db` and `from-db`. Infers whether the query returns a collection or
  singleton using the query-var's metadata"
  ([query-var q-ns]
   (wrap-query query-var q-ns nil))
  ([query-var q-ns {:keys [to-db from-db]
                    :or {to-db util/de-ns-map-keys
                         from-db #(util/deep-ns-map-keys % (str q-ns))}}]
   (let [{:keys [result]} (meta query-var)]
     (case result
       (:1 :one) (fn [conn m]
                   (->> m to-db (query-var conn) from-db))
       (:* :many) (fn [conn m]
                    (->> m to-db (query-var conn) (mapv from-db)))
       (throw (ex-info "Missing query metadata. Be sure to pass a var, and check for typos." {}))))))
