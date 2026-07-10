# Agents.md — codebase orientation for AI agents (and humans)

This file explains how this repository is structured, where the code came from, and the
rules to follow when extending it.

## What this project is

A standalone Java library (`lib`) exposing a programmatic WebRTC SFU stack, produced by
**porting** jitsi-videobridge's pipeline to Java — not by re-designing it. A demo
application (`testapp`) exercises it end-to-end against real browsers.

Current state: the **data-channel vertical slice** works end-to-end (ICE, DTLS, SCTP,
data channels, verified with Playwright + Chromium). The RTP/SRTP media path (Transceiver,
incoming/outgoing pipelines, codec parsing, simulcast/SVC, BWE) is the next porting phase;
its foundations (`org.jitsi.rtp`, the `org.jitsi.nlj` node framework, SRTP transformers)
are already ported.

## Layout

```
lib/src/main/java/
  org/jitsi/rtp/            ← ported packet model (upstream rtp/, Kotlin→Java)
  org/jitsi/nlj/            ← ported pipeline core, dtls/, srtp/ (upstream jitsi-media-transform/)
  org/jitsi/videobridge/    ← ported transports + data channels (upstream jvb/)
    transport/              ← NEW plain DTOs (IceCandidate, DtlsFingerprint,
                              TransportDescription) replacing XMPP/Jingle extensions
    transport/ice|dtls/     ← IceTransport, DtlsTransport
    dcsctp/, sctp/          ← DcSctpTransport/Handler/BaseCallbacks, SctpConfig
    datachannel/            ← copied near-verbatim (was already Java upstream)
    ice/, util/             ← Harvesters/IceConfig/TransportUtils; ByteBufferPool/TaskPools
  com/igrium/sfu/           ← NEW public API (SfuPeerConnection & friends)
  com/igrium/sfu/sdp/       ← NEW optional SDP glue (core stays SDP-free)
testapp/                    ← demo SFU + browser client (web/index.html)
reference/jitsi-videobridge ← upstream source (READ-ONLY reference for porting)
DECISIONS.md                ← decision log + porting resume state
```

## Workflow rules

- **Commit and push after every completed feature or step** — at minimum once per work
  session, ideally more. A "step" is anything that compiles and stands on its own (one
  ported layer, one bug fix verified, one doc update). Push to the designated feature
  branch with `git push -u origin <branch>`. Don't let finished work sit uncommitted:
  the dev sandbox is ephemeral.

## The porting rules (do not break these)

1. **Port, don't redesign.** Keep upstream package names, class names, method names,
   field names and control flow so files stay diffable against
   `reference/jitsi-videobridge`. Rename/restructure only where a user-facing jitsi
   feature is being converted to a library-facing one.
2. **Never re-implement a published dependency.** `ice4j`, `jitsi-utils`, `jitsi-srtp`,
   `jitsi-dcsctp`, BouncyCastle are used as artifacts. Only `jitsi-videobridge` itself is
   ported into this repo. `jitsi-utils` is a dependency, not a port.
3. **Java, not Kotlin.** Upstream Kotlin is translated (see the conventions below).
   Upstream Java files are copied verbatim unless they touch stripped features.
4. **Strip, and only strip:** conference/room/endpoint semantics, XMPP/COLIBRI, REST,
   health checks, metrics/stats-reporting infra, HOCON/metaconfig deployment config.
   Config values become plain Java config classes holding the same upstream defaults.
5. **Preserve Apache-2.0 headers** on ported files; new files get an Apache-2.0 header.
6. **Mark library-facing additions** to ported files with a "(Library addition …)"
   comment (see `DataChannel.getSid/getLabel/sendBinary`, `SctpConfig.setMaxChannels`).

## Kotlin → Java translation conventions used throughout

- `companion object` → statics; `by lazy` → static holder / null-checked getter.
- `data class` → plain class (+`equals`/`hashCode`/`toString` only where used).
- Nullable `T?` + `?.let/?:` → null checks; `@JvmField` vals → public fields.
- Extension functions / top-level functions → static methods on the class that uses them
  (file-level helpers of `X.kt` end up as private statics in `X.java`).
- `logger.cdebug { }` → `logger.debug(() -> ...)`;
  `createChildLogger(parent)` → `parent.createChildLogger(getClass().getName())`.
- Kotlin unsigned/implicit widening → explicit masking (`& 0xFF`, `& 0xFFFFFFFFL`).
- Default parameters → overloads. Checked exceptions absent in Kotlin → wrap in
  `RuntimeException` with a comment.
- **Static-init ordering matters in Java**: a `static final X config = new X()` singleton
  must be declared *after* any static fields its constructor reads (this was a real bug in
  `DtlsConfig`).

## Hard-won runtime knowledge (don't rediscover these)

- **ice4j push API**: incoming data on the single-port harvester only reaches
  `Component.setBufferCallback` when `AbstractUdpListener.USE_PUSH_API = true`. Upstream
  sets it in `Main.kt` (stripped) — this library sets it in `IceTransport`'s static
  initializer, together with the `ice4j.harvest.udp.use-dynamic-ports=false` and
  `ice4j.harvest.use-link-local-addresses=false` system properties (upstream ships these
  in `jvb/src/main/resources/application.conf`). Without the first two, DTLS handshakes
  stall: dynamic-port host candidate sockets have no reader.
- **Browser `.local` (mDNS) candidates** are ignored (`resolveRemoteCandidates=false`,
  as upstream); connectivity still works because the browser's checks against our
  candidates create peer-reflexive candidates — the standard JVB pattern.
- **DTLS roles**: `DtlsTransport.setSetupAttribute` takes the *remote* setup. When we
  answer an `actpass` offer we choose active/client, i.e. pass `"passive"` (the remote
  will be the server). The DTLS **client** initiates the SCTP association (`connect()`);
  the server waits (mirrors upstream `Relay`).
- **Data channel sids** (RFC 8832): DTLS client uses even stream ids, server odd.
- **DCEP deadlock**: never process inbound SCTP messages inline in
  `OnMessageReceived` — it runs holding the SCTP socket lock. They go through
  `incomingDataChannelMessagesQueue` (a `PacketInfoQueue`), exactly like upstream
  `Endpoint`.
- Build with the system gradle (`/opt/gradle/bin/gradle` in the dev sandbox, or any
  Gradle 8.14+/JDK 21); the checked-in wrapper pins a Gradle version that may not be
  available offline.

## Verifying changes

`testapp` + Playwright is the end-to-end harness:

```sh
gradle :testapp:installDist
testapp/build/install/testapp/bin/testapp &   # http://localhost:8080
# then drive two browser contexts at / (see e2e notes in DECISIONS.md):
# both must reach ICE connected + data channel open, and messages sent from
# each must arrive at the other via the SFU. /debug shows transport counters
# (num_packets_received == 0 on ice_transport means the push API is broken).
```

## What's next (media phase)

Port order (see `DECISIONS.md` §7): nlj media-path nodes (`RtpReceiver/Sender`,
incoming/outgoing chains, `Transceiver`), rtcp/codec/BWE subtrees, then
`cc/` bitrate control, then extend `com.igrium.sfu` with `MediaTrack`
(decrypted-RTP forwarding via `onRtpPacket`, host injection via `sendRtp`) and the
media-related observer callbacks. Wire `setSrtpInformation` where
`SfuPeerConnection.setupDtlsTransport` leaves a TODO, and route non-DTLS ICE traffic
(currently dropped) into the transceiver.
