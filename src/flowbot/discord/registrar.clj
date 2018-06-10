(ns flowbot.discord.registrar
  "Integrant component that registers utility discord effects, interceptors, and
  commands. Requires running bot and registrar components."
  (:require [integrant.core :as ig]
            [flowbot.discord.action :as discord.action]
            [flowbot.registrar :as reg]))

(defn http-registry [bot]
  (discord.action/http-actions bot))

(defmethod ig/init-key :discord/registrar [_ {:keys [bot registrar]}]
  (let [registry {:effect (http-registry bot)
                  :interceptor {::discord.action/reply discord.action/reply-interceptor}}]
    (reg/add-registry! registrar registry)
    {:registry registry :registrar registrar}))

(defmethod ig/halt-key! :discord/registrar [_ {:keys [registry registrar]}]
  (reg/remove-registry! registrar registry))
