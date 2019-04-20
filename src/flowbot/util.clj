(ns flowbot.util
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]))

(s/def ::nonblank-string (s/and string? #(not (str/blank? %))))

(defn transform-keys [m f]
  (let [transform (fn [[k v]] [(f k) v])]
    (walk/postwalk (fn [x] (if (map? x)
                             (into {} (map transform) x)
                             x))
                   m)))

(defn deep-ns-map-keys [m ns]
  (transform-keys m #(keyword ns (name %))))

(defn ns-map-keys [m ns]
  (into {}
        (map (fn [[k v]] [(keyword ns (name k)) v]))
        m))

(defn de-ns-map-keys [m]
  (into {}
        (map (fn [[k v]] [(keyword (name k)) v]))
        m))

(defn remove-nil-vals [m]
  (into {}
        (remove (fn [[_ v]] (nil? v)))
        m))

(defn parse-long [s]
  (Long/parseLong s))

(defn or-join [xs]
  (case (count xs)
    0 ""
    1 (first xs)
    2 (apply format "%s or %s" xs)
    (let [first-xs (butlast xs)
          last-x (last xs)]
      (format "%s, or %s" (str/join ", " first-xs) last-x))))

(defn and-join [xs]
  (case (count xs)
    0 ""
    1 (first xs)
    2 (apply format "%s and %s" xs)
    (let [first-xs (butlast xs)
          last-x (last xs)]
      (format "%s, and %s" (str/join ", " first-xs) last-x))))
