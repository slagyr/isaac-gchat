(ns isaac.comm.gchat.format
  "Markdown → Google Chat text, plus Discord-style newline-then-hard split."
  (:require
    [clojure.string :as str]))

(def default-message-cap 4096)

(defn- fence-table [s]
  (if (re-find #"(?m)^\s*\|.+\|\s*$" s)
    (str "```\n" (str/trim-newline s) "\n```")
    s))

(defn ->chat-text
  "Small translate: markdown bold/italic/code/links; tables → code block."
  [s]
  (let [s (str s)
        s (fence-table s)
        s (str/replace s #"\[([^\]]+)\]\(([^)]+)\)" "<$2|$1>")
        s (str/replace s #"\*\*([^*]+)\*\*" "\u0000$1\u0000")
        s (str/replace s #"(?<!\*)\*([^*]+)\*(?!\*)" "_$1_")
        s (str/replace s #"\u0000([^\u0000]+)\u0000" "*$1*")]
    s))

(defn- split-at-cap [s cap]
  (mapv #(subs s % (min (count s) (+ % cap)))
        (range 0 (count s) cap)))

(defn split-content
  "Split at newline boundaries, then hard-split oversize lines. Discord's algorithm."
  [content cap]
  (let [cap (or cap default-message-cap)]
    (if (or (nil? cap) (<= (count (str content)) cap))
      [(str content)]
      (let [lines (str/split (str content) #"\n" -1)]
        (loop [remaining lines
               current   nil
               chunks    []]
          (if-let [line (first remaining)]
            (let [candidate (if current (str current "\n" line) line)]
              (cond
                (<= (count candidate) cap)
                (recur (rest remaining) candidate chunks)

                current
                (recur remaining nil (conj chunks current))

                :else
                (let [parts (split-at-cap line cap)]
                  (recur (rest remaining) nil (into chunks parts)))))
            (cond-> chunks current (conj current))))))))
