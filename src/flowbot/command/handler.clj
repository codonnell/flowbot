(ns flowbot.command.handler
  (:require [manifold.stream :as stream]
            [manifold.bus :as bus]
            [integrant.core :as ig]
            [flowbot.registrar :as reg]
            [flowbot.event.handler :as event.handler]
            [flowbot.discord.action :as discord.action]))

(def registry
  {:command
   {:hello {:name :hello
            :interceptors [::discord.action/reply]
            :handler-fn (fn [message]
                          {::discord.action/reply {:content "Hello!"}})}}})

(defn- dispatcher [registrar]
  (fn [{name :command :as message}]
    (let [command (reg/get registrar :command name)
          handler (event.handler/handler registrar command)]
      (event.handler/execute registrar handler message))))

(defmethod ig/init-key :command/handler [_ {:keys [registrar event-bus]}]
  (let [event-stream (bus/subscribe event-bus :command)]
    (reg/add-registry! registrar registry)
    (stream/consume (dispatcher registrar) event-stream)
    {:registrar registrar :event-stream event-stream :registry registry}))

(defmethod ig/halt-key! :command/handler [_ {:keys [registrar event-stream registry]}]
  (reg/remove-registry! registrar registry)
  (stream/close! event-stream))
