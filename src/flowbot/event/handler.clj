(ns flowbot.event.handler
  (:require [flowbot.interceptor.registrar :as int.reg]
            [flowbot.effect.registrar :as effect.reg]
            [io.pedestal.interceptor :as int]
            [io.pedestal.interceptor.chain :as chain]))

(defrecord Handler [interceptors handler-fn])

(defn handler [interceptor-registrar {:keys [interceptors handler-fn]}]
  (->Handler (mapv (partial int.reg/interceptor interceptor-registrar) interceptors)
             (int.reg/interceptor interceptor-registrar handler-fn)))

(defn valid-handler? [{:keys [interceptors handler-fn] :as handler}]
  (and (= Handler (type handler))
       (every? int/valid-interceptor? interceptors)
       (int/valid-interceptor? handler-fn)))

(defn event->effects
  "Applies a handler to an event, returning a map of effects."
  [{:keys [interceptors handler-fn] :as handler} event]
  {:pre [(valid-handler? handler)]}
  (:effects (chain/execute {:event event} (conj interceptors handler-fn))))

(defn execute
  "Applies a handler to an event and executes the effects returned."
  [effect-registrar handler event]
  (let [fx (event->effects handler event)]
    (doseq [[name params] fx]
      ((effect.reg/get effect-registrar name) params))))
