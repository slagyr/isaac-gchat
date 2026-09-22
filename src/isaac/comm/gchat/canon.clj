(ns isaac.comm.gchat.canon
  "A space is a conversation and a conversation is a session.

   Every space the account belongs to routes to one canonical session with no
   config at all. The name is readable — Chat's display name for a space, the
   other member's name for a DM — and it is spelled here the way the session
   store will keep it, because the store slugifies whatever it is handed.

   The space id cannot live in the name: `AAQA7rg5Uyc` slugs to `aaqa7rg5uyc`
   and no longer names the space. It rides on a tag (`space:AAQA7rg5Uyc`),
   verbatim, and the tag — not the name — is what matches. So a renamed space
   keeps its session and the session is renamed with it, and two spaces that
   happen to share a display name still get two sessions (isaac-xy2i,
   isaac-ihuc)."
  (:require
    [clojure.string :as str]))

(def PREFIX "gchat")

(def TAG-PREFIX "space:")

(defn slug
  "Lower case, every run of anything else a hyphen — `isaac.session.store`'s
   own rule, spelled out so the name chosen here is the name it gets."
  [s]
  (-> (str (or s ""))
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn space-id
  "AAQA7rg5Uyc of spaces/AAQA7rg5Uyc — untouched, case and all."
  [space]
  (let [s (str (or space ""))]
    (or (second (re-find #"spaces/([^/]+)" s)) s)))

(defn space-tag
  "The tag a canonical session carries: space:<id>."
  [space]
  (when (seq (str (or space "")))
    (keyword (str TAG-PREFIX (space-id space)))))

(defn space-tags
  "Every space tag a session carries — normally one, never more."
  [session]
  (filter #(str/starts-with? (name %) TAG-PREFIX) (or (:tags session) #{})))

(defn canonical-name
  "The readable session name for a space: the Google organization, then the
   display name — or `dm-<member>` for a direct message. The organization is
   always named, on a host with one as much as on a host with ten, so a session
   says whose space it is without anyone having to look. A space Chat has not
   named falls back to its resource name."
  [{:keys [space tenant display-name dm? member]}]
  (let [body (if dm?
               (when-let [who (not-empty (slug member))] (str "dm-" who))
               (not-empty (slug display-name)))]
    (->> [PREFIX (not-empty (slug tenant)) (or body (slug space))]
         (remove str/blank?)
         (str/join "-"))))

(defn- wanted-name
  "The name this space's session should carry: the canonical one, unless
   another space's session already answers to it, and then the space id
   settles it."
  [space session-key sessions]
  (let [id    (slug session-key)
        taken (first (filter #(= id (slug (or (:id %) (:name %)))) sessions))]
    (if (seq (space-tags taken))
      (str session-key "-" (slug (space-id space)))
      session-key)))

(defn settle
  "Which session this space speaks on, and what it should be called now.

   The session already tagged with the space is the one, whatever it is
   called — the tag is what a rename cannot orphan. When Chat's name for the
   space has moved on, that session is renamed to match and `:rename-from`
   says what it was called. A space nothing has claimed simply takes the
   canonical name."
  [{:keys [space session-key]} sessions]
  (let [tag     (space-tag space)
        tagged  (first (filter #(contains? (set (:tags %)) tag) sessions))
        others  (remove #(identical? % tagged) sessions)
        wanted  (wanted-name space session-key others)
        current (when tagged (or (:name tagged) (:id tagged)))]
    (cond
      (nil? tagged)                    {:session-key wanted}
      (= (slug current) (slug wanted)) {:session-key current}
      :else                            {:session-key wanted :rename-from current})))
