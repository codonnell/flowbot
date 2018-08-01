(ns flowbot.mafia.game-test
  (:require [flowbot.mafia.game :as game]
            [flowbot.mafia.data.game :as d.g]
            [flowbot.mafia.data.event :as d.e]
            [flowbot.mafia.data.player :as d.p]
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

(defn ballot-for [game voter-id]
  (->> (get-in game [::d.g/current-day ::d.g/votes])
       (filterv #(= voter-id (::d.g/voter-id %)))
       peek
       ::d.g/votee-id))

(def has-not-voted? (comp nil? ballot-for))
(def voted-for-no-one? (comp #(= ::d.g/no-one %) ballot-for))
(def vote-invalidated? (comp #(= ::d.g/invalidated %) ballot-for))

(deftest vote
  (let [[{id1 ::d.p/id} {id2 ::d.p/id} {id3 ::d.p/id} :as players] (gen-players 3)
        game (started-with-players players)]
    (is (= id2 (ballot-for (game/vote game id1 id2) id1))
        "Single votes are recorded")
    (is (= id3 (ballot-for (-> game (game/vote id1 id2) (game/vote id1 id3))
                           id1))
        "Subsequent votes by a player override their previous votes")
    (is (= id1 (ballot-for (game/vote game id1 id1) id1))
        "Players can vote for themselves")
    (testing "Unvote votes for no one (different from not voting)"
      (let [unvote-game (game/unvote game id1)]
        (is (voted-for-no-one? unvote-game id1))
        (is (not (has-not-voted? unvote-game id1)))))
    (let [game' (-> game (game/vote id1 id2) (game/vote id2 id3))]
      (testing "Multiple votes are recorded"
        (is (= id2 (ballot-for game' id1)))
        (is (= id3 (ballot-for game' id2)))))
    (is (has-not-voted? (-> game (game/kill id1) (game/vote id1 id2))
                        id1)
        "Dead players cannot vote")
    (let [unreg-player-id (generate ::d.p/id)]
      (is (has-not-voted? (game/vote game unreg-player-id id2) unreg-player-id)
          "Unregistered players cannot vote"))
    (let [kill-vote-game (-> game
                             (game/vote id1 id2)
                             (game/vote id2 id3)
                             (game/kill id2))
          revive-vote-game (game/revive kill-vote-game id2)]
      ;; TODO: Test that a player who's voted for themself doesn't have their vote invalidated twice
      (testing "Killing a player invalidates votes for and by them"
        (is (vote-invalidated? kill-vote-game id1)
            "Killing a player invalidates votes for them")
        (is (vote-invalidated? kill-vote-game id2)
            "Killing a player invalidates their vote"))
      (testing "Reviving a player does not re-validate votes for and by them"
        (is (vote-invalidated? revive-vote-game id1)
            "Killing a player invalidates votes for them")
        (is (vote-invalidated? revive-vote-game id2)
            "Killing a player invalidates their vote")))))

(deftest invalidate-votes-for
  (let [[{id1 ::d.p/id} {id2 ::d.p/id} {id3 ::d.p/id} :as players] (gen-players 3)
        game (game/vote (started-with-players players) id1 id2)
        night-game (game/end-day game)]
    (is (vote-invalidated? (game/invalidate-votes-for game id2) id1)
        "Votes for a player are invalidated")
    (testing "Multiple votes for a player are all invalidated"
      (let [game' (-> game (game/vote id3 id2) (game/invalidate-votes-for id2))]
        (is (vote-invalidated? game' id1))
        (is (vote-invalidated? game' id3))))
    (is (not (vote-invalidated? (-> game (game/vote id1 id3) (game/invalidate-votes-for id2))
                                id1))
        "Outdated votes do not cause invalidation")
    (is (= night-game (game/invalidate-votes-for night-game id2))
        "Invalidating votes does nothing at night")))

(deftest invalidate-vote-by
  (let [[{id1 ::d.p/id} {id2 ::d.p/id} {id3 ::d.p/id} :as players] (gen-players 3)
        game (game/vote (started-with-players players) id1 id2)
        night-game (game/end-day game)]
    (is (vote-invalidated? (game/invalidate-vote-by game id1) id1)
        "Invalidating the vote of a player who has voted succeeds")
    (is (has-not-voted? (game/invalidate-vote-by game id2) id2)
        "Invalidating the vote of a player who has not voted does nothing")
    (is (= night-game (game/invalidate-vote-by night-game id1))
        "Invalidating a vote does nothing at night")))

(deftest votes-by-votee-id
  (let [[{id1 ::d.p/id} {id2 ::d.p/id} {id3 ::d.p/id} :as players] (gen-players 3)
        game (started-with-players players)
        current-totals (fn [game] (game/votes-by-votee-id (get-in game [::d.g/current-day ::d.g/votes])))]
    (is (= {id1 1 id2 1}
           (-> game (game/vote id1 id2) (game/vote id3 id1) current-totals))
        "Single votes are counted")
    (is (= {id1 2}
           (-> game (game/vote id2 id1) (game/vote id3 id1) current-totals))
        "Multiple votes are counted")
    (is (= {::d.g/no-one 1}
           (-> game (game/vote id1 id2) (game/unvote id1) current-totals))
        "Votes for no one are counted")
    (is (= {::d.g/invalidated 1}
           (-> game (game/vote id1 id2) (game/invalidate-vote-by id1) current-totals))
        "Invalidated votes are counted")
    (is (= {::d.g/invalidated 1} (-> game (game/vote id1 id2) (game/kill id1) current-totals))
        "Dead player votes are invalidated")))

(deftest today-and-yesterday-totals
  (let [[{id1 ::d.p/id} {id2 ::d.p/id} {id3 ::d.p/id} :as players] (gen-players 3)
        game (-> (started-with-players players)
                 (game/vote id1 id2)
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
                 (game/vote id1 id2)
                 (game/vote id3 id1))]
    (is (= #{id2} (game/nonvoters game))
        "A single nonvoter is counted during the day")
    (is (= #{} (-> game (game/unvote id2) game/nonvoters))
        "Players that vote for no one are not counted as nonvoters")
    (is (= #{} (-> game (game/vote id2 id1) (game/invalidate-vote-by id2) game/nonvoters))
        "Players whose votes have been invalidated are not counted as nonvoters")
    (is (nil? (-> game game/end-day game/nonvoters))
        "Nonvoters is nil at night")))
