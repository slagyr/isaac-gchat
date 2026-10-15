(ns isaac.comm.gchat.reply-placement
  "Chat turn origin, transient placement choice and per-space remembered placement."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.foundation.config.root :as root]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]))

(defonce ^:private turns* (atom {}))
(defonce ^:private choices* (atom {}))

(defn begin! [session-key origin]
  (swap! turns* assoc session-key origin)
  (swap! choices* dissoc session-key))

(defn finish! [session-key]
  (swap! turns* dissoc session-key)
  (swap! choices* dissoc session-key))

(defn origin [session-key] (get @turns* session-key))

(defn- filesystem [] (or (fs/instance) (nexus/get :fs) (fs/real-fs)))

(defn record-path [space]
  (when-let [base (or (nexus/get :root) (root/current-root))]
    (str base "/google/chat/" (str/replace space #"[/:]" "-") ".edn")))

(defn- remembered [space]
  (try
    (let [path (record-path space)
          fs*  (filesystem)]
      (when (and path (fs/exists? fs* path))
        (:replies (edn/read-string (fs/slurp fs* path)))))
    (catch Exception _ nil)))

(defn default-where [{:keys [dm? thread-reply?]}]
  (if (and dm? (not thread-reply?)) :top-level :thread))

(defn where [session-key origin]
  (or (get @choices* session-key)
      (remembered (:space origin))
      (default-where origin)))

(defn thread [session-key origin]
  (when (= :thread (where session-key origin)) (:thread origin)))

(defn choose! [session-key where remember?]
  (if-let [origin (origin session-key)]
    (if (#{:thread :top-level} where)
      (do
        (when remember?
          (let [path (record-path (:space origin))
                fs*  (filesystem)]
            (when-not path (throw (ex-info "Chat root not available" {})))
            (fs/mkdirs fs* (fs/parent path))
            (fs/spit fs* path (pr-str {:replies where}))))
        (swap! choices* assoc session-key where)
        {:result {:where where :remembered (boolean remember?)}})
      {:isError true :error "where must be thread or top-level"})
    {:isError true :error "No Google Chat turn is in flight for this session"}))

(defn system-section [{:keys [session-key]}]
  (when-let [{:keys [space dm?]} (origin session-key)]
    {:text  (str "Google Chat space " space " is " (if dm? "a direct message" "a group space") ".")
     :tools ["gchat__reply_in"]}))
