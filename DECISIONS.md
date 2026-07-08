# vibe-sfu — Implementation Plan & Decision Log

Port of the `jitsi-videobridge` media pipeline into a standalone, programmatic
Java SFU library (`webrtc-java`-style API), with conference/XMPP/REST stripped.

Status: **IN PROGRESS — vertical slice first (data-channel-only end-to-end), then fill out full media pipeline. Full port remains the end goal.**

### Media forwarding requirement (2026-07-08, user)
- Client/host apps **must be able to forward audio/video streams directly without
  decoding/re-encoding**. The media path hands the host the **decrypted RTP packet**
  (payload + headers) and accepts RTP back for the outbound peer — no transcode, no
  depacketize/re-packetize in the common forward case. This is the core SFU contract
  and drives the `MediaTrack.onRtpPacket` / `sendRtp` API design.

### Build environment notes (this sandbox)
- `services.gradle.org` is policy-blocked → the Gradle wrapper (9.3.1) can't self-download.
  Use the system Gradle at `/opt/gradle/bin/gradle` (8.14.3) with JDK 21. The committed
  wrapper is left pointing at 9.3.1 for normal hosts.
- `jitsi-dcsctp` (dcsctp4j) is **not on Maven Central**; only on the Jitsi GitHub-hosted
  maven repo. The `github.com/.../raw/` path is proxy-blocked (403) but
  `raw.githubusercontent.com` serves the same files (200) — `lib/build.gradle` points there.
- ice4j, jitsi-utils, jitsi-srtp resolve from Maven Central directly.

### Approach ruling (2026-07-08)
- Build a **data-channel-only vertical slice** first (ICE → DTLS → SCTP → DataChannel +
  `com.igrium.sfu` API + demo + Playwright-green browser-to-browser), to de-risk the hard
  real-browser DTLS/SCTP integration before porting the large RTP/nlj media pipeline.
- **Full port is still the final deliverable** — audio/video forwarding, simulcast/SVC, BWE
  come after the slice proves the architecture.
- Mechanical Kotlin→Java translation of bulk packages is delegated to **sonnet** subagents;
  wiring and the public API are done directly.


### Resolved rulings (2026-07-08)
- **(A) Config:** Plain Java config objects holding upstream default values, exposed
  programmatically. No `kotlin-stdlib`, no HOCON deployment store.
- **(B) Debug/stats JSON:** **Keep Jackson verbatim** — port `getNodeStats()`/`debugState()`
  as-is using `com.fasterxml.jackson.databind` (add Jackson to `lib/build.gradle`).

---

## 0. Reference survey (what actually exists upstream)

Submodules under `reference/` (Apache-2.0, compatible with our use):

| Module | Lang | Role |
|---|---|---|
| `rtp` | 47 Kotlin, 1 Java | RTP/RTCP packet model (`org.jitsi.rtp`) |
| `jitsi-media-transform` | 168 Kotlin, 18 Java | The node pipeline (`org.jitsi.nlj`): Transceiver, RtpReceiver/Sender, SRTP, DTLS, BWE, codec parsers |
| `jvb` | 106 Kotlin, 55 Java | Endpoint wiring, ICE/DTLS/SCTP transport, data channels, bitrate controller |

Findings that shape the plan:
- **No `kotlinx.coroutines`** anywhere in the pipeline — porting to Java is mechanically feasible (plain `ExecutorService`/threads).
- **156 `by config {}` metaconfig delegates** — config is the biggest cross-cutting concern (see §4).
- **87 files touch Jackson** — almost entirely `getNodeStats()`/debug-JSON. Not core forwarding (see §4).
- DTLS (`nlj/dtls`) wraps **BouncyCastle bctls** directly. SRTP (`nlj/srtp`) wraps **jitsi-srtp**. Data channels (`jvb/datachannel`, already Java) sit on **dcsctp4j**. These map cleanly to real deps.

---

## 1. Module / package layout

```
vibe-sfu/
├── lib/                         (existing Gradle module — the library)
│   └── src/main/java/
│       ├── org/jitsi/rtp/**            ← ported verbatim-as-possible from rtp/
│       ├── org/jitsi/nlj/**            ← ported from jitsi-media-transform/
│       ├── org/jitsi/sfu/transport/**  ← ported transport (ice/dtls/sctp) from jvb, de-conferenced
│       ├── org/jitsi/sfu/datachannel/**← copied Java data-channel stack (jvb/datachannel is already Java)
│       └── com/igrium/sfu/**           ← NEW public API layer (PeerConnection, Track, callbacks)
└── testapp/                     (NEW Gradle module — demo SFU + browser client)
    ├── src/main/java/...        ← minimal demo SFU using com.igrium.sfu
    └── src/main/resources/web/  ← HTML/JS test client
```

