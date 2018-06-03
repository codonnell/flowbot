(ns flowbot.migrate.up
  (:require [flowbot.config :as config]
            [ragtime.repl :as rt.repl]))

(defn -main [& args]
  (rt.repl/migrate (config/ragtime-config)))
