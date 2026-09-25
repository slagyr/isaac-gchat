(ns isaac.comm.gchat.attachment
  "Outbound attachments (isaac-vlxz): Chat takes a media upload per file, and
   the message then references what was uploaded. Every upload happens before
   the message posts, so one failure fails the whole send — never a partial
   message."
  (:require
    [clojure.string :as str]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]))

(def ^:private content-types
  {"pdf"  "application/pdf"
   "png"  "image/png"
   "jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "gif"  "image/gif"
   "webp" "image/webp"
   "svg"  "image/svg+xml"
   "txt"  "text/plain"
   "md"   "text/markdown"
   "csv"  "text/csv"
   "html" "text/html"
   "json" "application/json"
   "zip"  "application/zip"
   "mp3"  "audio/mpeg"
   "mp4"  "video/mp4"})

(defn content-type
  "Media type for a filename, by extension; application/octet-stream when
   the extension is unknown or absent."
  [filename]
  (let [ext (some-> (re-find #"\.([^./]+)$" (str filename)) second str/lower-case)]
    (get content-types ext "application/octet-stream")))

(defn- active-fs []
  (or (nexus/get :fs) (fs/real-fs)))

(defn- read-file [fs* path]
  (or (fs/read-bytes fs* path 0 (fs/size fs* path))
      (throw (ex-info (str "attachment not found: " path) {:path path}))))

(defn upload-all!
  "Uploads each path to the space; returns their attachmentDataRefs in order.
   Throws on the first unreadable file or refused upload."
  [space token paths]
  (let [fs* (active-fs)]
    (mapv (fn [path]
            (let [filename (fs/filename path)]
              (chat-api/upload-attachment! {:space        space
                                            :filename     filename
                                            :content-type (content-type filename)
                                            :bytes        (read-file fs* path)
                                            :token        token})))
          paths)))
