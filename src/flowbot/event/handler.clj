(ns flowbot.event.handler
  (:require [flowbot.interceptor.registrar :as reg]
            [io.pedestal.interceptor :as int]
            [io.pedestal.interceptor.chain :as chain]))

(defrecord Handler [interceptors handler-fn])

(defn handler [{:keys [interceptors handler-fn]}]
  (->Handler (mapv int/interceptor interceptors) (int/interceptor handler-fn)))

(defn valid-handler? [{:keys [interceptors handler-fn] :as handler}]
  (and (= Handler (type handler))
       (every? int/valid-interceptor? interceptors)
       (int/valid-interceptor? handler-fn)))

(defn event->effects
  "Applies a handler to an event, returning a map of effects."
  [{:keys [interceptors handler-fn] :as handler} event]
  {:pre [(valid-handler? handler)]}
  (:effects (chain/execute {:event event} (conj interceptors handler-fn))))
