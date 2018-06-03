(ns flowbot.mafia.data.game
  (:require [hugsql.core :as hugsql]))

(hugsql/def-db-fns "flowbot/mafia/data/game.sql" {:quoting :ansi})
