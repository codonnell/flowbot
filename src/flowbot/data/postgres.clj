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
   :enter (fn [ctx]
            (assoc ctx ::conn conn))})

(defmethod ig/init-key :postgres/connection [_ {:keys [dbtype dbname host port user password]}]
  (let [url (str "jdbc:" dbtype "://" host ":" port "/" dbname)
        pool {:datasource (doto (ComboPooledDataSource.)
                            (.setDriverClass "org.postgresql.Driver")
                            (.setJdbcUrl url)
                            (.setUser user)
                            (.setPassword password))}]
    (reg/register! :interceptor inject-int-name (inject-conn pool))
    pool))

(defmethod ig/halt-key! :rainbot/db [_ pool]
  (reg/unregister! :interceptor inject-int-name)
  (.close (:datasource pool)))

(defn value-to-json-pgobject [value]
  (doto (PGobject.)
    (.setType "json")
    (.setValue (json/encode value))))

#_(extend-protocol clojure.java.jdbc/ISQLParameter
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

  ;; java.sql.Array
  ;; (result-set-read-column [val _ _]
  ;;   (into [] (.getArray val)))
  )

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

(defn wrapped-query [from-db to-db query]
  (fn [db m]
    (->> m to-db (query db) from-db)))

(defn def-wrapped-query* [from-db to-db query]
  (let [{:keys [result] :as query-meta} (meta (resolve query))]
    (case result
      (:1 :one) `(def ~query ~(with-meta
                                (wrapped-query @(resolve from-db) @(resolve to-db) @(resolve query))
                                query-meta))
      (:* :many) `(def ~query ~(with-meta
                                 (wrapped-query #(mapv @(resolve from-db) %) @(resolve to-db) @(resolve query))
                                 query-meta)))))

(defmacro def-wrapped-queries
  "Redefines each query var in queries to be
  * (comp from-db query to-db) if the query returns a single result
  * (comp #(mapv from-db %) query to-db) if the query returns multiple results
  Preserves the query var's metadata and uses it to infer the result type."
  [{:keys [from-db to-db queries]
    :or {from-db identity
         to-db identity}}]
  `(do
     ~@(map #(def-wrapped-query* from-db to-db %) queries)))
