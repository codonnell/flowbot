(ns flowbot.event.bus
  (:require [integrant.core :as ig]
            [manifold.bus :as bus]))

(defmethod ig/init-key :event-bus [_ _]
  (bus/event-bus))
