(ns ib.positions-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [ib.positions :as positions]))

(deftest positions-snapshot-success-test
  (testing "collects position events until position-end"
    (let [events (async/chan 10)
          out (positions/positions-snapshot-from-events! events {:timeout-ms 500})]
      (async/>!! events {:type :ib/other})
      (async/>!! events {:type :ib/position
                         :account "DU111"
                         :contract {:conId 1 :symbol "AAPL" :secType "STK" :currency "USD" :exchange "SMART"}
                         :position 10.0
                         :avg-cost 120.5})
      (async/>!! events {:type :ib/position
                         :account "DU111"
                         :contract {:conId 2 :symbol "MSFT" :secType "STK" :currency "USD" :exchange "SMART"}
                         :position 4.0
                         :avg-cost 300.0})
      (async/>!! events {:type :ib/position-end})
      (let [result (async/<!! out)]
        (is (vector? result))
        (is (= 2 (count result)))
        (is (= [:ib/position :ib/position] (mapv :type result)))))))

(deftest positions-snapshot-timeout-test
  (testing "returns timeout error object instead of hanging"
    (let [events (async/chan 1)
          out (positions/positions-snapshot-from-events! events {:timeout-ms 30})
          result (async/<!! out)]
      (is (= :ib/error (:type result)))
      (is (= :timeout (:reason result)))
      (is (= 30 (:timeout-ms result))))))
