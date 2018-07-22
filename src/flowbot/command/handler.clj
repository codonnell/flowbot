(ns flowbot.command.handler
  (:require [manifold.stream :as stream]
            [manifold.bus :as bus]
            [integrant.core :as ig]
            [clojure.tools.logging :as log]
            [flowbot.registrar :as reg]
            [flowbot.event.handler :as event.handler]
            [flowbot.discord.action :as discord.action]))

(def registry
  {:command
   {:hello {:name :hello
            :interceptors [::discord.action/reply]
            :handler-fn (fn [message]
                          {::discord.action/reply {:content "Hello!"}})}}})

(defn- execute-command [{name :command :as message}]
  (let [command (reg/get :command name)
        handler (event.handler/handler command)]
    (event.handler/execute handler message)))

(defmethod ig/init-key :command/handler [_ {:keys [event-bus]}]
  (let [command-stream (bus/subscribe event-bus :command)]
    (reg/add-registry! registry)
    (stream/consume execute-command command-stream)
    {:command-stream command-stream :registry registry}))

(defmethod ig/halt-key! :command/handler [_ {:keys [command-stream registry]}]
  (reg/remove-registry! registry)
  (stream/close! command-stream))
