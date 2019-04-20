(ns flowbot.botc.data.player
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [flowbot.util :as util]))

(def names #{"Aaren" "Aarika" "Abagael" "Abagail" "Abbe" "Abbey" "Abbi" "Abbie" "Abby" "Abbye" "Abigael" "Abigail" "Abigale" "Abra" "Ada" "Adah" "Adaline" "Adan" "Adara" "Adda" "Addi" "Addia" "Addie" "Addy" "Adel" "Adela" "Adelaida" "Adelaide" "Adele" "Adelheid" "Adelice" "Adelina" "Adelind" "Adeline" "Adella" "Adelle" "Adena" "Adey" "Adi" "Adiana" "Adina" "Adora" "Adore" "Adoree" "Adorne" "Adrea" "Adria" "Adriaens" "Adrian" "Adriana" "Adriane" "Adrianna" "Adrianne" "Adriena" "Adrienne" "Aeriel" "Aeriela" "Aeriell" "Afton" "Ag" "Agace" "Agata" "Agatha" "Agathe" "Aggi" "Aggie" "Aggy" "Agna" "Agnella" "Agnes" "Agnese" "Agnesse" "Agneta" "Agnola" "Agretha" "Aida" "Aidan" "Aigneis" "Aila" "Aile" "Ailee" "Aileen" "Ailene" "Ailey" "Aili" "Ailina" "Ailis" "Ailsun" "Ailyn" "Aime" "Aimee" "Aimil" "Aindrea" "Ainslee" "Ainsley" "Ainslie" "Ajay" "Alaine" "Alameda" "Alana"})

(def cur-player-id (atom 0))

(s/def ::id (s/with-gen pos-int? #(gen/fmap (fn [_] (swap! cur-player-id inc)) (gen/return nil))))
(s/def ::username (s/with-gen ::util/nonblank-string #(s/gen names)))
(s/def ::index pos-int?)
(s/def ::player (s/keys :req [::id ::username]
                        :opt [::index]))
