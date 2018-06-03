(ns flowbot.config
  (:require [clojure.java.io :as io]
            [com.walmartlabs.dyn-edn :as dyn-edn]
            [integrant.core :as ig]
            [ragtime.jdbc :as rt.jdbc]))

(defn system []
  (ig/read-string {:readers (dyn-edn/env-readers)}
                  (slurp (io/resource "system.edn"))))

(defn ragtime-config []
  {:datastore (rt.jdbc/sql-database (:postgres/connection (system)))
   :migrations (rt.jdbc/load-resources "migrations")})
