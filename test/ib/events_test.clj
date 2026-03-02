(ns ib.events-test
  (:require [clojure.test :refer [deftest is testing]]
            [ib.events :as events]))

(deftest contract-map-normalization-test
  (testing "normalizes maps with canonical keys"
    (is (= {:conId 1234
            :symbol "AAPL"
            :secType "STK"
            :currency "USD"
            :exchange "SMART"}
           (events/contract->map {:conId 1234
                                  :symbol "AAPL"
                                  :secType "STK"
                                  :currency "USD"
                                  :exchange "SMART"}))))

  (testing "normalizes maps with alternative keys"
    (is (= {:conId 5678
            :symbol "MSFT"
            :secType "STK"
            :currency "USD"
            :exchange "NASDAQ"}
           (events/contract->map {"conid" "5678"
                                  "symbol" "MSFT"
                                  "secType" "STK"
                                  "currency" "USD"
                                  "exchange" "NASDAQ"}))))

  (testing "handles nil contract"
    (is (= {:conId nil
            :symbol nil
            :secType nil
            :currency nil
            :exchange nil}
           (events/contract->map nil)))))
