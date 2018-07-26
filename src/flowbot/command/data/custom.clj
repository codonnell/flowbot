(ns flowbot.command.data.custom
  (:require [hugsql.core :as hugsql]
            [clojure.spec.alpha :as s]
            [flowbot.data.postgres :as pg])
  (:import [flowbot.data.postgres Conn]))

(defprotocol CustomCommand
  (insert-custom-command! [this command])
  (upsert-custom-command! [this command])
  (get-custom-command-by-id [this id])
  (get-custom-command-by-name [this name])
  (get-custom-commands [this])
  (delete-custom-command-by-name! [this name]))

(hugsql/def-db-fns "flowbot/command/data/custom.sql" {:quoting :ansi})

(let [q-ns "flowbot.command.data.custom"]
  (extend Conn
    CustomCommand
    {:insert-custom-command! (pg/wrap-query #'insert-custom-command!* q-ns)
     :upsert-custom-command! (pg/wrap-query #'upsert-custom-command!* q-ns)
     :get-custom-command-by-id (pg/wrap-query #'get-custom-command-by-id* q-ns
                                              {:to-db (fn [id] {:id id})})
     :get-custom-command-by-name (pg/wrap-query #'get-custom-command-by-name* q-ns
                                                {:to-db (fn [name] {:name name})})
     :get-custom-commands (pg/wrap-query #'get-custom-commands* q-ns)
     :delete-custom-command-by-name (pg/wrap-query #'delete-custom-command-by-name!* q-ns
                                                   {:to-db (fn [name] {:name name})})}))
