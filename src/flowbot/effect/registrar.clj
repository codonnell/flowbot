(ns flowbot.effect.registrar
  (:require [flowbot.effect.registry :as registry]
            [integrant.core :as ig])
  (:refer-clojure :exclude [get]))

(defonce registry (atom {}))

(defmethod ig/init-key :effect/registrar [_ _]
  (reset! registry registry/effect-registry)
  registry)

(defmethod ig/halt-key! :effect/registrar [_ _]
  (reset! registry {}))

(defn- register
  [registry name effect]
  (cond (not (keyword? name))
        (throw (ex-info "Every effect name must be a keyword" {:name name}))

        (contains? registry name)
        (throw (ex-info "An effect with that name is already registered. You must unregister the existing interceptor before you can register this one." {:registry registry :name name}))

        :else
        (assoc registry name effect)))

(defn register!
  "Adds an effect to the registry. Throws an exception if no keyword name is given.
  Also throws if an effect with the given name is already registered."
  [registry name effect]
  (swap! registry register name effect))

(defn- unregister
  [registry name]
  {:pre [(keyword? name)]}
  (cond (not (keyword? name))
        (throw (ex-info "Every effect name must be a keyword" {:name name}))

        (contains? registry name)
        (throw (ex-info "There is no effect registered with that name." {:registry registry :name name}))

        :else
        (dissoc registry name)))

(defn unregister!
  "Removes the effect with the given name from the registry. Throws an exception
  if such an interceptor does not exist or the name passed is not a keyword."
  [registry name]
  (swap! registry unregister name))

(defn get
  "Gets the effect from the registry with the given name. Throws an exception if
  such an effect does not exist."
  [registry name]
  (let [registry_ @registry]
    (if-not (contains? registry_ name)
      (throw (ex-info "An effect with that name does not exist" {:registry registry_
                                                                 :name name}))
      (registry_ name))))
