# Laboratory Work 3: A Local Network Chat

**Network Programming** · Macari Chiril-Adam, Carazanu Augustin · FAF-243

A chat for the local network built directly on UDP sockets (`java.net.DatagramSocket`). There is **no
server**: every running copy is a peer that sends and receives.

- **General channel:** one datagram for everybody, to the multicast group `239.255.42.99` (or, with
  `--broadcast`, to the broadcast address of the network).
- **Who is online:** found without a server (JOIN, then answers within a second); a heartbeat every 5 s; a peer
  not heard from for 15 s is gone; names are unique.
- **Private messages:** one datagram to the receiver's own endpoint only.
- **Encryption:** every private message is encrypted with AES-256-GCM, using a key derived from a shared
  passphrase with PBKDF2.
- **Log file:** every peer writes `chat-<name>.log`, one line per datagram sent, received or dropped.

## Requirements

JDK 17 or newer. On Windows the scripts find the JDK by themselves.

## Build

```
javac -d out src/*.java
```
On Windows: `powershell -ExecutionPolicy Bypass -File scripts\build.ps1`

## Run

```
java -cp out Chat <name> <port> <passphrase> [--broadcast] [--iface <ip>]
```

| Example | |
|---|---|
| `java -cp out Chat Ana 6000 lab3-secret` | Ana joins the chat on port 6000 (multicast) |
| `java -cp out Chat Ion 6000 lab3-secret --broadcast` | the general channel by broadcast instead |
| `java -cp out Chat Ana 6000 lab3-secret --iface 192.168.8.115` | use the network card with this IP |

On Windows: `powershell -ExecutionPolicy Bypass -File scripts\chat.ps1 -Name Ana` (port 6000, passphrase
`lab3-secret`; options `-Port`, `-Passphrase`, `-Broadcast`, `-Iface`).

**All peers of one chat must use the same port, the same passphrase and the same mode** (all multicast, or all
`--broadcast`). Several peers can run on the same computer: open one terminal per peer.

| Typed | Effect |
|---|---|
| `hello everyone` | message to the general channel |
| `/msg Ion see you at five` | private, encrypted message to Ion only |
| `/users` | who is online, with their endpoints |
| `/quit` | leave the chat |

```
[general] Ana: hello everyone
[private] Ion: see you at five
* Maria joined the chat
* Ion left the chat
```

## Two computers

1. Both on the **same network**. Many Wi-Fi networks block broadcast and multicast; use a **phone hotspot**.
2. **Windows, once:** let UDP in for Java. Either click *Allow access* when Windows asks, or run in an
   **administrator** PowerShell: `powershell -ExecutionPolicy Bypass -File scripts\firewall.ps1`
3. Start the peers. Each prints the network card it uses. If it picked the wrong card (for example a
   VirtualBox or WSL one), give the right IP with `--iface` / `-Iface`.
4. If messages do not cross between the computers in multicast mode, start **all** peers with
   `--broadcast` (or the other way round).

## Protocol

Every datagram is one line of UTF-8 text: `LANCHAT1|TYPE|senderId|senderName|seq|payload`.

| Type | Sent to | Meaning |
|---|---|---|
| `JOIN` | everybody | "I want to join with this name" |
| `HERE` | the joiner | answer of every online peer to JOIN |
| `TAKEN` | the joiner | "this name is mine": the joiner does not join |
| `ONLINE` | everybody | "I have joined" (1 s after JOIN, if nobody answered TAKEN) |
| `ALIVE` | everybody | heartbeat every 5 s |
| `LEAVE` | everybody | `/quit` |
| `MSG` | everybody | general channel; payload = the text (at most 500 characters) |
| `PRIV` | one peer | private; payload = `receiverName:base64(nonce + ciphertext + tag)` |

A datagram that does not follow this format, or is longer than 4096 bytes, or a private message that cannot be
decrypted, is dropped and logged. The program never stops because of it.

## How it works

| File | Role |
|---|---|
| `Chat.java` | the peer: commands, joining, heartbeat, timeouts, showing messages |
| `Net.java` | the two sockets and the choice of the network card |
| `Message.java` | the datagram format and its validation |
| `Crypto.java` | PBKDF2 key derivation and AES-GCM encryption |
| `ChatLog.java` | the log file |

**Two sockets per peer.** The *group socket* is bound to `0.0.0.0:<port>` with `SO_REUSEADDR` (and
`SO_REUSEPORT` on macOS/Linux), so all peers on one computer can share the chat port. Broadcast and multicast
datagrams are delivered to every socket bound to it. The *private socket* is bound to `0.0.0.0` on a free port
chosen by the system, so it is unique to the peer. Everything is sent from it, so its endpoint is the peer's
address for answers and private messages. A unicast datagram sent to the shared chat port would reach only one
of the peers that share it.

**Own messages.** Every run has a random 16-hex-digit id. Datagrams that come back with our own id are dropped
(the author shows its own message once, when sending). Duplicates, recognised by (id, seq), are dropped too.

**Encryption.** The key is PBKDF2-HMAC-SHA256 of the passphrase, with 200 000 iterations and the salt
`lan-chat/<port>`. Each message uses AES-256-GCM with a fresh random 12-byte nonce from `SecureRandom`. The
sender's id and name and the receiver's name are authenticated as associated data. A changed or redirected
message fails the tag check and is dropped.

## Tested

On one computer with 3 to 6 peers, in multicast and in broadcast mode:
- joins, leaves and `/users`;
- a duplicate name refused; two peers with the same name at the same moment (exactly one joins);
- a killed peer removed after 15 s;
- a private message received only by its receiver (not by the other peers on the same computer);
- a private message to a name that is not online;
- a peer with another passphrase (its private messages are dropped);
- a flipped ciphertext byte and a redirected message (rejected);
- 501 typed characters (refused);
- random bytes, a foreign datagram, 5000 bytes, an unknown type, a bad id, name or sequence number, an empty
  datagram (all dropped and logged).
