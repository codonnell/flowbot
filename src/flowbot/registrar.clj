(ns flowbot.registrar
  (:refer-clojure :exclude [get]))

(defmulti valid-entity?
  "Validation function that can be run by implementers of IRegistrar. Always
  returns true by default."
  (fn [ent-type _] ent-type))

(defmethod valid-entity? :default [_ _] true)

(defmulti coerce-entity
  "Hook that coerces an entity before it is entered into a registry."
  (fn [ent-type _] ent-type))

(defmethod coerce-entity :default [_ ent] ent)

(defprotocol IRegistrar
  (register! [this ent-type name ent]
    "Registers an entity of type ent-type. Throws an exception if an entity with
    that type and name already exists or the entity is invalid.")
  (unregister! [this ent-type name]
    "Unregisters the entity with the given type and name. Throws an exception if
    such an entity does not exist.")
  (get [this ent-type name]
    "Returns the entity with the given type and name. Throws an exception if
    such an entity does not exist."))

(defn add-registry!
  "Given a map `registry` of the shape
  ```
  {:ent-type-a {:enta1 enta1 :enta2 enta2}
   :ent-type-b {:entb1 entb1 :entb2 entb2}}
  ```
  adds each contained entity using the `registrar` component."
  [registrar registry]
  (doseq [[ent-type ent-registry] registry
          [name ent] ent-registry]
    (register! registrar ent-type name ent)))

(defn remove-registry!
  "Given a map `registry` of the shape
  ```
  {:ent-type-a {:enta1 enta1 :enta2 enta2}
   :ent-type-b {:entb1 entb1 :entb2 entb2}}
  ```
  removes each contained entity using the `registrar` component."
  [registrar registry]
  (doseq [[ent-type ent-registry] registry
          [name ent] ent-registry]
    (unregister! registrar ent-type name)))
