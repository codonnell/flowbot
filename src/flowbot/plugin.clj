(ns flowbot.plugin
  (:require [flowbot.registrar :as reg]
            [flowbot.discord.bot :as discord.bot]
            [flowbot.discord.action :as discord.action]
            [flowbot.interceptor.registrar :as int.reg]
            [flowbot.util :as util]
            [net.cgrand.xforms :as x]
            [integrant.core :as ig]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))


;; Consider putting this in the database if lack of persistence is problematic
(def plugins (atom #{}))

(defmulti enable identity)

(defmethod enable :default [plugin-name]
  {::discord.action/reply (format "No plugin named %s found." plugin-name)})

(defmulti disable identity)

(defmethod disable :default [plugin-name]
  {::discord.action/reply (format "No plugin named %s found." plugin-name)})

(def enable-command
  {:name :enable
   :interceptors [discord.action/reply-interceptor
                  discord.action/owner-role]
   :handler-fn (fn [{:keys [content]}]
                 (when-let [plugin-name (second (str/split content #"\s+"))]
                   (swap! plugins conj plugin-name)
                   (enable plugin-name)))})

(def disable-command
  {:name :disable
   :interceptors [discord.action/reply-interceptor
                  discord.action/owner-role]
   :handler-fn (fn [{:keys [content]}]
                 (when-let [plugin-name (second (str/split content #"\s+"))]
                   (swap! plugins disj plugin-name)
                   (let [ret (disable plugin-name)]
                     (log/info "ret" ret)
                     ret)))})

(def registry {:command {:enable enable-command
                         :disable disable-command}})

(defmethod ig/init-key :plugin/manager [_ _]
  (reg/add-registry! registry)
  registry)

(defmethod ig/halt-key! :plugin/manager [_ _]
  (reg/remove-registry! registry))
