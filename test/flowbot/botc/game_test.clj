(ns flowbot.botc.game-test
  (:require [flowbot.botc.game :as game]
            [flowbot.botc.data.game :as d.g]
            [flowbot.botc.data.event :as d.e]
            [flowbot.botc.data.player :as d.p]
            [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

(defn generate [spec]
  (gen/generate (s/gen spec)))

(defn initialized-game []
  (-> ::d.g/db-game generate game/init-game (dissoc ::d.g/finished-at)))

(defn gen-players [n]
  (gen/generate (gen/vector-distinct (s/gen ::d.p/player) {:num-elements n})))

(defn reg-players [game players]
  (reduce (fn [game player] (game/join-game game player))
          game
          players))

(defn started-with-players [players]
  (-> (initialized-game)
      (reg-players players)
      game/end-registration
      game/start-day))

(deftest init-game
  (let [game (generate ::d.g/db-game)
        game' (game/init-game game)]
    (is (= ::d.g/registration (::d.g/stage game')))
    (is (= [] (::d.g/past-days game')))
    (is (nil? (::d.e/events game')))
    (let [initial-keys [::d.g/channel-id ::d.g/moderator-id ::d.g/id ::d.g/created-at]]
      (is (= (select-keys game initial-keys) (select-keys game' initial-keys))))
    (is (= {} (::d.g/registered-players game')))))

(deftest unstarted
  (let [game (initialized-game)]
    (is (game/unstarted? game))
    (is (game/unstarted? (assoc game ::d.g/stage ::d.g/role-distribution)))
    (is (not (game/unstarted? (assoc game ::d.g/stage ::d.g/day))))
    (is (not (game/unstarted? (assoc game ::d.g/stage ::d.g/night))))))

(deftest join-game
  (let [game (initialized-game)
        player (generate ::d.p/player)
        join-game-success? (fn [game {::d.p/keys [id] :as player}]
                             ((::d.g/registered-players (game/join-game game player)) id))]
    (is (join-game-success? game player))
    (is (not (join-game-success? (game/end-registration game) player)))))

(deftest leave-game
  (let [{::d.p/keys [id] :as player} (generate ::d.p/player)
        game (game/join-game (initialized-game) player)
        leave-game-success? (fn [game player-id]
                              (not ((::d.g/registered-players (game/leave-game game player-id)) player-id)))]
    (is (leave-game-success? game id))
    (is (not (leave-game-success? (game/end-registration game) id)))))

(deftest end-registration
  (let [game (initialized-game)
        players (mapv (fn [player username]
                        (assoc player ::d.p/username username))
                      (gen-players 3) ["Adam" "Mary" "Bob"])
        username->id (into {} (map (juxt ::d.p/username ::d.p/id)) players)
        game' (-> game (reg-players players) game/end-registration)
        game-players (::d.g/registered-players game')
        index-of (fn [username] (get-in game-players [(username->id username) ::d.p/index]))]
    (is (= 0 (index-of "Adam")))
    (is (= 1 (index-of "Bob")))
    (is (= 2 (index-of "Mary")))))

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
                              (is (not= ::d.g/day (::d.g/stage game)))
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
  (let [{::d.p/keys [id] :as player} (generate ::d.p/player)
        game (-> (initialized-game)
                 (game/join-game player)
                 game/end-registration)
        killed-game (game/kill game id)]
    (is (= (::d.g/registered-players game) (::d.g/registered-players killed-game))
        "Killing a player does not affect registered players list")
    (is (= {} (::d.g/players killed-game))
        "Killing a player removes that player from the players list")))

(deftest revive
  (let [{::d.p/keys [id] :as player} (generate ::d.p/player)
        game (-> (initialized-game)
                 (game/join-game player)
                 game/end-registration
                 (game/kill id))
        revived-game (game/revive game id)
        failed-revive-game (game/revive game (generate ::d.p/id))]
    (is (= #{id} (set (keys (::d.g/players revived-game))))
        "Reviving a player returns that player to the players list")
    (is (= (::d.g/registered-players game) (::d.g/registered-players failed-revive-game))
        "Reviving an unregistered player does not add them to the registered players list")
    (is (= {} (::d.g/players failed-revive-game))
        "Reviving an unregistered player does not add them to the players list")))

(deftest nominate
  (let [[{id1 ::d.p/id} {id2 ::d.p/id} {id3 ::d.p/id} :as players] (gen-players 3)
        {::d.g/keys [moderator-id] :as game} (started-with-players players)]
    (is (-> game
            (game/nominate id1 id2)
            (game/nominated? id2))
        "Single nominations are recorded")
    (is (-> game
            (game/nominate id1 moderator-id)
            (game/nominated? moderator-id))
        "The moderator can be nominated")
    (is (-> game
            (game/kill id2)
            (game/nominate id1 id2)
            (game/nominated? id2))
        "Dead players can be nominated")
    (is (-> game
            (game/kill id1)
            (game/nominate id1 id2)
            (game/nominated? id2)
            not)
        "Dead players cannot nominate")
    (testing "The same player cannot nominate twice"
      (let [game' (-> game
                      (game/nominate id1 id2)
                      (game/nominate id1 id3))]
        (is (not (game/nominated? game' id3)))
        (is (game/nominated? game' id2))
        (is (not (game/can-nominate? (game/nominate game id1 id2) id1)))))
    (testing "The same player cannot be nominated twice"
      (let [game' (-> game
                      (game/nominate id1 id2)
                      (game/nominate id3 id2))]
        (is (game/nominated? game' id2))
        (is (not (game/can-nominate? game' id1)))
        (is (game/can-nominate? game' id3))))
    (let [game' (game/end-day game)]
      (is (= game' (game/nominate game' id1 id2))
          "You cannot nominate at night"))
    (testing "Nominations are cleared at day change"
      (let [game' (-> game
                      (game/nominate id1 id2)
                      game/end-day
                      game/start-day)]
        (is (not (game/nominated? game' id2)))
        (is (game/can-nominate? game' id1))))))

(deftest vote
  (let [[{id1 ::d.p/id} {id2 ::d.p/id} {id3 ::d.p/id} :as players] (gen-players 3)
        {::d.g/keys [moderator-id] :as game} (started-with-players players)]
    (is (-> game
            (game/vote id1 id2)
            (game/voted-for? id1 id2)
            not)
        "Cannot vote for players who have not been nominated")
    (is (-> game
            (game/nominate id1 id2)
            (game/vote id1 id2)
            (game/voted-for? id1 id2))
        "Can vote for players who have been nominated")
    (testing "Can vote multiple times when alive"
      (let [game' (-> game
                      (game/nominate id1 id2)
                      (game/vote id1 id2)
                      (game/nominate id2 id3)
                      (game/vote id1 id3))]
        (is (game/voted-for? game' id1 id2))
        (is (game/voted-for? game' id1 id3))))
    (is (-> game
            (game/kill id2)
            (game/nominate id1 id3)
            (game/vote id2 id3)
            (game/voted-for? id2 id3))
        "Can vote once when dead")
    (is (-> game
            (game/kill id2)
            (game/nominate id1 id3)
            (game/nominate id3 id1)
            (game/vote id2 id3)
            (game/vote id2 id1)
            (game/voted-for? id2 id1)
            not)
        "Cannot vote twice when dead")
    (let [game' (game/end-day game)]
      (is (= game' (game/vote game' id1 id2))
          "You cannot vote at night"))
    (testing "Your votes are invalidated if you are killed during the day"
      (let [game' (-> game
                      (game/nominate id1 id3)
                      (game/nominate id3 id1)
                      (game/vote id2 id1)
                      (game/vote id2 id3)
                      (game/kill id2))]
        (is (not (game/voted-for? game' id2 id1)))
        (is (not (game/voted-for? game' id2 id3)))))
    (is (-> game
            (game/nominate id1 id2)
            (game/vote id1 id2)
            (game/kill id2)
            (game/voted-for? id1 id2)
            not)
        "Votes on you are invalidated if you are killed during the day")
    (is (-> game
            (game/nominate id1 id2)
            (game/vote id1 id2)
            game/end-day
            game/start-day
            (game/voted-for? id1 id2)
            not)
        "Votes are cleared at day change")))

(deftest votes-by-votee-id
  (let [[{id1 ::d.p/id} {id2 ::d.p/id} {id3 ::d.p/id} :as players] (gen-players 3)
        game (started-with-players players)
        current-totals (fn [game] (game/votes-by-votee-id (get-in game [::d.g/current-day ::d.g/votes])))]
    (is (= {id1 1 id2 1}
           (-> game
               (game/nominate id1 id2)
               (game/vote id1 id2)
               (game/nominate id3 id1)
               (game/vote id3 id1)
               current-totals))
        "Single votes are counted")
    (is (= {id1 2}
           (-> game
               (game/nominate id2 id1)
               (game/vote id2 id1)
               (game/vote id3 id1)
               current-totals))
        "Multiple votes on the same player are counted")
    (is (= {id1 1 id3 1}
           (-> game
               (game/nominate id2 id1)
               (game/vote id2 id1)
               (game/nominate id1 id3)
               (game/vote id2 id3)
               current-totals))
        "Multiple votes by the same player are counted")
    (is (= {}
           (-> game
               (game/nominate id1 id2)
               (game/vote id1 id2)
               (game/nominate id2 id3)
               (game/vote id2 id3)
               (game/kill id2)
               current-totals))
        "Invalidated votes are not counted")))

(deftest today-and-yesterday-totals
  (let [[{id1 ::d.p/id} {id2 ::d.p/id} {id3 ::d.p/id} :as players] (gen-players 3)
        game (-> (started-with-players players)
                 (game/nominate id1 id2)
                 (game/vote id1 id2)
                 (game/nominate id3 id1)
                 (game/vote id3 id1))
        day1-totals {id1 1 id2 1}
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
  (let [[{id1 ::d.p/id} {id2 ::d.p/id} {id3 ::d.p/id} :as players] (gen-players 3)
        game (-> (started-with-players players)
                 (game/nominate id1 id2)
                 (game/vote id1 id2)
                 (game/nominate id3 id1)
                 (game/vote id3 id1))]
    (is (= #{id2} (game/nonvoters game))
        "A single nonvoter is counted during the day")
    (is (= #{} (-> game
                   (game/vote id2 id1)
                   (game/kill id2)
                   game/nonvoters))
        "Players whose votes have been invalidated are not counted as nonvoters")
    (is (nil? (-> game game/end-day game/nonvoters))
        "Nonvoters is nil at night")))

(deftest who-dies
  (let [[{id1 ::d.p/id} {id2 ::d.p/id} {id3 ::d.p/id} :as players] (gen-players 3)
        game (-> (started-with-players players)
                 (game/nominate id1 id2)
                 (game/vote id1 id2)
                 (game/nominate id3 id1)
                 (game/vote id3 id1))]
    (is (nil? (game/who-dies game))
        "No one dies when no one has a majority of votes")
    (is (= id1 (-> game
                   (game/vote id2 id1)
                   game/who-dies))
        "A single player with a majority of votes dies")
    (is (nil? (-> game
                  (game/vote id2 id1)
                  (game/vote id3 id2)
                  game/who-dies))
        "No one dies when there is a tie, even when there is a majority of votes")
    (is (= id1 (-> game
                   (game/vote id2 id1)
                   game/end-day
                   game/who-dies))
        "who-dies returns who died yesterday if it's night")))
