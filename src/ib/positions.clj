(ns ib.positions
  "Higher-level API for position snapshots based on event streams."
  (:require [clojure.core.async :as async]
            [ib.client :as client]
            [ib.events :as events]))

(def default-timeout-ms
  "Default timeout for position snapshots."
  5000)

(defn positions-snapshot-from-events!
  "Collect all `:ib/position` events until `:ib/position-end` or timeout.

  Returns a channel delivering either:
  - vector of normalized position events
  - error map (`:type :ib/error`) on timeout / closed stream"
  ([events-ch]
   (positions-snapshot-from-events! events-ch {}))
  ([events-ch {:keys [timeout-ms]
               :or {timeout-ms default-timeout-ms}}]
   (let [out (async/chan 1)
         timeout-ch (async/timeout timeout-ms)]
     (async/go-loop [positions []]
       (let [[value port] (async/alts! [events-ch timeout-ch])]
         (cond
           (= port timeout-ch)
           (do
             (async/>! out {:type :ib/error
                            :ts (events/now-ms)
                            :reason :timeout
                            :message "Timed out while waiting for :ib/position-end"
                            :timeout-ms timeout-ms})
             (async/close! out))

           (nil? value)
           (do
             (async/>! out {:type :ib/error
                            :ts (events/now-ms)
                            :reason :event-stream-closed
                            :message "Event stream closed before :ib/position-end"})
             (async/close! out))

           (= :ib/position (:type value))
           (recur (conj positions value))

           (= :ib/position-end (:type value))
           (do
             (async/>! out positions)
             (async/close! out))

           :else
           (recur positions))))
     out)))

(defn positions-snapshot!
  "Request positions and return a channel with snapshot result.

  Result is either vector of position events or an error map.

  Options:
  - `:timeout-ms` (default 5000)
  - `:tap-buffer-size` (default 256)"
  ([conn]
   (positions-snapshot! conn {}))
  ([conn {:keys [timeout-ms tap-buffer-size]
          :or {timeout-ms default-timeout-ms
               tap-buffer-size 256}}]
   (let [sub-ch (client/subscribe-events! conn {:buffer-size tap-buffer-size})
         snapshot-ch (positions-snapshot-from-events! sub-ch {:timeout-ms timeout-ms})
         out (async/chan 1)]
     (try
       (client/req-positions! conn)
       (catch Throwable t
         (async/put! out {:type :ib/error
                          :ts (events/now-ms)
                          :reason :request-failed
                          :message (.getMessage t)
                          :raw t})
         (client/unsubscribe-events! conn sub-ch)
         (async/close! sub-ch)
         (async/close! out)))
     (async/go
       (when-let [result (async/<! snapshot-ch)]
         (async/>! out result))
       (client/unsubscribe-events! conn sub-ch)
       (async/close! sub-ch)
       (async/close! out))
     out)))
