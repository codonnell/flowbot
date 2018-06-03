(ns flowbot.mafia.data.event
  (:require [hugsql.core :as hugsql]))

(hugsql/def-db-fns "flowbot/mafia/data/event.sql" {:quoting :ansi})
