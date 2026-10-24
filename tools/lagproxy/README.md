# lagproxy

A small UDP proxy that puts a bad network between real JXPilot clients and a real server, so you can
play at 200 ms RTT with jitter and loss on one machine or a LAN. It runs the same link emulator as the
headless latency harness (`common.net.link.LinkEmulator`, see `docs/latency-measurements.md`), in
real time. It needs no root and works on macOS, Linux and Windows.

```
client(s) --> lagproxy (listen port) --[uplink impairments]--> server
client(s) <-- lagproxy <--[downlink impairments]-------------- server
```

Every client gets its own upstream socket, so the server sees one peer per client. They all come
from the proxy's address, though, and the server allows only 2 clients per address by default.
**Run the server with `-maxClientsPerIP 0`** (no limit) when more than two players go through one
proxy.

## Build and run

From the repository root (the proxy is a module of the main build):

```
MAVEN_OPTS= mvn -q -DskipTests package -pl tools/lagproxy -am
java -cp tools/lagproxy/target/classes:common/target/classes:common/target/test-classes \
  com.fulcrologic.jxpilot.lagproxy.LagProxy --listen 15400 --server 127.0.0.1:15345 --rtt 200 --jitter 15 --bursty 2%,3
```

Then start the server as usual (with `-maxClientsPerIP 0`), and point clients at the proxy:
`--connect proxyhost:15400`.

The proxy prints its settings, each new client, and every `--stats` seconds the counters of both
directions (sent, delivered, lost, queue drops, duplicates, reordered, spikes, in flight). Stop it
with Ctrl-C.

## Options

Every impairment flag applies to both directions. Prefix it with `up-` (client to server) or `down-`
(server to client) to set one direction, for example `--down-loss 2%`. Later flags override earlier
ones. Probabilities are fractions (`0.02`) or percentages (`2%`).

| Flag | Meaning |
|---|---|
| `--listen [HOST:]PORT` | Where clients send to (required). A bare port listens on every IPv4 address; port 0 picks a free one (printed at start). |
| `--server HOST:PORT` | The real server (required). `[v6addr]:port` for IPv6. |
| `--rtt MS` | One-way delay of MS/2 each way (no prefix). |
| `--delay MS` | One-way delay. |
| `--jitter MS` | Normal jitter with standard deviation MS (the delay never goes below 0). |
| `--spikes P,MS[,SHAPE[,CAP]]` | A share P of packets gets a Pareto delay spike: scale MS, shape (default 1.5), cap in ms (default none). |
| `--loss P` | Independent loss. |
| `--bursty P,LEN` | Gilbert bursty loss: long-run share P, in bursts of mean length LEN packets. |
| `--reorder P,MS` | A share P of packets is held back by MS, so later ones overtake it. |
| `--dup P` | A share P of packets is delivered twice. |
| `--rate BYTES[,QUEUE_MS]` | Bottleneck rate in bytes per second (28 bytes of IP+UDP header counted per packet); with QUEUE_MS, packets that would queue longer are dropped. |
| `--no-fifo` | Let jitter reorder packets. By default a late packet holds back the ones behind it, as on a real path. |
| `--seed N` | Seed of both directions' random streams (default 1). |
| `--stats S` | Print counters every S seconds (default 10, 0 = never). |
| `--idle S` | Forget a client silent for S seconds (default 60). |

Delays are timed with 1 ms resolution (the proxy sleeps in `select`), so expect up to about 1 ms of
extra delay per direction, plus whatever the OS scheduler adds.

## Alternatives

- macOS: `dnctl` with `pfctl` (dummynet).
- Linux: `tc qdisc add dev lo root netem delay 100ms 15ms distribution normal loss gemodel 1% 30%`.

Both need root and shape all traffic on the interface. The proxy only shapes the game.