Package strategy:
- **Keep upstream package names** for ported pipeline code (`org.jitsi.rtp`, `org.jitsi.nlj`) so it stays diffable against upstream (spec rule 5). Ported transport is de-conferenced, so it moves under `org.jitsi.sfu.transport`.
- **New public API** lives under `com.igrium.sfu` (matches the package already referenced in `lib/build.gradle`).

---

## 2. What is ported vs. mapped-to-dependency

### Ported into this repo (the deliberate jitsi-videobridge port)
- `org.jitsi.rtp` — RTP/RTCP packet model + header extensions (Kotlin → Java).
- `org.jitsi.nlj` node pipeline:
  - `transform/node/**` (Node base, pipeline builder, incoming/outgoing chains)
  - `Transceiver`, `RtpReceiver(Impl)`, `RtpSender(Impl)`, `PacketInfo`, `PacketHandler`
  - `srtp/**` (thin wrappers over jitsi-srtp), `dtls/**` (thin wrappers over BouncyCastle)
  - `rtcp/**`, `codec/**` + `rtp/codec/**` (VP8/VP9/AV1 parsing for simulcast/SVC)
  - `rtp/bandwidthestimation2/**` + `TransportCcEngine` (congestion control / BWE)
  - `stats/**`, `util/**`, `format/**` (payload types), `MediaSourceDesc`/`RtpLayerDesc`
- `jvb` pipeline pieces (de-conferenced):
  - `transport/ice/IceTransport`, `transport/dtls/DtlsTransport`, `dcsctp/DcSctp*`
  - `datachannel/**` + `datachannel/protocol/**` (**already Java → copied near-verbatim**)
  - `cc/**` bitrate controller / bandwidth allocation / `BandwidthProbing`
  - `Endpoint` → refactored into the new `PeerConnection` (conference refs removed)

### Mapped to real published dependencies (NOT reimplemented) — already in `lib/build.gradle`
- ICE/STUN/TURN → `org.jitsi:ice4j`
- RTP/utils/logging helpers → `org.jitsi:jitsi-utils` (**direct dependency, see §3**)
- SRTP/SRTCP crypto → `org.jitsi:jitsi-srtp`
- SCTP → `org.jitsi:jitsi-dcsctp` (dcsctp4j)
- DTLS → `org.bouncycastle:bctls/bcpkix/bcprov`
- SLF4J for logging (spec requirement)

### Explicitly STRIPPED (not ported)
- `videobridge/xmpp`, `colibri2`, `message/**` (XMPP/COLIBRI signalling)
- `videobridge/rest/**`, `health/**`, `metrics/**`, `stats/**` reporting infra, `load_management`, `websocket`
- `Conference`, `AbstractEndpoint` multi-tenant/room semantics, `relay/**`, `AudioSubscription*`, `JvbLastN`, speech-activity — collapsed into per-connection API.

---

## 3. jitsi-utils confirmation

**`jitsi-utils` is depended on directly as a published artifact** (`org.jitsi:jitsi-utils:1.0-150-g4ab9a3b`, already in `lib/build.gradle`), exactly like `ice4j`, `jitsi-srtp`, `dcsctp4j`, and BouncyCastle. It is **not** ported or reimplemented. The submodule under `reference/jitsi-utils` is for reading only.

---

## 4. The two cross-cutting decisions I need your ruling on (spec item 5)

### (A) Config system — `jitsi-metaconfig` + `jicoco-config` (both Kotlin)
The pipeline uses 156 `by config {}` delegates reading HOCON from the Jitsi Meet
deployment. These artifacts are **Kotlin** (would drag in `kotlin-stdlib` + a
HOCON file model that is exactly the "deployment config store" the spec says to strip).

**Recommendation:** Replace metaconfig delegates with plain Java config objects
(e.g. `SrtpConfig`, `DtlsConfig`, `BweConfig`) holding the same upstream default
values as `public static final` fields / builders, exposed programmatically
through the public API. This is part of the deliberate port, keeps the values
identical to upstream, avoids `kotlin-stdlib`, and matches the "programmatic,
no deployment config-store" goal. **No Kotlin runtime dependency.**

Alternative if you prefer: depend on the Kotlin `jitsi-metaconfig` JAR directly
and ship a bundled `reference.conf`. Costs `kotlin-stdlib` on the classpath.

