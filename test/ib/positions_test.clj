(ns ib.positions-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [ib.client]
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
        (is (true? (:ok result)))
        (is (= 2 (count (:positions result))))
        (is (= [:ib/position :ib/position] (mapv :type (:positions result))))))))

(deftest positions-snapshot-timeout-test
  (testing "returns timeout error object instead of hanging"
    (let [events (async/chan 1)
          out (positions/positions-snapshot-from-events! events {:timeout-ms 30})
          result (async/<!! out)]
      (is (false? (:ok result)))
      (is (= :timeout (:error result)))
      (is (= 30 (:timeout-ms result))))))

(deftest positions-snapshot-stream-closed-test
  (testing "returns closed-stream error when source closes before position-end"
    (let [events (async/chan 1)
          out (positions/positions-snapshot-from-events! events {:timeout-ms 500})]
      (async/close! events)
      (let [result (async/<!! out)]
        (is (false? (:ok result)))
        (is (= :event-stream-closed (:error result)))))))

(deftest positions-snapshot-api-test
  (testing "positions-snapshot! returns collector result and unsubscribes"
    (let [sub-ch (async/chan 1)
          collector-out (async/chan 1)
          req-called? (atom false)
          unsub-called? (atom false)]
      (async/>!! collector-out {:ok true
                                :positions [{:type :ib/position :account "DU1"}]})
      (async/close! collector-out)
      (with-redefs [ib.client/subscribe-events! (fn [_ _] sub-ch)
                    ib.client/req-positions! (fn [_] (reset! req-called? true) true)
                    ib.client/unsubscribe-events! (fn [_ _] (reset! unsub-called? true))
                    ib.positions/positions-snapshot-from-events! (fn [_ _] collector-out)]
        (let [out (positions/positions-snapshot! {:dummy true} {:timeout-ms 200})
              result (async/<!! out)]
          (is (true? @req-called?))
          (is (true? @unsub-called?))
          (is (= {:ok true
                  :positions [{:type :ib/position :account "DU1"}]}
                 (select-keys result [:ok :positions])))))))

  (testing "positions-snapshot! returns request-failed error"
    (let [sub-ch (async/chan 1)
          collector-out (async/chan 1)
          unsub-calls (atom 0)]
      (async/close! collector-out)
      (with-redefs [ib.client/subscribe-events! (fn [_ _] sub-ch)
                    ib.client/req-positions! (fn [_] (throw (ex-info "boom" {})))
                    ib.client/unsubscribe-events! (fn [_ _] (swap! unsub-calls inc))
                    ib.positions/positions-snapshot-from-events! (fn [_ _] collector-out)]
        (let [out (positions/positions-snapshot! {:dummy true} {:timeout-ms 200})
              result (async/<!! out)]
          (is (false? (:ok result)))
          (is (= :request-failed (:error result)))
          (is (pos? @unsub-calls)))))))
