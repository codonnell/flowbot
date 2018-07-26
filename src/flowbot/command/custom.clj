(ns flowbot.command.custom
  (:require [flowbot.registrar :as reg]
            [flowbot.event.handler :as event.handler]
            [flowbot.data.postgres :as pg]
            [flowbot.command.data.custom :as d.custom]
            [flowbot.discord.action :as discord.action]
            [flowbot.interceptor.registrar :as int.reg]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [manifold.bus :as bus]
            [manifold.stream :as stream]
            [integrant.core :as ig]))

(defn extract-args
  "Given a format string like \"Foo %1 bar %2 baz %1\", returns a vector
  containing the indexes of the arguments. In this example, returns [0 1 0]."
  [format-string]
  (mapv (comp dec #(Integer/parseInt %) second)
        (re-seq #"%(\d)" format-string)))

(defn ->clj-format-string [format-string]
  (str/replace format-string #"%\d" "%s"))

(defn format-custom-command
  "Replaces %1, %2, etc. in `format-string` with the 1st, 2nd, etc. words
  following the command in `message`."
  [format-string message]
  (let [arg-indexes (extract-args format-string)
        num-args (if (empty? arg-indexes) 0 (inc (apply max arg-indexes)))
        message-args (subvec (str/split message #"\s+" (inc num-args)) 1)
        ordered-args (mapv #(get message-args %) arg-indexes)
        clj-format-string (->clj-format-string format-string)]
    (apply format clj-format-string ordered-args)))

(def custom-command-handler
  {:id ::custom-command-handler
   :interceptors [discord.action/reply-interceptor
                  ::pg/inject-conn
                  ::d.custom/inject-custom-command]
   :handler-fn
   (int.reg/interceptor
    {:name ::custom-command-handler-fn
     :leave (fn [{:keys [event] {::d.custom/keys [format-string] :as command} ::d.custom/command :as ctx}]
              (cond-> ctx
                command (update :effects assoc
                                ::discord.action/reply {:content (format-custom-command format-string (:content event))})))})})

(defn- execute-command [{name :command :as message}]
  (event.handler/execute (event.handler/handler custom-command-handler) message))

(defmethod ig/init-key :command/custom [_ {:keys [event-bus]}]
  (let [command-stream (bus/subscribe event-bus :command)]
    (stream/consume execute-command command-stream)
    command-stream))

(defmethod ig/halt-key! :command/custom [_ command-stream]
  (stream/close! command-stream))
