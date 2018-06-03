(ns flowbot.interceptor.registrar
  (:require [flowbot.interceptor.registry :as registry]
            [integrant.core :as ig]
            [io.pedestal.interceptor :as int])
  (:refer-clojure :exclude [get]))

(defonce registry (atom {}))

(defmethod ig/init-key :interceptor/registrar [_ _]
  {:pre [(every? int/valid-interceptor? (vals registry/interceptor-registry))
         (every? keyword? (keys registry/interceptor-registry))]}
  (reset! registry registry/interceptor-registry)
  registry)

(defmethod ig/halt-key! :interceptor/registrar [_ _]
  (reset! registry {}))

(defn- register
  ([registry interceptor]
   (let [{:keys [name] :as interceptor} (int/interceptor interceptor)]
     (if-not (keyword? name)
       (throw (ex-info "An interceptor needs a keyword name to be registered" {:interceptor interceptor}))
       (register name interceptor))))
  ([registry name interceptor]
   {:pre [(keyword? name)]}
   (if (contains? registry name)
     (throw (ex-info "An interceptor with that name is already registered. You must unregister the existing interceptor before you can register this one."
                     {:registry registry :name name :interceptor interceptor}))
     (assoc registry name (int/interceptor interceptor)))))

(defn register!
  "Adds an interceptor to the registry. Throws an exception if no name is given
  and the interceptor doesn't have a name. Attempts to coerce interceptor to an
  Interceptor record."
  ([registry interceptor]
   (swap! registry register interceptor))
  ([registry name interceptor]
   (swap! registry register name interceptor)))

(defn- unregister
  [registry name]
  {:pre [(keyword? name)]}
  (if-not (contains? registry name)
    (throw (ex-info "There is no interceptor registered with that name." {:registry registry :name name}))
    (dissoc registry name)))

(defn unregister!
  "Removes the interceptor with the given name from the registry. Throws an
  exception if such an interceptor does not exist."
  [registry name]
  (swap! registry unregister name))

(defn get
  "Gets the interceptor from the registry with the given name. Throws an exception
  if such an interceptor does not exist."
  [registry name]
  (let [registry_ @registry]
    (if-not (contains? registry_ name)
      (throw (ex-info "An interceptor with that name does not exist" {:registry registry_
                                                                      :name name}))
      (registry_ name))))

(extend-protocol int/IntoInterceptor
  ;; Our input is under :event and output is under :effects instead of :request
  ;; and :response, so we override the default
  clojure.lang.Fn
  (-interceptor [t]
    (int/interceptor {:enter (fn [context]
                               (assoc context :effects (t (:event context))))})))

(defn interceptor
  "If int is a keyword, gets its value from the registrar. Otherwise attempts to
  coerce int to an interceptor. Throws if int is not found by the registrar or
  the value cannot be coerced to an interceptor."
  [registrar int]
  (if (keyword? int)
    (get registrar int)
    (int/interceptor int)))
