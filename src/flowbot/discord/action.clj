(ns flowbot.discord.action
  (:require [manifold.deferred :as d]
            [harmony.rest :as rest]
            [io.pedestal.interceptor.chain :as int.chain]
            [flowbot.interceptor.registrar :as int.reg]
            [flowbot.config :as config]
            [flowbot.util :as util]))

(def http-actions*
  {::send-message rest/create-message!})

(defn http-actions
  "Given a discord bot record, returns a map of http actions. In clj.discord, each
  http action takes auth as its first param. We inject that so callers don't
  need to include it."
  [{:keys [rest-client]}]
  (into {}
        (map (fn [[k f]] [k {:name k
                             :f #(apply f rest-client %)}]))
        http-actions*))

;; Consider using this if we want to go the async route
(defmacro defaction [http-action]
  `(def ~(-> http-action quote name symbol)
     ~(comp d/deferred http-action)))

(def reply-interceptor
  {:name  ::reply
   :leave (fn [{:keys [event effects] :as context}]
            (let [{:keys [content tts embed] :as reply} (::reply effects)]
              (cond-> context
                reply
                (update :effects
                        #(-> %
                             (assoc ::send-message
                                    (cond-> [(:channel-id event) content]
                                      (some? embed) (into [:embed embed])
                                      (some? tts)   (into [:tts tts])))
                             (dissoc ::reply))))))})

(defn- add-reply-text [ctx text]
  (update ctx :effects assoc ::reply {:content text}))

(defn- terminate-with-reply
  [ctx text]
  (int.chain/terminate (add-reply-text ctx text)))

(defn author-id [message]
  (util/parse-long (get-in message [:author :id])))

(def owner-role
  {:name ::owner-role
   :enter (fn [{:keys [event] :as ctx}]
            ;; TODO: Fix up config and do this better
            (if (= (util/parse-long (System/getenv "FLOWBOT_OWNER_ID")) (author-id event))
              ctx
              (terminate-with-reply ctx "Only the bot owner can do that.")))})
