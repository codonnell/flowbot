(ns flowbot.event.handler
  (:require [flowbot.registrar :as reg]
            [flowbot.interceptor.registrar :as int.reg]
            [flowbot.effect.registrar :as effect.reg]
            [io.pedestal.interceptor :as int]
            [io.pedestal.interceptor.chain :as chain]
            [clojure.tools.logging :as log]))

(defrecord Handler [interceptors handler-fn])

(defn handler [{:keys [interceptors handler-fn]}]
  (->Handler (mapv int.reg/interceptor interceptors)
             (int.reg/interceptor handler-fn)))

(defn valid-handler? [{:keys [interceptors handler-fn] :as handler}]
  (and (= Handler (type handler))
       (every? int/valid-interceptor? interceptors)
       (int/valid-interceptor? handler-fn)))

(defn event->effects
  "Applies a handler to an event, returning a map of effects."
  [{:keys [interceptors handler-fn] :as handler} event]
  {:pre [(valid-handler? handler)]}
  (log/debug {:interceptors interceptors :handler-fn handler-fn})
  (:effects (chain/execute {:event event} (conj interceptors handler-fn))))

(defn execute
  "Applies a handler to an event and executes the effects returned."
  [handler event]
  {:pre [(valid-handler? handler)]}
  (let [fx (event->effects handler event)]
    (doseq [[name params] fx]
      (let [{:keys [f]} (reg/get :effect name)]
        (f params)))))
