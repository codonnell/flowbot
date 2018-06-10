(ns flowbot.interceptor.registrar
  (:require [flowbot.registrar :as reg]
            [io.pedestal.interceptor :as int]))

(defmethod reg/valid-entity? :interceptor [_ ent]
  (and (int/valid-interceptor? (int/interceptor ent))
       (keyword? (:name ent))))

(defmethod reg/coerce-entity :interceptor [_ ent]
  (int/interceptor ent))

(extend-protocol int/IntoInterceptor
  ;; Our input is under :event and output is under :effects instead of :request
  ;; and :response, so we override the default
  clojure.lang.Fn
  (-interceptor [t]
    (int/interceptor {:enter (fn [context]
                               (assoc context :effects (t (:event context))))})))

(defn interceptor [ent]
  (if (keyword? ent)
    (reg/get :interceptor ent)
    (int/interceptor ent)))
