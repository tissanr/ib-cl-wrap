(ns ib.market-data-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [ib.contract]
            [ib.market-data :as market-data]))

(deftest delayed-data-notice-test
  (testing "delayed-data-notice? matches case-insensitively"
    (let [f #'ib.market-data/delayed-data-notice?]
      (is (true? (f {:message "Delayed market data is available"})))
      (is (true? (f {:message "REQUESTED MARKET DATA REQUIRES ADDITIONAL SUBSCRIPTION FOR API. Delayed market data is available."})))
      (is (false? (f {:message "Some other IB error"})))
      (is (false? (f {:message nil}))))))

(deftest market-data-snapshot-delayed-fallback-test
  (testing "snapshot succeeds when delayed-data notice is followed by delayed ticks"
    (let [events-ch (async/chan 8)
          subscribe-called? (atom false)
          unsubscribe-called? (atom false)
          market-data-type-calls (atom [])
          request-args (atom nil)
          cancel-calls (atom [])
          conn {}
          rid (atom nil)]
      (with-redefs [ib.market-data/next-req-id! (fn []
                                                  (reset! rid 12345)
                                                  @rid)
                    ib.client/subscribe-events! (fn [_ _]
                                                  (reset! subscribe-called? true)
                                                  events-ch)
                    ib.client/unsubscribe-events! (fn [_ ch]
                                                    (reset! unsubscribe-called? (= ch events-ch))
                                                    ch)
                    ib.client/req-market-data-type! (fn [_ mode]
                                                      (swap! market-data-type-calls conj mode)
                                                      true)
                    ib.client/req-mkt-data! (fn [_ opts]
                                              (reset! request-args opts)
                                              true)
                    ib.client/cancel-mkt-data! (fn [_ req-id]
                                                 (swap! cancel-calls conj req-id)
                                                 true)]
        (let [result-ch (market-data/market-data-snapshot! conn "AAPL" {:timeout-ms 2000})]
          (async/>!! events-ch {:type :ib/error
                                :request-id @rid
                                :message "Requested market data requires additional subscription for API. Delayed market data is available."
                                :code 10167})
          (async/>!! events-ch {:type :ib/tick-price
                                :request-id @rid
                                :field-key :last
                                :price 179.5})
          (async/>!! events-ch {:type :ib/tick-snapshot-end
                                :request-id @rid})
          (let [result (async/<!! result-ch)]
            (is (= [4] @market-data-type-calls))
            (is (= {:req-id @rid
                    :symbol "AAPL"
                    :con-id nil
                    :sec-type "STK"
                    :exchange "SMART"
                    :primary-exch nil
                    :currency "USD"
                    :snapshot true}
                   @request-args))
            (is (= {:ok true
                    :request-id @rid
                    :req-id @rid
                    :symbol "AAPL"
                    :ticks {:last 179.5}}
                   (select-keys result [:ok :request-id :req-id :symbol :ticks])))
            (is (= [@rid] @cancel-calls))
            (is (true? @subscribe-called?))
            (is (true? @unsubscribe-called?))))))))

(deftest market-data-snapshot-fatal-error-test
  (testing "snapshot still fails fast for non-delayed IB errors"
    (let [events-ch (async/chan 8)
          cancel-calls (atom [])
          conn {}
          rid (atom nil)]
      (with-redefs [ib.market-data/next-req-id! (fn []
                                                  (reset! rid 12346)
                                                  @rid)
                    ib.client/subscribe-events! (fn [_ _] events-ch)
                    ib.client/unsubscribe-events! (fn [_ ch] ch)
                    ib.client/req-market-data-type! (fn [_ _] true)
                    ib.client/req-mkt-data! (fn [_ _] true)
                    ib.client/cancel-mkt-data! (fn [_ req-id]
                                                 (swap! cancel-calls conj req-id)
                                                 true)]
        (let [result-ch (market-data/market-data-snapshot! conn "AAPL" {:timeout-ms 2000})]
          (async/>!! events-ch {:type :ib/error
                                :request-id @rid
                                :message "No market data permissions for NYSE"
                                :code 200})
          (let [result (async/<!! result-ch)]
            (is (= {:ok false
                    :error :ib-error
                    :request-id @rid
                    :req-id @rid
                    :symbol "AAPL"
                    :message "No market data permissions for NYSE"
                    :code 200}
                   (select-keys result [:ok :error :request-id :req-id :symbol :message :code])))
            (is (= [@rid] @cancel-calls))))))))

(deftest contract-details-compat-wrapper-test
  (testing "deprecated market-data wrapper delegates to canonical contract API"
    (let [result-ch (async/chan 1)]
      (async/>!! result-ch {:ok true
                            :request-id 7001
                            :req-id 7001
                            :contracts [{:contract {:conId 1 :symbol "AAPL"}}]
                            :ts 111})
      (async/close! result-ch)
      (with-redefs [ib.contract/contract-details-snapshot!
                    (fn [_ contract-opts opts]
                      (is (= {:symbol "AAPL"
                              :sec-type "STK"
                              :exchange "SMART"
                              :currency "USD"}
                             contract-opts))
                      (is (= {:timeout-ms 8000} opts))
                      result-ch)]
        (let [result (async/<!! (market-data/contract-details-snapshot! {} "AAPL"))]
          (is (= {:ok true
                  :symbol "AAPL"
                  :request-id 7001
                  :req-id 7001
                  :details {:contract {:conId 1 :symbol "AAPL"}}
                  :ts 111}
                 result)))))))
