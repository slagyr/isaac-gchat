(ns isaac.comm.gchat.transcript
  "What was said in a space while Isaac was not spoken to.

   Isaac answers on a mention (or in a DM), but a space keeps talking in
   between, and a mention that arrives with no context reads like a riddle.
   Every message the gate accepts from an allowed sender is appended here —
   one EDN line per message, per space, bounded — and the lines since Isaac
   last replied become the context block of the next turn. No LLM, no turn,
   no growth without a ceiling (isaac-iv5c)."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]))

(def LIMIT
  "Lines kept per space. A mention reads at most CONTEXT-LINES of them; the
   rest is slack so a busy space does not lose its recent past to one trim."
  200)

(def CONTEXT-LINES
  "How many lines a mention carries when the thread gives no better bound."
  20)

(defn- fs* []
  (try (or (fs/instance) (nexus/get :fs) (fs/real-fs)) (catch Exception _ nil)))

(defn- root* []
  (try (or (nexus/get :root) (root/current-root)) (catch Exception _ nil)))

(defn space-file
  "…/google/chat/spaces-AAQA.ednl — one file per space."
  ([space] (space-file (root*) space))
  ([root space]
   (when (and (seq (str (or root ""))) (seq (str (or space ""))))
     (str root "/google/chat/" (str/replace (str space) #"[/:]" "-") ".ednl"))))

(defn- read-lines [path]
  (let [fs (fs*)]
    (when (and fs path (fs/exists? fs path))
      (->> (str/split-lines (str (fs/slurp fs path)))
           (remove str/blank?)
           (keep (fn [line] (try (edn/read-string line) (catch Exception _ nil))))
           vec))))

(defn recent
  "The last n lines for a space, oldest first. Never throws: a space with no
   file, no root or no fs simply has nothing to say."
  ([space] (recent space CONTEXT-LINES))
  ([space n]
   (try
     (let [lines (or (read-lines (space-file space)) [])]
       (vec (take-last n lines)))
     (catch Exception _ []))))

(defn append!
  "Append one message. Trims to LIMIT when it grows past it."
  [space entry]
  (try
    (let [fs   (fs*)
          path (space-file space)]
      (when (and fs path)
        (let [existing (or (read-lines path) [])
              kept     (vec (take-last LIMIT (conj existing entry)))]
          (fs/mkdirs fs (fs/parent path))
          (fs/spit fs path (str (str/join "\n" (map pr-str kept)) "\n"))
          kept)))
    (catch Exception _ nil)))

(defn since-reply
  "The lines a turn should see: everything after Isaac's own last line in this
   space, capped at n. A space Isaac has never spoken in gives its last n."
  ([space] (since-reply space CONTEXT-LINES))
  ([space n]
   (try
     (let [lines (or (read-lines (space-file space)) [])
           after (if-let [idx (last (keep-indexed (fn [i l] (when (:self? l) i)) lines))]
                   (subvec lines (inc idx))
                   lines)]
       (vec (take-last n after)))
     (catch Exception _ []))))

(defn entry
  "One line: who spoke, in which thread, when, and what they said."
  [decision]
  (cond-> {:sender (:sender decision)
           :text   (:text decision)}
    (:thread decision) (assoc :thread (:thread decision))
    (:at decision)     (assoc :at (:at decision))
    (:self? decision)  (assoc :self? true)))
