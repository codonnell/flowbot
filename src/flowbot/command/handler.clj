(ns flowbot.command.handler
  (:require [manifold.stream :as stream]
            [manifold.bus :as bus]
            [integrant.core :as ig]
            [io.pedestal.interceptor.chain :as int.chain]
            [net.cgrand.xforms :as x]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [flowbot.data.postgres :as pg]
            [flowbot.util :as util]
            [flowbot.discord.message-dispatcher :as msg.dispatch]
            [flowbot.command.data.custom :as d.custom]
            [flowbot.registrar :as reg]
            [flowbot.interceptor.registrar :as int.reg]
            [flowbot.event.handler :as event.handler]
            [flowbot.discord.action :as discord.action]))

(defn channel-id [message]
  (util/parse-long (get-in message [:channel :id])))

(defn- add-reply-text [ctx text]
  (update ctx :effects assoc ::discord.action/reply {:content text}))

(defn- terminate-with-reply
  [ctx text]
  (int.chain/terminate (add-reply-text ctx text)))

(defn- parse-add-command [message]
  (let [[_ name format-string] (str/split message #"\s" 3)]
    #::d.custom{:name name :format-string format-string}))

(def add-command-command
  {:name :add-command
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  discord.action/owner-role]
   :handler-fn
   (int.reg/interceptor
    {:name ::add-command-handler
     :leave (fn [{::pg/keys [conn] :keys [event] :as ctx}]
              (update ctx :effects assoc
                      ::add-command {:command (parse-add-command (:content event))
                                     :conn conn
                                     :channel-id (channel-id event)}))})})

(def add-command-effect
  {:name ::add-command
   :f (fn [{:keys [command conn channel-id]}]
        (try
          (let [send-message (:f (reg/get :effect ::discord.action/send-message))]
            (d.custom/upsert-custom-command! conn command)
            (send-message [channel-id (str "Added command `" (::d.custom/name command) "`")]))
          (catch Throwable e
            (log/error e))))})

(def remove-command-command
  {:name :remove-command
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  discord.action/owner-role]
   :handler-fn
   (int.reg/interceptor
    {:name ::remove-command-handler
     :leave (fn [{::pg/keys [conn] :keys [event] :as ctx}]
              (update ctx :effects assoc
                      ::remove-command {:command (parse-add-command (:content event))
                                        :conn conn
                                        :channel-id (channel-id event)}))})})

(def remove-command-effect
  {:name ::remove-command
   :f (fn [{:keys [conn channel-id] {::d.custom/keys [name]} :command}]
        (try
          (let [send-message (:f (reg/get :effect ::discord.action/send-message))]
            (if (d.custom/delete-custom-command-by-name! conn name)
              (send-message [channel-id (str "Removed command `" name "`")])
              (send-message [channel-id (str "No command with the name `" name "` exists")])))
          (catch Throwable e
            (log/error e))))})

(def custom-commands-command
  {:name :custom-commands
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  discord.action/owner-role]
   :handler-fn
   (int.reg/interceptor
    {:name ::custom-commands-handler
     :leave (fn [{::pg/keys [conn] :keys [event] :as ctx}]
              (update ctx :effects assoc
                      ::discord.action/reply {:content (x/str (comp (map ::d.custom/name)
                                                                    (interpose ", "))
                                                              (d.custom/get-custom-commands conn))}))})})


(defn inject-custom-command [prefix]
  {:name ::d.custom/inject-custom-command
   :enter (fn [{::pg/keys [conn] :keys [event] :as ctx}]
            (if-let [command (d.custom/get-custom-command-by-name
                              conn
                              (msg.dispatch/command-name (:content event) prefix))]
              (assoc ctx ::d.custom/command command)
              ctx))})

(def registry
  {:command
   {:add-command add-command-command
    :remove-command remove-command-command
    :custom-commands custom-commands-command}
   :effect
   {::add-command add-command-effect
    ::remove-command remove-command-effect}})

(defn- execute-command [{name :command :as message}]
  (when-let [command (reg/get :command name)]
    (event.handler/execute (event.handler/handler command) message)))

(defmethod ig/init-key :command/handler [_ {:keys [event-bus bot]}]
  (let [command-stream (bus/subscribe event-bus :command)]
    (reg/add-registry! (assoc-in registry
                                 [:interceptor ::d.custom/inject-custom-command]
                                 (inject-custom-command (:prefix bot))))
    (stream/consume execute-command command-stream)
    {:command-stream command-stream :registry registry}))

(defmethod ig/halt-key! :command/handler [_ {:keys [command-stream registry]}]
  (reg/remove-registry! registry)
  (stream/close! command-stream))
