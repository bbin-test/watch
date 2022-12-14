(ns rads.watch
  (:require [babashka.cli :as cli]
            [babashka.process :refer [sh] :as process]
            [clojure.core.async :refer [<!] :as async]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(require '[babashka.pods :as pods])
(pods/load-pod 'org.babashka/fswatcher "0.0.3")
(require '[pod.babashka.fswatcher :as fw])

(defn print-help [_]
  (println (str/trim "
Usage: watch [utility]

Run arbitrary commands when files change.

Examples:
  ls | watch")))

(declare print-commands)

(defn- start-builder [build-events build-fn]
  (async/go-loop [i 1]
    (let [event (<! build-events)]
      (log/info [:start-build i event])
      (build-fn)
      (log/info [:end-build i event])
      (recur (inc i)))))

(defn- build-event? [{:keys [type path] :as _watch-event}]
  (and (not (#{:chmod} type))
       (not (str/ends-with? path "~"))))

(defn start-watchers [build-events]
  (loop []
    (when-let [line (read-line)]
      (fw/watch line #(async/put! build-events %) {:recursive true})
      (recur))))

(defn print-version [_]
  (println "watch 0.0.4"))

(defn watch [opts]
  (cond
    (:help opts) (print-help nil)
    (:version opts) (print-version nil)
    :else (do
            (let [build-fn #(sh (concat (process/tokenize (first (:utility opts)))
                                        (rest (:utility opts)))
                                {:out :inherit :err :inherit})
                  build-xf (filter build-event?)
                  build-events (async/chan (async/sliding-buffer 1) build-xf)]
              (start-builder build-events build-fn)
              (start-watchers build-events))
            (deref (promise)))))

(defn run-help [{:keys [opts] :as parsed-args}]
  (if (:version opts)
    (print-version parsed-args)
    (print-help parsed-args)))

(def commands
  [{:cmds ["commands"] :fn #(print-commands %)}
   {:cmds ["help"] :fn run-help}
   {:cmds ["version" :fn print-version]}
   {:cmds [] :fn #(watch (assoc (:opts %) :utility (:args %)))
    :aliases {:h :help}}])

(defn print-commands [_]
  (println (str/join " " (keep #(first (:cmds %)) commands)))
  nil)

(defn set-logging-config! [{:keys [debug]}]
  (log/merge-config! {:min-level (if debug :debug :warn)}))

(defn -main [& _]
  (set-logging-config! (cli/parse-opts *command-line-args*))
  (cli/dispatch commands *command-line-args* {}))
