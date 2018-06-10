(ns flowbot.discord.action
  (:require [manifold.deferred :as d]
            [discord.http :as http]
            [flowbot.interceptor.registrar :as int.reg]))

;; TODO: wrap all http actions from discord.clj with d/deferred
;; if http actions don't automatically retry, add optional retry
;; Register all of these actions as effects

(def http-actions*
  {::send-message           http/send-message
   ::delete-message         http/delete-message
   ::delete-messages        http/delete-messages
   ::edit-message           http/edit-message
   ::get-message            http/get-message
   ::logs-from              http/logs-from
   ::pin-message            http/pin-message
   ::unpin-message          http/unpin-message
   ::pins-from              http/pins-from
   ::add-reaction           http/add-reaction
   ::remove-reaction        http/remove-reaction
   ::reaction-users         http/reaction-users
   ::clear-reactions        http/clear-reactions
   ::get-guild              http/get-guild
   ::get-servers            http/get-servers
   ::find-server            http/find-server
   ::create-server          http/create-server
   ::modify-server          http/modify-server
   ::delete-server          http/delete-server
   ::get-guild-member       http/get-guild-member
   ::list-members           http/list-members
   ::find-member            http/find-member
   ::get-member             http/get-member
   ::edit-member            http/edit-member
   ::kick                   http/kick
   ::ban                    http/ban
   ::unban                  http/unban
   ::update-nickname        http/update-nickname
   ::prune-members          http/prune-members
   ::estimate-prune-members http/estimate-prune-members
   ::get-current-user       http/get-current-user
   ::edit-profile           http/edit-profile
   ::get-channel            http/get-channel
   ::get-guild-channels     http/get-guild-channels
   ::get-voice-channels     http/get-voice-channels
   ::get-text-channels      http/get-text-channels
   ::find-channel           http/find-channel
   ::create-channel         http/create-channel
   ::create-dm-channel      http/create-dm-channel
   ::edit-channel           http/edit-channel
   ::move-channel           http/move-channel
   ::delete-channel         http/delete-channel
   ::get-roles              http/get-roles
   ::send-typing            http/send-typing
   ::get-gateway            http/get-gateway
   ::get-bot-gateway        http/get-bot-gateway})

(defn http-actions
  "Given a discord bot record, returns a map of http actions. In clj.discord, each
  http action takes auth as its first param. We inject that so callers don't
  need to include it."
  [bot]
  (into {}
        (map (fn [[k f]] [k {:name k
                             :f #(apply f (get-in bot [:client :auth]) %)}]))
        http-actions*))

;; Consider using this if we want to go the async route
(defmacro defaction [http-action]
  `(def ~(-> http-action quote name symbol)
     ~(comp d/deferred http-action)))

(def reply-interceptor
  {:name  ::reply
   :leave (fn [{:keys [event effects] :as context}]
            (let [{:keys [content tts embed]} (::reply effects)]
              (update context :effects
                      #(-> %
                           (assoc ::send-message
                                  (cond-> [(:channel event) content]
                                    (some? embed) (into [:embed embed])
                                    (some? tts)   (into [:tts tts])))
                           (dissoc ::reply)))))})
