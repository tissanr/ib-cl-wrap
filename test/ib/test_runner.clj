(ns ib.test-runner
  (:gen-class)
  (:require [clojure.test :as t]
            [ib.events-test]
            [ib.positions-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'ib.events-test 'ib.positions-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
