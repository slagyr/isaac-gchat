(ns isaac.comm.gchat.inbound-attachment
  "Downloads Chat message attachments into the receiving session's workspace."
  (:require
    [clojure.string :as str]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.fs :as fs]
    [isaac.logger :as log]))

(def MAX-BYTES (* 25 1024 1024))

(defn sanitize-filename [filename]
  (let [name (last (str/split (str filename) #"[/\\\\]+"))]
    (if (or (str/blank? name) (= name ".") (= name "..")) "attachment" name)))

(defn- content-bytes [content]
  (.getBytes (str content) "UTF-8"))

(defn- path [cwd message-id filename]
  (str cwd "/attachments/" message-id "/" filename))

(defn- saved-line [filename content-type size message-id]
  (str "[attachment: " filename " (" (or content-type "application/octet-stream") ", " size ") at attachments/"
       message-id "/" filename "]"))

(defn- failure-line [filename reason]
  (str "[attachment: " filename " (" reason ")]"))

(defn save-all!
  "Downloads attachments. Every failure is contained so a routed turn still runs."
  [cwd message-id attachments]
  (mapv (fn [attachment]
          (let [filename     (sanitize-filename (:contentName attachment))
                content-type (or (:contentType attachment) "application/octet-stream")
                resource     (get-in attachment [:attachmentDataRef :resourceName])]
            (try
              (let [content (chat-api/download-attachment! resource)
                    size    (alength (content-bytes content))]
                (if (> size MAX-BYTES)
                  (failure-line filename "too large, not saved")
                  (let [target (path cwd message-id filename)]
                    (fs/mkdirs (fs/instance) (fs/parent target))
                    (fs/spit (fs/instance) target content)
                    (saved-line filename content-type size message-id))))
              (catch Exception e
                (log/warn :gchat.attachment/download-failed :attachment resource :error (.getMessage e))
                (failure-line filename "download failed")))))
        (if (map? attachments) (map second (sort-by first attachments)) (or attachments []))))
