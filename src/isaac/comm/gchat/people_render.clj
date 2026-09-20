(ns isaac.comm.gchat.people-render
  "How a Chat message's sender reads in a tool result or a context line.
   Chat gives a human sender users/<id> + displayName and never an email, so
   this is where the People API resolve is asked for one (isaac-jqk2)."
  (:require
    [isaac.google.people :as people]))

(defn sender-label
  "\"Micah Martin <micah@tonotop.com>\", degrading to whatever Chat gave."
  [message]
  (let [sender (:sender message)
        user   (:name sender)
        shown  (:displayName sender)
        entry  (or (try (people/resolve user {:display-name shown}) (catch Exception _ nil))
                   {:user user :display-name shown})]
    (people/render entry)))