### (B) Jackson (debug/stats JSON)
87 files implement `getNodeStats()`/`debugState()` returning Jackson `ObjectNode`.
This is diagnostics, not forwarding.

**Recommendation:** Keep the node-stats *shape* but return `Map<String,Object>`
instead of Jackson nodes, dropping the Jackson dependency. (Jackson is a normal
Java dep, so keeping it is also fine if you'd rather preserve verbatim — your call.)

> Both recommendations preserve upstream behavior/values while keeping the lib
> pure-Java with no `kotlin-stdlib`. If you'd rather I minimize porting effort by
> pulling the Kotlin config/stats JARs directly, say so and I'll adjust.

No license conflicts found: everything (jitsi-videobridge, jitsi-utils, ice4j,
jitsi-srtp, dcsctp4j) is Apache-2.0; BouncyCastle is MIT-style. All compatible.

---

## 5. Public API surface (`com.igrium.sfu`) — webrtc-java-style

Core classes (Endpoint → PeerConnection refactor):
- **`SfuPeerConnection`** — one peer connection. Configurable per-connection as
  offerer or answerer (`Role.OFFERER` / `Role.ANSWERER`). Wraps ICE+DTLS+SRTP+Transceiver.
- **`SfuConnectionConfig` / builder** — ICE controlling, DTLS role, bundled config objects.
- **Signalling I/O (host brings its own transport):** `setRemoteDescription(...)`,
  `createOffer()/createAnswer()` returning plain transport-parameter objects,
  `addRemoteCandidate(...)`; local candidates surface via `onIceCandidate`.
- **Tracks:**
  - `MediaTrack` (audio/video) — default delivers **decrypted RTP payload, minimal
    processing** via `onRtpPacket`; supports host-injected media via `sendRtp(...)`.
  - `DataChannelTrack` — full WebRTC data-channel parity (string/binary, ordered/unordered,
    reliable/partial-reliability via maxRetransmits/maxPacketLifetime) over dcsctp4j.
  - `addTrack(...)`, `removeTrack(...)` at runtime.

### Callback list (spec item 4) — `SfuPeerConnectionObserver`
Minimum required by spec plus the rest of a complete WebRTC lifecycle:
1. `onConnected()` — ICE connected **and** DTLS established (connection ready).
2. `onRenegotiationNeeded()` — track add/remove requires new offer/answer.
3. `onDisconnected()` — transport lost / peer gone.
4. `onDtlsError(Throwable)` — DTLS handshake/transport failure.
5. `onIceConnectionStateChange(IceState)` — new/checking/connected/failed/closed.
6. `onIceCandidate(Candidate)` — locally gathered candidate for host to trickle.
7. `onDataChannel(DataChannelTrack)` — remote-initiated data channel opened.
8. `onDataChannelOpen(track)` / `onDataChannelClose(track)` — channel state.
9. `onDataChannelMessage(track, StringOrBinary)` — inbound data-channel message.
10. `onTrack(MediaTrack)` — remote audio/video track (new incoming SSRC/media) appeared.
11. `onRtpPacket(MediaTrack, DecryptedRtp)` — decrypted inbound RTP payload (the SFU forward hook).
12. `onBandwidthEstimateChanged(long bps)` — BWE update (for host forwarding decisions).
13. `onClosed()` — connection fully torn down.
14. `onError(Throwable)` — catch-all pipeline error.

---

## 6. Deliverables checklist
- [ ] `lib` builds with only real deps + ported videobridge code (no reimplementations).
- [ ] `testapp` module: demo SFU + HTML/JS client.
- [ ] Playwright-MCP verification: two peers reach `connected`, data-channel round-trip, no DTLS errors.
- [ ] `README.md` (every public class/method) + Javadoc kept in sync.
- [ ] `Agents.md` (structure & reasoning) written after planning.
- [ ] License headers/attribution preserved on all ported files.

## 7. Suggested implementation phasing (bottom-up, each layer compiles)
1. `org.jitsi.rtp` packet model → 2. `nlj/util`+`format`+`stats` → 3. `nlj/transform/node` pipeline core →
4. srtp+dtls wrappers → 5. Transceiver/Receiver/Sender → 6. rtcp+codec+BWE →
7. jvb transport (ice/dtls/sctp) + data channels → 8. cc/bitrate → 9. `com.igrium.sfu` public API →
10. `testapp` demo + client → 11. Playwright verification → 12. README/Javadoc/Agents.md.
```
