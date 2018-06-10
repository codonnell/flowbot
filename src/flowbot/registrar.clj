(ns flowbot.registrar
  (:require [clojure.tools.logging :as log])
  (:refer-clojure :exclude [get]))

(def registry (atom {}))

(defmulti valid-entity?
  "Validation function that can be run by implementers of IRegistrar. Always
  returns true by default."
  (fn [ent-type _] ent-type))

(defmethod valid-entity? :default [_ _] true)

(defmulti coerce-entity
  "Hook that coerces an entity before it is entered into a registry."
  (fn [ent-type _] ent-type))

(defmethod coerce-entity :default [_ ent] ent)

(defn- register [registry ent-type name ent]
  (log/info "Registering entity" {:ent-type ent-type :name name :ent ent})
  (cond (contains? (registry ent-type) name)
        (throw (ex-info "An entity with that type and name already exists. Unregister it and try again."
                        {:registry registry
                         :ent-type ent-type
                         :name name
                         :ent ent}))

        (not (valid-entity? ent-type ent))
        (throw (ex-info "That entity is invalid."
                        {:ent-type ent-type
                         :name name
                         :ent ent}))

        :else
        (assoc-in registry [ent-type name] (coerce-entity ent-type ent))))

(defn register! [ent-type name ent]
  (swap! registry register ent-type name ent))

(defn- unregister [registry ent-type name]
  (if-not (contains? (registry ent-type) name)
    (throw (ex-info "There is no entity registered with that type and name."
                    {:ent-type ent-type
                     :name name}))
    (update registry ent-type dissoc name)))

(defn unregister! [ent-type name]
  (swap! registry unregister ent-type name))

(defn get [ent-type name]
  (if-some [ent (get-in @registry [ent-type name])]
    ent
    (throw (ex-info "There is no entity registered with that type and name."
                    {:ent-type ent-type
                     :name name}))))

(defn add-registry!
  "Given a map `registry` of the shape
  ```
  {:ent-type-a {:enta1 enta1 :enta2 enta2}
   :ent-type-b {:entb1 entb1 :entb2 entb2}}
  ```
  registers each contained entity."
  [registry]
  (doseq [[ent-type ent-registry] registry
          [name ent] ent-registry]
    (register! ent-type name ent)))

(defn remove-registry!
  "Given a map `registry` of the shape
  ```
  {:ent-type-a {:enta1 enta1 :enta2 enta2}
   :ent-type-b {:entb1 entb1 :entb2 entb2}}
  ```
  unregisters each contained entity."
  [registry]
  (doseq [[ent-type ent-registry] registry
          [name ent] ent-registry]
    (unregister! ent-type name)))
