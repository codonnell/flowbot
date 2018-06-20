(ns flowbot.mafia.game-test
  (:require [flowbot.mafia.game :as game]
            [flowbot.mafia.data.game :as d.g]
            [flowbot.mafia.data.event :as d.e]
            [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

(defn- generate [spec]
  (gen/generate (s/gen spec)))

(defn- initialized-game []
  (-> ::d.g/db-game generate game/init-game))

(defn- gen-player-ids [n]
  (gen/generate (gen/vector-distinct (s/gen ::d.g/player-id) {:num-elements n})))

(defn- reg-players [game player-ids]
  (reduce (fn [game player-id] (game/join-game game player-id))
          game
          player-ids))

(defn- started-with-players [player-ids]
  (-> (initialized-game)
      (reg-players player-ids)
      game/end-registration
      game/start-day))

(deftest init-game
  (let [game (generate ::d.g/db-game)
        game' (game/init-game game)]
    (is (= ::d.g/registration (::d.g/stage game')))
    (is (= [] (::d.g/past-days game')))
    (is (= [] (::d.g/events game')))
    (let [initial-keys [::d.g/channel-id ::d.g/moderator-id ::d.g/id ::d.g/created-at]]
      (is (= (select-keys game initial-keys) (select-keys game' initial-keys))))
    (is (= #{} (::d.g/registered-players game')))))

(deftest unstarted
  (let [game (initialized-game)]
    (is (game/unstarted? game))
    (is (game/unstarted? (assoc game ::d.g/stage ::d.g/role-distribution)))
    (is (not (game/unstarted? (assoc game ::d.g/stage ::d.g/day))))
    (is (not (game/unstarted? (assoc game ::d.g/stage ::d.g/night))))))

(deftest join-game
  (let [game (initialized-game)
        player-id (generate ::d.g/player-id)
        join-game-success? (fn [game player-id]
                             ((::d.g/registered-players (game/join-game game player-id)) player-id))]
    (is (join-game-success? game player-id))
    (is (not (join-game-success? (game/end-registration game) player-id)))))

(deftest leave-game
  (let [player-id (generate ::d.g/player-id)
        game (game/join-game (initialized-game) player-id)
        leave-game-success? (fn [game player-id]
                              (not ((::d.g/registered-players (game/leave-game game player-id)) player-id)))]
    (is (leave-game-success? game player-id))
    (is (not (leave-game-success? (game/end-registration game) player-id)))))

(deftest start-day
  (let [game (initialized-game)]
    (testing "Start day does nothing unless it's night or role distribution stage"
      (is (= game (game/start-day game)))
      (let [day-game (-> game game/end-registration game/start-day)]
        (is (= day-game (game/start-day day-game)))))
    (testing "Start day adds current-day and changes stage to day when it's night or role distribution stage"
      (let [rd-stage (-> game game/end-registration)
            night-stage (-> game game/end-registration game/start-day game/end-day)
            changes-to-day? (fn [game]
                              (is (not (::d.g/current-day game)))
                              (is (not (= ::d.g/day (::d.g/stage game))))
                              (let [day-game (game/start-day game)]
                                (is (::d.g/current-day day-game))
                                (is (= ::d.g/day (::d.g/stage day-game)))))]
        (changes-to-day? rd-stage)
        (changes-to-day? night-stage)))))

(deftest end-day
  (let [game (initialized-game)]
    (testing "End day does nothing unless it's day stage"
      (is (= game (game/end-day game)))
      (let [rd-stage (-> game game/end-registration)
            night-stage (-> game game/end-registration game/start-day game/end-day)]
        (is (= rd-stage (game/end-day rd-stage)))
        (is (= night-stage (game/end-day night-stage)))))
    (testing "End day adds current-day to the end of past-days, removes current-day, and changes stage to night."
      (let [day-stage (-> game game/end-registration game/start-day)]
        (is (::d.g/current-day day-stage))
        (is (= ::d.g/day (::d.g/stage day-stage)))
        (let [night-stage (game/end-day day-stage)]
          (is (not (::d.g/current-day night-stage)))
          (is (= ::d.g/night (::d.g/stage night-stage)))
          (is (= (::d.g/current-day day-stage) (peek (::d.g/past-days night-stage)))))))))

(deftest kill
  (let [player-id (generate ::d.g/player-id)
        game (-> (initialized-game)
                 (game/join-game player-id)
                 game/end-registration)
        killed-game (game/kill game player-id)]
    (is (= (::d.g/registered-players game) (::d.g/registered-players killed-game))
        "Killing a player does not affect registered players list")
    (is (= #{} (::d.g/players killed-game))
        "Killing a player removes that player from the players list")))

(deftest revive
  (let [player-id (generate ::d.g/player-id)
        game (-> (initialized-game)
                 (game/join-game player-id)
                 game/end-registration
                 (game/kill player-id))
        revived-game (game/revive game player-id)
        failed-revive-game (game/revive game (generate ::d.g/player-id))]
    (is (= #{player-id} (::d.g/players revived-game))
        "Reviving a player returns that player to the players list")
    (is (= (::d.g/registered-players game) (::d.g/registered-players failed-revive-game))
        "Reviving an unregistered player does not add them to the registered players list")
    (is (= #{} (::d.g/players failed-revive-game))
        "Reviving an unregistered player does not add them to the players list")))

(deftest vote
  (let [[player1 player2 player3 :as player-ids] (gen-player-ids 3)
        game (started-with-players player-ids)
        ballot-for (fn [game player-id] (get-in game [::d.g/current-day ::d.g/votes player-id]))
        has-not-voted? (fn [game player-id] (-> game
                                                (get-in [::d.g/current-day ::d.g/votes])
                                                (contains? player-id)
                                                not))]
    (is (= player2 (ballot-for (game/vote game player1 player2) player1))
        "Single votes are recorded")
    (is (= player3 (ballot-for (-> game (game/vote player1 player2) (game/vote player1 player3))
                               player1))
        "Subsequent votes by a player override their previous votes")
    (is (= player1 (ballot-for (game/vote game player1 player1) player1))
        "Players can vote for themselves")
    (is (nil? (ballot-for (game/vote game player1 nil) player1))
        "Players can vote for no one (nil)")
    (let [game' (-> game (game/vote player1 player2) (game/vote player2 player3))]
      (testing "Multiple votes are recorded"
        (is (= player2 (ballot-for game' player1)))
        (is (= player3 (ballot-for game' player2)))))
    (is (has-not-voted? (-> game (game/vote player1 player2) (game/unvote player1))
                        player1)
        "Unvote removes votes (different from voting no one)")
    (is (has-not-voted? (-> game (game/kill player1) (game/vote player1 player2))
                        player1)
        "Dead players cannot vote")
    (is (has-not-voted? (-> game (game/vote player1 player2) (game/kill player1))
                        player1)
        "Killing a player removes their vote")
    (let [unreg-player (generate ::d.g/player-id)]
      (is (has-not-voted? (game/vote game unreg-player player2) unreg-player)
          "Unregistered players cannot vote"))))

(deftest vote-totals
  (let [[player1 player2 player3 :as players] (gen-player-ids 3)
        game (started-with-players players)
        current-totals (fn [game] (game/vote-totals (get-in game [::d.g/current-day ::d.g/votes])))]
    (is (= {player1 1 player2 1}
           (-> game (game/vote player1 player2) (game/vote player3 player1) current-totals))
        "Single votes are counted")
    (is (= {player1 2}
           (-> game (game/vote player2 player1) (game/vote player3 player1) current-totals))
        "Multiple votes are counted")
    (is (= {nil 1}
           (-> game (game/vote player1 player2) (game/vote player1 nil) current-totals))
        "Votes for no one are counted")
    (is (= {} (-> game (game/vote player1 player2) (game/unvote player1) current-totals))
        "Unvotes are not counted")))

(deftest today-and-yesterday-totals
  (let [[player1 player2 player3 :as players] (gen-player-ids 3)
        game (-> (started-with-players players)
                 (game/vote player1 player2)
                 (game/vote player3 player1))
        day1-totals {player1 1 player2 1}
        night-game (game/end-day game)
        day2-game (game/start-day night-game)]
    (is (= day1-totals (game/today-totals game))
        "Today votes counts same day votes")
    (is (nil? (game/yesterday-totals game))
        "Yesterday vote totals on the first day are nil")
    (is (nil? (game/today-totals night-game))
        "Today vote totals is nil at night")
    (is (= day1-totals (game/yesterday-totals night-game))
        "Yesterday vote totals on the first night have the day 1 vote totals")
    (is (= {} (game/today-totals day2-game))
        "Today vote totals are empty (not nil) at the start of day 2")
    (is (= day1-totals (game/yesterday-totals day2-game))
        "Yesterday totals on the second day have the day 1 vote totals")))

(deftest nonvoters
  (let [[player1 player2 player3 :as players] (gen-player-ids 3)
        game (-> (started-with-players players)
                 (game/vote player1 player2)
                 (game/vote player3 player1))]
    (is (= #{player2} (game/nonvoters game))
        "A single nonvoter is counted during the day")
    (is (= #{} (-> game (game/vote player2 nil) game/nonvoters))
        "Players that vote for no one are not counted as nonvoters")
    (is (= #{player1 player2} (-> game (game/unvote player1) game/nonvoters))
        "Players that unvote are counted as nonvoters")
    (is (nil? (-> game game/end-day game/nonvoters))
        "Nonvoters is nil at night")))
