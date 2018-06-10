(ns flowbot.registrar.registrar
  (:require [integrant.core :as ig]
            [clojure.tools.logging :as log]
            [flowbot.registrar :as reg])
  (:refer-clojure :exclude [get]))

(defn- register [registry ent-type name ent]
  (log/info "Registering entity" {:ent-type ent-type :name name :ent ent})
  (cond (contains? (registry ent-type) name)
        (throw (ex-info "An entity with that type and name already exists. Unregister it and try again."
                        {:registry registry
                         :ent-type ent-type
                         :name name
                         :ent ent}))

        (not (reg/valid-entity? ent-type ent))
        (throw (ex-info "That entity is invalid."
                        {:ent-type ent-type
                         :name name
                         :ent ent}))

        :else
        (assoc-in registry [ent-type name] (reg/coerce-entity ent-type ent))))

(defn- unregister [registry ent-type name]
  (if-not (contains? (registry ent-type) name)
    (throw (ex-info "There is no entity registered with that type and name."
                    {:ent-type ent-type
                     :name name}))
    (update registry ent-type dissoc name)))

(defrecord Registrar [registry]
  reg/IRegistrar
  (register! [_ ent-type name ent]
    (swap! registry register ent-type name ent))

  (unregister! [_ ent-type name]
    (swap! registry unregister ent-type name))

  (get [_ ent-type name]
    (if-some [ent (get-in @registry [ent-type name])]
      ent
      (throw (ex-info "There is no entity registered with that type and name."
                      {:ent-type ent-type
                       :name name})))))

(defmethod ig/init-key :registrar [_ _]
  (->Registrar (atom {})))
