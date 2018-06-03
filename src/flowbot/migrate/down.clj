(ns flowbot.migrate.down
  (:require [flowbot.config :as config]
            [ragtime.repl :as rt.repl]))

(defn -main [& args]
  (rt.repl/rollback (config/ragtime-config)))
