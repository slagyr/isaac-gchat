(ns isaac.features-main
  "Entry point for `clojure -M:features`.

   gherclj.main/-main only calls System/exit when the run fails. On a green
   run it returns normally, and the JVM then waits for every non-daemon
   thread before exiting. Clojure's send-off pool threads are non-daemon and
   idle for 60s before they die, so any scenario that dispatches a tool -
   tool dispatch runs on a future - left a passing suite hanging ~60s past
   its last assertion. `bb ci` kills jvm-features at 60s, so a fully green
   run reported 'jvm-features timed out after 60s'.

   shutdown-agents releases that pool once the suite is done, which is the
   documented way to let a JVM exit promptly after using futures or agents.
   It runs after gherclj has reported, so it never changes the outcome - only
   how fast the process gets out of the way."
  (:require [gherclj.main :as gherclj]))

(defn -main [& args]
  (try
    (apply gherclj/-main args)
    (finally
      (flush)
      (shutdown-agents))))
