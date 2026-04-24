(ns ib.market-data
  "Market data snapshot API built on top of the event stream.

  Uses market data type 4 (delayed-frozen) by default so no paid
  market data subscription is required for testing."
  (:require [clojure.core.async :as async]
            [ib.client :as client]
            [ib.contract :as contract]
            [ib.events :as events]
            [clojure.string :as str]))

(def ^:private req-id-counter (atom 800000))

(defn- next-req-id! []
  (swap! req-id-counter inc))

(defn- delayed-data-notice?
  "True when an IB error event is only the delayed-data-available notice.

  IB can emit this after `reqMktDataType(4)` when live data is unavailable,
  but delayed snapshot ticks may still follow."
  [{:keys [message]}]
  (boolean
   (when (string? message)
     (str/includes? (str/lower-case message) "delayed market data is available"))))

(defn market-data-snapshot!
  "Request a single market data snapshot for one contract.

  Sets market data type to 4 (delayed-frozen) before the request so
  it works without a live data subscription.

  Options:
  - `:con-id`      IB contract id (preferred; use instead of symbol when known)
  - `:sec-type`    default \"STK\"
  - `:exchange`    default \"SMART\"
  - `:primary-exch` primary exchange for SMART routing (e.g. \"NYSE\")
  - `:currency`    default \"USD\"
  - `:timeout-ms`  default 8000

  Returns a channel delivering one map:
  - success: `{:ok true  :request-id ... :symbol ... :ticks {:bid ... :ask ... :last ...}}`
  - error:   `{:ok false :error :timeout/:ib-error/:stream-closed ... }`"
  ([conn symbol]
   (market-data-snapshot! conn symbol {}))
  ([conn symbol {:keys [con-id sec-type exchange primary-exch currency timeout-ms]
                 :or {sec-type "STK" exchange "SMART" currency "USD" timeout-ms 8000}}]
   (let [rid        (next-req-id!)
         sub-ch     (client/subscribe-events! conn {:buffer-size 64})
         out        (async/chan 1)
         timeout-ch (async/timeout timeout-ms)]
     (try
       (client/req-market-data-type! conn 4)
       (catch Throwable _ nil))
     (let [req-err (try
                     (client/req-mkt-data! conn {:req-id       rid
                                                 :symbol       symbol
                                                 :con-id       con-id
                                                 :sec-type     sec-type
                                                 :exchange     exchange
                                                 :primary-exch primary-exch
                                                 :currency     currency
                                                 :snapshot     true})
                     nil
                     (catch Throwable t t))]
       (if req-err
         (do
           (async/put! out {:ok false :error :request-failed
                            :request-id rid
                            :req-id rid
                            :symbol symbol :message (.getMessage req-err)
                            :ts (events/now-ms)})
           (async/close! out)
           (client/unsubscribe-events! conn sub-ch)
           (async/close! sub-ch))
         (async/go-loop [ticks {}]
           (let [[val port] (async/alts! [sub-ch timeout-ch])
                 done-result
                 (cond
                   (= port timeout-ch)
                   (do (client/cancel-mkt-data! conn rid)
                       (if (seq ticks)
                         {:ok true :request-id rid :req-id rid :symbol symbol :ticks ticks
                          :ts (events/now-ms)}
                         {:ok false :error :timeout :symbol symbol
                          :request-id rid :req-id rid
                          :message "No tick data received before timeout"
                          :ts (events/now-ms)}))

                   (nil? val)
                   {:ok false :error :stream-closed :symbol symbol
                    :request-id rid :req-id rid
                    :ts (events/now-ms)}

                   (and (= :ib/tick-snapshot-end (:type val))
                        (= rid (:request-id val)))
                   ;; IB auto-cancels snapshot subscriptions after tickSnapshotEnd,
                   ;; so cancelMktData here is redundant and causes a harmless
                   ;; "Can't find EId" warning. Swallow the error.
                   (do (try (client/cancel-mkt-data! conn rid) (catch Throwable _ nil))
                       {:ok true :request-id rid :req-id rid :symbol symbol :ticks ticks
                        :ts (events/now-ms)})

                   (and (= :ib/error (:type val))
                        (= rid (:request-id val)))
                   (when-not (delayed-data-notice? val)
                     (client/cancel-mkt-data! conn rid)
                     {:ok false :error :ib-error :symbol symbol
                      :request-id rid :req-id rid
                      :message (:message val) :code (:code val)
                      :ts (events/now-ms)})

                   :else nil)]
             (if done-result
               (do
                 (async/>! out done-result)
                 (async/close! out)
                 (client/unsubscribe-events! conn sub-ch)
                 (async/close! sub-ch))
               (let [new-ticks
                     (if (and (= :ib/tick-price (:type val))
                              (= rid (:request-id val))
                              (:field-key val))
                       (assoc ticks (:field-key val) (:price val))
                       ticks)]
                 (recur new-ticks)))))))
     out)))

(defn ^:deprecated contract-details-snapshot!
  "Deprecated compatibility wrapper around `ib.contract/contract-details-snapshot!`.

  This preserves the old single-symbol calling convention and legacy result
  shape during the Phase 2 transition. New code should call
  `ib.contract/contract-details-snapshot!` directly.

  Options:
  - `:sec-type`   default `\"STK\"`
  - `:exchange`   default `\"SMART\"`
  - `:currency`   default `\"USD\"`
  - `:timeout-ms` default 8000

  Returns a channel delivering one map:
  - success: `{:ok true  :symbol ... :details {:con-id ... :primary-exch ... :exchange ... ...}}`
  - error:   `{:ok false :error :timeout/:ib-error/:no-results/:stream-closed ... }`"
  ([conn symbol]
   (contract-details-snapshot! conn symbol {}))
  ([conn symbol {:keys [sec-type exchange currency timeout-ms]
                 :or {sec-type "STK" exchange "SMART" currency "USD" timeout-ms 8000}}]
   (let [result-ch (contract/contract-details-snapshot!
                    conn
                    {:symbol symbol
                     :sec-type sec-type
                     :exchange exchange
                     :currency currency}
                    {:timeout-ms timeout-ms})
         out (async/chan 1)]
     (async/go
       (when-let [result (async/<! result-ch)]
         (async/>! out
                   (let [contracts (:contracts result)]
                     (cond
                       (and (:ok result) (seq contracts))
                       {:ok true
                        :symbol symbol
                        :request-id (:request-id result)
                        :req-id (:req-id result)
                        :details (first contracts)
                        :ts (:ts result)}

                       (:ok result)
                       {:ok false
                        :error :no-results
                        :symbol symbol
                        :request-id (:request-id result)
                        :req-id (:req-id result)
                        :message "contractDetailsEnd received with no prior results"
                        :ts (:ts result)}

                       :else
                       (cond-> {:ok false
                                :error (:error result)
                                :symbol symbol
                                :request-id (:request-id result)
                                :req-id (:req-id result)
                                :message (:message result)
                                :ts (:ts result)}
                         (= :ib-error (:error result))
                         (assoc :code (get-in result [:ib-error :code])
                                :message (get-in result [:ib-error :message]))

                         (and (= :event-stream-closed (:error result))
                              (nil? (:message result)))
                         (assoc :message "Event stream closed before contract details completed")

                         (and (= :timeout (:error result))
                              (nil? (:message result)))
                         (assoc :message "No contract details received before timeout"))))))
       (async/close! out))
     out)))
