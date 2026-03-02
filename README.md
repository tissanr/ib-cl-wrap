# ib-cl-wrap

Asynchroner Clojure-Wrapper fuer die Interactive Brokers TWS/IB Gateway Java API.

Der Wrapper nutzt `core.async` fuer ein Event-basiertes Modell und vermeidet blockierende Logik in IB-Callback-Threads.

## Setup

1. `ibapi.jar` in `lib/ibapi.jar` ablegen.
2. TWS oder IB Gateway starten.
3. In TWS/Gateway API aktivieren und Socket-Port oeffnen.

Typische Ports:
- TWS Paper: `7497`
- TWS Live: `7496`
- IB Gateway Paper: `4002`
- IB Gateway Live: `4001`

In `deps.edn` ist `lib/ibapi.jar` bereits in `:paths` eingetragen. Wenn die JAR fehlt, werfen `ib.client/connect!` und andere IB-nahe Funktionen eine erklaerende Exception.

## API

### Namespace `ib.client`

- `connect!` - verbindet zu TWS/Gateway und startet den `EReader`-Loop (ohne busy-wait).
- `disconnect!` - trennt Verbindung und schliesst Event-Ressourcen.
- `subscribe-events!` - liefert Subscriber-Channel fuer Events.
- `unsubscribe-events!` - entfernt Subscriber-Channel.
- `req-positions!` - triggert `reqPositions()`.
- `dropped-event-count` - Anzahl nicht enqueueter Events.

### Namespace `ib.positions`

- `positions-snapshot!` - triggert `reqPositions()`, sammelt `:ib/position` bis `:ib/position-end`, liefert Ergebnis auf Channel.
- `positions-snapshot-from-events!` - reine Collector-Funktion fuer simulierte Tests.

### Namespace `ib.events`

- `contract->map` - normalisiert Contract auf stabiles Map-Format.
- `position->event`, `error->event` - Event-Normalisierung.
- `create-event-bus` - bounded Event-Infrastruktur mit Backpressure-Strategie.

## Event-Schema

Minimale Event-Typen:
- `{:type :ib/connected ...}`
- `{:type :ib/error ...}`
- `{:type :ib/position ...}`
- `{:type :ib/position-end ...}`

Contract-Normalisierung (stabil):
- `:conId`
- `:symbol`
- `:secType`
- `:currency`
- `:exchange`

## Overflow-Strategie

Default: `:sliding` auf bounded Channels.

Das bedeutet: Bei vollem Puffer werden aeltere Events verworfen, neuere behalten. Callback-Threads blockieren dadurch nicht.

## Beispiel

```clojure
(require '[clojure.core.async :as async]
         '[ib.client :as ib]
         '[ib.positions :as pos])

(def conn
  (ib/connect! {:host "127.0.0.1"
                :port 7497
                :client-id 42
                :event-buffer-size 2048
                :overflow-strategy :sliding}))

(def events-ch (ib/subscribe-events! conn {:buffer-size 512}))

(async/go-loop []
  (when-let [evt (async/<! events-ch)]
    (println "EVENT" evt)
    (recur)))

(def snapshot-ch (pos/positions-snapshot! conn {:timeout-ms 5000}))

(async/go
  (let [result (async/<! snapshot-ch)]
    (if (= :ib/error (:type result))
      (println "Positions-Fehler:" result)
      (println "Positions-Snapshot:" result))))

;; spaeter
(ib/unsubscribe-events! conn events-ch)
(ib/disconnect! conn)
```

## Tests

Tests laufen ohne echte IB-Verbindung (simulierte Events):

```bash
clj -M:test
```

Getestet wird:
- Contract-Normalisierung
- Snapshot-Collector-Logik
- Timeout-Verhalten

## Hinweise Paper vs Live

- Paper und Live nutzen unterschiedliche Ports/Accounts.
- Verwende unterschiedliche `client-id` pro Client-Verbindung.
- Erst in Paper testen, dann Live aktivieren.
