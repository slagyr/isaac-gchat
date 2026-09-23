(ns isaac.gchat-steps
  "Google Chat inbound + outbound feature steps."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.api :as api]
    [isaac.comm.factory :as comm-factory]
    [isaac.comm.gchat :as gchat]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.comm.gchat.guidance :as guidance]
    [isaac.comm.gchat.handler :as handler]
    [isaac.comm.gchat.lookup :as gchat-lookup]
    [isaac.comm.gchat.self :as gchat-self]
    [isaac.comm.protocol :as comm]
    [isaac.comm.registry :as comm-registry]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.google.people :as people]
    [isaac.google.tenants :as tenants]
    [isaac.google.token :as google-token]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.auth.store :as auth-store]
    [isaac.logger :as log]
    [isaac.module.discovery :as discovery]
    [isaac.nexus :as nexus]
    [isaac.session.session-steps :as session-steps]
    [isaac.session.store.memory :as memory-store]
    [isaac.session.store.spi :as session-store]))

(helper! isaac.gchat-steps)

(declare restore-chat-http!)

(g/after-scenario
  (fn []
    (alter-var-root #'discovery/*foundation-index-override* (constantly nil))
    (restore-chat-http!)
    (gchat-lookup/forget!)
    (g/dissoc! :gchat-comm)
    (g/dissoc! :gchat-http-stub)
    (g/dissoc! :gchat-known-spaces)
    (g/dissoc! :gchat-refused-spaces-get)
    (g/dissoc! :gchat-refused-create)
    ;; the people memo and its warn-once marks outlive a scenario otherwise
    (people/reset-memo!)
    ;; likewise the per-tenant self cache - otherwise a later scenario could
    ;; inherit an id an earlier one learned (isaac-mm7o)
    (gchat-self/forget!)))

(defn- kv-cells->map [cells]
  (when (and (seq cells) (even? (count cells)))
    (into {} (map (fn [[k v]] [k v]) (partition 2 cells)))))

(defn- table-map [{:keys [headers rows]}]
  (or (let [header-map (kv-cells->map headers)
            row-map    (apply merge {} (keep kv-cells->map rows))]
        (when (or header-map (seq row-map))
          (merge header-map row-map)))
      (when (and (seq headers) (= 1 (count rows)))
        (zipmap headers (first rows)))
      {}))

(defn- nest-dotted [m]
  (reduce (fn [acc [k v]]
            (assoc-in acc (mapv keyword (str/split (str k) #"\.")) v))
          {}
          m))

(defn- parse-cell [v]
  (cond
    (nil? v) v
    (and (string? v) (re-matches #"-?\d+" v)) (parse-long v)
    :else v))

(defn- path-table->record [table]
  (let [pairs (if (and (= ["path" "value"] (:headers table))
                       (seq (:rows table)))
                (:rows table)
                (or (seq (map (fn [row]
                                (if (= 2 (count row))
                                  row
                                  [(first row) (second row)]))
                              (:rows table)))
                    (when (and (seq (:headers table)) (seq (:rows table)))
                      (map vector (:headers table) (first (:rows table))))))]
    (reduce (fn [acc [path value]]
              (let [s  (str path)
                    ks (if (str/starts-with? s "gchat/")
                         [(keyword s)]
                         (mapv keyword (str/split s #"/")))]
                (assoc-in acc ks (parse-cell value))))
            {}
            pairs)))

(defn- feature-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- root-dir []
  (or (g/get :runtime-root-dir) (g/get :root)))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (feature-fs)}
    (f)))

(defn- gchat-module-coord []
  {:isaac.comm.gchat {:local/root (System/getProperty "user.dir")}})

(defn- gchat-module-index []
  (let [local (io/file (System/getProperty "user.dir") "resources" "isaac-manifest.edn")
        manifest (cond
                   (.exists local) (edn/read-string (slurp local))
                   :else (some-> (io/resource "isaac-manifest.edn") slurp edn/read-string))]
    (when manifest
      {:isaac.comm.gchat {:coord    {:local/root (System/getProperty "user.dir")}
                          :manifest manifest
                          :path     nil}})))

(defn inject-gchat-module! []
  (alter-var-root #'discovery/*foundation-index-override*
                  (fn [prev]
                    (merge (or prev (discovery/builtin-index))
                           (gchat-module-index))))
  (when-let [root (root-dir)]
    (with-feature-fs
      (fn []
        (let [path    (str root "/config/isaac.edn")
              fs*     (feature-fs)
              current (if (fs/exists? fs* path)
                        (edn/read-string (fs/slurp fs* path))
                        {})
              updated (update current :modules merge (gchat-module-coord))]
          (fs/mkdirs fs* (fs/parent path))
          (fs/spit fs* path (pr-str updated)))))))

(defn- ensure-session-store! []
  (when-not (session-store/registered-store)
    (when-let [root (root-dir)]
      (session-store/register-store! (memory-store/create-store root)))))

(defn- ensure-gchat-factory! []
  (when-not (get-method comm-factory/create :gchat)
    (require 'isaac.comm.gchat)))

(defn- load-comm-cfg [name]
  (let [fs*  (feature-fs)
        root (root-dir)
        cfg  (:config (loader/load-config-result {:root root :fs fs*}))]
    (or (get-in cfg [:comms (keyword name)])
        (get-in cfg [:comms (clojure.core/name name)])
        {})))

(defn- record-http! [req]
  (let [flat (cond-> {:method  (:method req)
                      :url     (:url req)
                      :headers (:headers req)
                      :body    (:body req)
                      :query   (:query req)}
               (:query req) (assoc :query (:query req)))]
    (g/update! :outbound-http-requests (fn [prior] (vec (conj (or prior []) flat))))
    (g/assoc! :outbound-http-request flat)
    req))

(defn- bearer-token
  "The token a stubbed send authenticated with, bare (no \"Bearer \")."
  [req]
  (some-> (get-in req [:headers "Authorization"])
          (str/replace #"^Bearer " "")))

(defn- space-of-url
  "spaces/AAQA7rg5Uyc of https://chat.googleapis.com/v1/spaces/AAQA7rg5Uyc."
  [url]
  (second (re-find #"/v1/(spaces/[^/?]+)" (str url))))

(defn- refused-spaces-get []
  (or (g/get :gchat-refused-spaces-get) #{}))

(defn- refused-create []
  (or (g/get :gchat-refused-create) #{}))

(defn- known-spaces-listing
  "Every space 'the Chat API knows space' has named, as spaces.list would
   report them - the fallback listing a DM's spaces.get 403 falls back to
   (isaac-qry7, isaac-f4ab)."
  []
  (mapv (fn [[space info]] (assoc info :name space))
        (g/get :gchat-known-spaces)))

(defn- stub-http! [req]
  (let [req' (record-http! req)
        stub (g/get :gchat-http-stub)
        url  (:url req')]
    (cond
      ;; reactions.create — Chat's :name is a relative resource path (never a
      ;; full URL); the stub's is deterministic (the message plus "/1") so a
      ;; scenario can predict the reactions.delete URL that follows.
      (and (str/ends-with? (str url) "/reactions") (= "POST" (:method req')))
      {:status 200 :body {:name (str (str/replace-first (str url) "https://chat.googleapis.com/v1/" "") "/1")}}

      ;; reactions.delete — DELETE on the reaction's own resource name.
      (re-find #"/reactions/\d+$" (str url))
      {:status 200 :body {}}

      (and stub (= url "https://chat.googleapis.com/v1/spaces:findDirectMessage")
           (:no-dm stub))
      {:status 404 :body {}}

      (and stub (= url "https://chat.googleapis.com/v1/spaces:setup")
           (:setup-space stub))
      {:status 200 :body {:name (:setup-space stub)}}

      (and (str/includes? (str url) "/messages")
           (contains? (refused-create) (space-of-url url)))
      {:status 403 :body {:error {:code 403 :message "PERMISSION_DENIED: The caller does not have permission"}}}

      (str/includes? (str url) "/messages")
      ;; The sender on a created message is Isaac's own account - the token
      ;; that authenticated the send stands in for it, so each organization's
      ;; stubbed sends learn a distinct users/<id> (isaac-mm7o).
      {:status 200 :body {:name   (str (:url req') "/posted")
                          :sender {:name (str "users/self-" (bearer-token req'))}}}

      ;; spaces.list: the account's own listing, the fallback a refused
      ;; spaces.get falls back to.
      (= url "https://chat.googleapis.com/v1/spaces")
      {:status 200 :body {:spaces (known-spaces-listing)}}

      (and (str/starts-with? (str url) "https://chat.googleapis.com/v1/spaces/")
           (contains? (refused-spaces-get) (space-of-url url)))
      {:status 403 :body {:error {:code 403 :message "PERMISSION_DENIED: The caller does not have permission"}}}

      ;; spaces.get: what Chat calls one space. A space no scenario named is
      ;; one Chat has not named either - the comm falls back to its id.
      (str/starts-with? (str url) "https://chat.googleapis.com/v1/spaces/")
      {:status 200 :body (get (g/get :gchat-known-spaces) (space-of-url url) {})}

      :else
      {:status 200 :body {}})))

(defonce ^:private real-chat-http* (atom nil))

(defn- own-chat-http!
  "Take the Chat HTTP seam for the whole scenario. The registration timer
   ticks in isaac-google's step, outside any with-redefs of ours, so a
   scenario that stubs spaces.list has to hold the seam across steps."
  []
  (when-not @real-chat-http*
    (reset! real-chat-http* chat-api/-http!)
    (alter-var-root #'chat-api/-http! (constantly stub-http!))))

(defn restore-chat-http! []
  (when-let [original @real-chat-http*]
    (alter-var-root #'chat-api/-http! (constantly original))
    (reset! real-chat-http* nil)))

(defn chat-api-knows-space
  "What spaces.get answers for one space for the rest of this scenario - its
   display name, its type - as Chat reports them."
  [space table]
  (own-chat-http!)
  (gchat-lookup/forget!)
  (g/update! :gchat-known-spaces (fnil assoc {}) space (nest-dotted (table-map table))))

(defn outbound-http-count
  "How many requests this scenario made to one URL, whatever their query."
  [n url]
  (let [n    (if (string? n) (parse-long n) n)
        reqs (or (g/get :outbound-http-requests) [])]
    (g/should= n (count (filter #(= url (:url %)) reqs)))))

(defn no-reaction-calls-were-made
  "No reactions.create or reactions.delete request went out - the heard-only
   and reactions-disabled cases (isaac-1bq1)."
  []
  (let [reqs (or (g/get :outbound-http-requests) [])]
    (g/should-not (some #(str/includes? (str (:url %)) "/reactions") reqs))))

(defn session-is-tagged [key tag]
  (let [session (api/get-session key)]
    (g/should (contains? (set (:tags session)) (keyword tag)))))

(defn chat-api-returns [name table]
  (let [msg (assoc (nest-dotted (table-map table)) :name name)]
    (g/update! :gchat-api-messages (fnil assoc {}) name msg)))

(defn- stub-get-message! [name]
  (or (get (g/get :gchat-api-messages) name)
      (throw (ex-info (str "no Chat API stub for " name) {:name name}))))

(defn- stored-access-token
  "The access token one organization has in the auth store, or the token every
   gchat scenario that never signed in has been using. Every login belongs to
   an organization, so without one there is no store to read (isaac-okfj)."
  [id]
  (or (when-let [tokens (when id
                          (with-feature-fs
                            (fn []
                              (auth-store/load-tokens (or (root-dir) "target/test-state")
                                                      (tenants/auth-provider id)
                                                      (feature-fs)))))]
        (or (:access tokens) (:access_token tokens)))
      "at-1"))

(defn- stub-google-token
  "Stands in for isaac.google.token/token so a scenario need not refresh, and
   answers per organization so which one the comm asked for is visible."
  ([] (stub-google-token nil))
  ([id] (stored-access-token id)))

(defn gchat-comm-registered
  "Register one configured Chat comm by name as the comm under test."
  [name]
  (ensure-gchat-factory!)
  (inject-gchat-module!)
  (ensure-session-store!)
  (let [fs*  (feature-fs)
        root (root-dir)
        comm (gchat/make {:name (keyword name) :root root})
        cfg  (nexus/-with-nested-nexus {:fs fs* :root root}
               (load-comm-cfg name))]
    (reset! (.-cfg comm) cfg)
    (comm-registry/register-instance! (clojure.core/name name) comm)
    (g/assoc! :gchat-comm comm)))

(defn gchat-outbound-comm-registered []
  (gchat-comm-registered "gchat"))

(defn google-auth-store-for-organization
  "Seed one organization's tokens in the auth store."
  [organization at rt]
  (with-feature-fs
    (fn []
      (auth-store/save-tokens! (root-dir)
                               (tenants/auth-provider (keyword organization))
                               {:access_token at :refresh_token rt :expires_in 3600}
                               (feature-fs)))))

(defn chat-api-refuses-spaces-get [space]
  (own-chat-http!)
  (gchat-lookup/forget!)
  (g/update! :gchat-refused-spaces-get (fnil conj #{}) space))

(defn chat-api-refuses-create [space]
  (own-chat-http!)
  (g/update! :gchat-refused-create (fnil conj #{}) space))

(defn chat-api-has-no-dm [email]
  (g/update! :gchat-http-stub (fnil assoc {}) :no-dm true :no-dm-email email))

(defn chat-api-creates-space-on-setup [name]
  (g/update! :gchat-http-stub (fnil assoc {}) :setup-space name))

(defn log-entry-count [n event]
  (let [n     (if (string? n) (parse-long n) n)
        event (keyword (str/replace (str event) #"^:" ""))
        hits  (filter #(= event (:event %)) (log/get-entries))]
    (g/should= n (count hits))))

(defn last-llm-request-carries-guidance-once
  "The standing thread guidance (isaac-acou) rides the charge's :guidance,
   which the prompt builder frames into the current user turn once. Grover's
   last request is the built prompt, so counting the guidance text's
   occurrences there proves it was attached, and attached exactly once."
  []
  (let [text (some-> (grover/last-request) pr-str)]
    (g/should text)
    (g/should= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote guidance/TEXT)) text)))))

(defn session-origin-matches
  "The session's :origin, field by field, as the table names them."
  [key table]
  (let [session  (api/get-session key)
        origin   (or (:origin session) (get session "origin") {})
        expected (table-map table)]
    (doseq [[field value] expected]
      (let [actual (or (get origin (keyword field)) (get origin field))]
        (g/should= value (str actual))))))

(defn- with-chat-stubs [f]
  (with-redefs [chat-api/get-message! stub-get-message!
                chat-api/-http!       stub-http!
                google-token/token    stub-google-token]
    (f)))

(defn google-chat-delivers [name]
  (ensure-gchat-factory!)
  (inject-gchat-module!)
  (ensure-session-store!)
  (when-not (g/get :gchat-comm)
    (gchat-outbound-comm-registered))
  (let [fs*  (feature-fs)
        root (root-dir)]
    (nexus/-with-nested-nexus {:fs fs* :root root}
      (let [cfg (:config (loader/load-config-result {:root root :fs fs*}))]
        (config/dangerously-install-config! cfg "gchat feature")
        (grover/clear-provider-requests!)
        (when-let [comm (g/get :gchat-comm)]
          (reset! (.-cfg comm) (or (get-in cfg [:comms :gchat])
                                   (get-in cfg [:comms "gchat"])
                                   {})))
        (with-chat-stubs
          (fn []
            (handler/handle-event {:type "google.workspace.chat.message.v1.created"
                                   :data {:message {:name name}}}))))))
  (session-steps/await-turn!))

(defn gchat-comm-send! [table]
  (ensure-gchat-factory!)
  (inject-gchat-module!)
  (when-not (g/get :gchat-comm)
    (gchat-outbound-comm-registered))
  (let [record (path-table->record table)
        comm   (g/get :gchat-comm)
        fs*    (feature-fs)
        root   (root-dir)
        cfg    (nexus/-with-nested-nexus {:fs fs* :root root}
                 (load-comm-cfg (:name (.-host comm))))]
    (reset! (.-cfg comm) cfg)
    (nexus/-with-nested-nexus {:fs fs* :root root}
      (with-chat-stubs
        (fn []
          (comm/send! comm record))))))

(defgiven #"the Chat API returns message \"([^\"]+)\":"
  isaac.gchat-steps/chat-api-returns)

(defgiven #"the Chat API knows space \"([^\"]+)\":"
  isaac.gchat-steps/chat-api-knows-space)

(defthen #"session \"([^\"]+)\" is tagged \"([^\"]+)\""
  isaac.gchat-steps/session-is-tagged)

(defthen #"^(\d+) outbound HTTP requests? to \"([^\"]+)\" (?:was|were) made$"
  isaac.gchat-steps/outbound-http-count)

(defthen "no reaction calls were made"
  isaac.gchat-steps/no-reaction-calls-were-made)

(defgiven "gchat outbound comm is registered"
  isaac.gchat-steps/gchat-outbound-comm-registered)

(defgiven #"gchat comm \"([^\"]+)\" is registered"
  isaac.gchat-steps/gchat-comm-registered)

(defgiven #"the google auth store for organization \"([^\"]+)\" has access \"([^\"]+)\" and refresh \"([^\"]+)\""
  isaac.gchat-steps/google-auth-store-for-organization)

(defgiven #"the Chat API has no direct message space with \"([^\"]+)\""
  isaac.gchat-steps/chat-api-has-no-dm)

(defgiven #"the Chat API refuses spaces\.get for \"([^\"]+)\" with 403"
  isaac.gchat-steps/chat-api-refuses-spaces-get)

(defgiven #"the Chat API refuses messages\.create in \"([^\"]+)\" with 403"
  isaac.gchat-steps/chat-api-refuses-create)

(defgiven #"the Chat API creates space \"([^\"]+)\" on setup"
  isaac.gchat-steps/chat-api-creates-space-on-setup)

(defwhen #"Google Chat delivers a message event for \"([^\"]+)\""
  isaac.gchat-steps/google-chat-delivers)

(defwhen "gchat comm send! is invoked with:"
  isaac.gchat-steps/gchat-comm-send!)

(defthen #"exactly (\d+) log entr(?:y|ies) has event \"([^\"]+)\""
  isaac.gchat-steps/log-entry-count)

(defthen #"session \"([^\"]+)\" has origin:"
  isaac.gchat-steps/session-origin-matches)

(defthen "the last LLM request carries the gchat thread guidance exactly once"
  isaac.gchat-steps/last-llm-request-carries-guidance-once)
