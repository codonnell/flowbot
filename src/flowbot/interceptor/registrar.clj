(ns flowbot.interceptor.registrar
  (:require [flowbot.registrar :as reg]
            [io.pedestal.interceptor :as int]
            [io.pedestal.interceptor.chain :as int.chain]))

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

(defn or-auth
  "Given two auth interceptors (:enter only which stop execution given an auth
  failure, or pass along the ctx map otherwise), returns an interceptor that
  continues execution if either the first or second interceptor continue
  execution. Tries the first interceptor first, and then the second. If both
  fail, returns the result of the second interceptor's :enter function."
  [{enter1 :enter} {enter2 :enter}]
  {:enter (fn [ctx]
            (let [ctx1 (enter1 ctx)]
              (if (::int.chain/queue ctx1)
                ctx1
                (enter2 ctx))))})
