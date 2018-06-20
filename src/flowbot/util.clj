(ns flowbot.util)

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

