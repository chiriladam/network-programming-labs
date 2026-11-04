# Laboratory Work 2: A Coffee Shop Server

**Network Programming** · Macari Chiril-Adam, Carazanu Augustin · FAF-243

A coffee-shop server built directly on TCP sockets (`java.net.ServerSocket` and `java.net.Socket`, with no
higher-level networking library), an interactive **client / provider** console program, and a **demo** that
starts many clients at once.

- Products: **coffee 4 s**, **tea 3 s**, **croissant 2 s**. At start: 10–50 of each, random, or the saved stock.
- One **worker per logical processor** prepares the orders; waiting orders form a FIFO queue.
- The stock is protected by a lock, so two clients can never both get the last croissant.
- The provider delivers products and is **alerted by the server** when a product falls below 5.
- A client that disappears has all its orders cancelled; its products go back to the stock.
- **Bonus:** the stock is saved to `stock.txt` and kept after a restart; the shop works between two computers.

## Requirements

- JDK 17 or newer (we used OpenJDK 21/25). The scripts find the newest JDK automatically, even if an old
  Java 8 comes first in `PATH`.

## Build

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1
```
or on any OS: `javac -d out src/*.java`

## Run

Each program in its own terminal, from the project folder:

| Role | Windows script | Plain command |
|---|---|---|
| Server | `scripts\server.ps1 -Port 5000` | `java -cp out Server 5000` |
| Client | `scripts\client.ps1 -Server localhost -Port 5000` | `java -cp out Client localhost 5000` |
| Provider | `scripts\provider.ps1 -Server localhost -Port 5000` | `java -cp out Client localhost 5000 provider` |
| Many clients | `scripts\demo.ps1 -Mode many -Clients 15` | `java -cp out Demo localhost 5000 many 15` |
| Last-croissant race | `scripts\demo.ps1 -Mode rush -Clients 10` | `java -cp out Demo localhost 5000 rush 10` |

(Run the scripts as `powershell -ExecutionPolicy Bypass -File scripts\server.ps1 -Port 5000` if script
execution is disabled.)

Server options:

| Option | Meaning |
|---|---|
| `--workers N` | number of workers (default: the number of logical processors) |
| `--stock coffee=10,tea=10,croissant=1` | start with this stock (for demonstrations) |
| `--stock-file stock.txt` | where the stock is saved (default `stock.txt`) |
| `--no-save` | do not load or save the stock |
| `--log server.log` | log file (default `server.log`; the log is also printed) |

## Protocol

Text over TCP, **one message per line, ended by `\n`** (`\r\n` is accepted too). Commands are not
case-sensitive. A message may be at most 1024 bytes.

| Client → server | Answer |
|---|---|
| `HELLO CLIENT` / `HELLO PROVIDER` | `OK you are a client` / `OK you are the provider` (sent automatically by our client program) |
| `MENU` | `MENU coffee 12 in stock (4s), tea 30 in stock (3s), croissant 7 in stock (2s)` |
| `ORDER croissant 2 tea 1 coffee 1` | `ACCEPTED order #5: 1 coffee, 1 tea, 2 croissant, preparation time 11s`, later `READY order #5 (...)` |
| | or `REFUSED not enough croissant: you asked for 3, only 1 left` |
| `DELIVER coffee 20 croissant 10` | provider: `DELIVERED 20 coffee, 10 croissant. MENU ...`; client: `ERROR only the provider can deliver` |
| `HELP` | the list of commands |
| `QUIT` | `BYE`, then the server closes the connection |
| `PING` | `PONG` (heartbeat, sent every 5 s by our client program and hidden from the user) |

Sent by the server on its own: `READY order #N` to the client, `ALERT tea is low: 3 left` to the provider.
Any invalid input gets `ERROR <reason>`: unknown command, missing or wrong parameter, unknown product,
a quantity that is not a positive number, a message that is too long, random bytes.

## How it works

```
                      listening socket (port 5000)
                               │ accept()
          ┌────────────────────┼────────────────────┐
   connection 1          connection 2          provider connection
   reader + writer       reader + writer       reader + writer
          │ ORDER                │ ORDER                 ▲ ALERT
          ▼                      ▼                       │
   ┌───────────────── Shop ──────────────────────────────┴──┐
   │ stock (lock)   ──►  FIFO queue of orders  ──►  N workers │──► READY to the client
   └────────────────────────────────────────────────────────┘
```

| File | Role |
|---|---|
| `Server.java` | `bind`, `listen`, then `accept` forever; starts the workers and the stock saver |
| `ClientSession.java` | one connection: a reader thread (commands) and a writer thread (all outgoing messages) |
| `LineReader.java` | cuts the received bytes into messages at `\n`; handles split and merged messages and too-long ones |
| `Shop.java` | the shared state: stock, queue, provider; check-and-take of the stock in one critical section |
| `Order.java` | one order and its state (WAITING → PREPARING → READY or CANCELLED) |
| `Worker.java` | takes the oldest order, waits its preparation time, sends READY; stops if the order is cancelled |
| `StockStore.java` | bonus: saves the stock to `stock.txt` (write to a temp file, then rename) |
| `Client.java`, `ShopConnection.java` | the console client/provider: one thread for the keyboard, one for the socket, one heartbeat |
| `Demo.java` | many real clients at once: `many` (more clients than workers) and `rush` (last croissant) |

**Shared data and its protection**

| Data | Shared by | Protection |
|---|---|---|
| stock | all client threads, the provider, cancellations | `synchronized (stockLock)`; checking and taking happen in one block |
| order queue | client threads (put), workers (take) | `LinkedBlockingQueue` (thread-safe, FIFO) |
| order state | its worker and its client's thread | `synchronized` methods of `Order` |
| the provider's connection | all client threads (for alerts), the provider thread | `synchronized (providerLock)` |
| each connection's socket output | its reader thread, workers, other clients' threads | only one writer thread writes; the others put lines into its queue |
| a client's list of orders | its reader thread, workers | `ConcurrentHashMap.newKeySet()` |

**When a client disappears**

- It closes the connection, or its process is killed: the operating system sends FIN/RST, `read()` returns
  -1 or throws "Connection reset", and the server notices **at once**.
- Its network is gone: nothing arrives at all. Our client sends `PING` every 5 s, so the server treats a
  connection that is **silent for 30 s** as gone (`setSoTimeout`).
- In every case all its orders are cancelled. A waiting order is skipped by the workers. The worker
  preparing an order is woken by `notifyAll()` and stops. The products go back to the stock.

## Two computers (bonus)

1. Both computers on the same network (for example, the same Wi-Fi).
2. On the server computer, allow the port through the Windows Firewall. Either click **Allow access** when
   Windows asks the first time the server starts, or run in an **administrator** PowerShell:
   `netsh advfirewall firewall add rule name="Coffee shop" dir=in action=allow protocol=TCP localport=5000`
3. Start the server. It prints its addresses, for example `192.168.1.23:5000`.
4. On the other computer: `scripts\client.ps1 -Server 192.168.1.23 -Port 5000`.

## Tested

A test script checked, against a running server: menu, orders, refusals and every error message; a
message split in two receives and two messages in one receive; a 5000-byte message and random bytes;
a second provider; deliveries; alerts at once and alerts missed while the provider was away; cancellation
on a normal close, on a connection reset, and for orders still waiting in the queue; QUIT; the stock file
after `kill -9` and a restart. A stress test with 40 clients ordering and disconnecting at random while the
provider delivered ended with the stock exactly equal to initial + delivered − prepared.
