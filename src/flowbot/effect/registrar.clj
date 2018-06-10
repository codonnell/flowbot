(ns flowbot.effect.registrar
  (:require [flowbot.registrar :as reg]))

(defmethod reg/valid-entity? :effect [_ {:keys [name f]}]
  (and (fn? f) (keyword? name)))
