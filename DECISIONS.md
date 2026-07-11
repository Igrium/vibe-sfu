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

### Media path must support BOTH raw-forward and decode/reencode (2026-07-08, user — low priority/later)
- Default & common case: hand the host the **decrypted RTP packet**, forward with no transcode.
- But the structure must ALSO let an application **decode/re-encode** if it wants to. So:
  - Keep upstream's codec parsers/depacketizers in the port (`nlj/codec/**`, `nlj/rtp/codec/**`,
    `transform/node/RtpParser` etc.) rather than stripping them — they provide the decode entry points.
  - Expose the media pipeline as **pluggable** (per the spec's "more exposed/pluggable media pipeline"):
    the host can tap raw RTP OR insert processing nodes / receive parsed frames.
  - `MediaTrack` API surface should offer a raw-RTP channel by default and leave room for a
    parsed-frame / injectable-encoder channel. Design this when building the media layer, not the DC slice.

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

## 5b. Config defaults to preserve in the plain-Java config classes
(From `jitsi-media-transform/.../reference.conf` — the metaconfig delegates become
Java classes holding these as defaults, with programmatic overrides where the API needs them.)
- **DTLS** (`jmt.dtls`): handshake-timeout = 30s; cipher-suites =
  `[TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
  TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256, TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
  TLS_DHE_RSA_WITH_AES_128_GCM_SHA256]`; local-fingerprint-hash-function = sha-256;
  accepted-fingerprint-hash-functions = `[sha-512, sha-384, sha-256, sha-1]`.
- **SRTP** (`jmt.srtp`): max-consecutive-packets-discarded-early = -1; protection-profiles =
  `[SRTP_AEAD_AES_128_GCM, SRTP_AES128_CM_HMAC_SHA1_80]`; factory-class = "OpenSSL".
- **SCTP** (`videobridge.sctp`): enabled = true (slice needs it on); DEFAULT_SCTP_PORT = 5000;
  DEFAULT_MAX_TIMER_DURATION = 3000ms; socket opts: maxRetransmissions/maxInitRetransmits = null (unlimited).

## 5c. RESUME STATE (2026-07-10)

**DONE and pushed:**
- Build baseline green; deps resolve (Central + raw.githubusercontent for dcsctp4j).
- **Task #1 COMPLETE** — `org.jitsi.rtp` fully ported to Java (incl. rtcp/rtcpfb subtree).
  Committed `416cb5a`.
- **Task #2 COMPLETE** — nlj core slice, 77 files under `lib/src/main/java/org/jitsi/nlj/`:
  pipeline core (Node hierarchy, PacketInfo, transform+visitors, stats, format, util),
  `dtls/**`, `srtp/**`. Metaconfig → plain-Java config per §5b. Committed `9204617`.
  Media-path nodes (RtpReceiver/Sender, incoming/outgoing, codec, simulcast) EXCLUDED
  from this slice; `SetMediaSourcesEvent` omitted (noted in `Event.java`).
- **Task #3 COMPLETE** — jvb transport ported, 29 files under
  `lib/src/main/java/org/jitsi/videobridge/`:
  - **Package decision:** kept upstream `org.jitsi.videobridge.*` packages (diffability),
    overriding the earlier idea of moving to `org.jitsi.sfu.transport` (§1).
  - **XMPP/jingle replaced** by plain DTOs in `org.jitsi.videobridge.transport`:
    `IceCandidate` (Comparable: host<srflx<prflx<relay), `DtlsFingerprint`,
    `TransportDescription` (ufrag/password/rtcpMux/candidates/fingerprints).
    `IceTransport.describe()/startConnectivityEstablishment()` and
    `DtlsTransport.describe()` now take `TransportDescription`.
  - `transport/ice/IceTransport,IceStatistics`; `transport/dtls/DtlsTransport`;
    `dcsctp/DcSctpTransport,DcSctpBaseCallbacks(own file),DcSctpHandler`;
    plain-Java `sctp/SctpConfig` (enabled()=true, maxChannels=1) and `ice/IceConfig`
    (public final fields: port=10000, ufragPrefix=null, keepAliveStrategy=selected_only,
    resolveRemoteCandidates=false, nominationStrategy=NominateFirstHostOrReflexiveValid,
    advertisePrivateCandidates=true); `ice/Harvesters` (lazy → getInstance()).
  - Verbatim copies: `ice/TransportUtils`, `util/ByteBufferPool,PartitionedByteBufferPool,
    TaskPools`, `datachannel/**` (13 files; only change: VideobridgeMetrics line removed
    from DataChannelStack).
  - Metrics counters (iceFailed/Succeeded/SucceededRelayed, rejectedDataChannels) stripped.
  - Known deviations: `ByteBufferKt.toHex`→`ByteBufferExtensions.toHex`;
    `UtilKt.getStackTrace`→`Util.getStackTrace`; `generateCandidateId` uses
    `candidate.hashCode()` (upstream Kotlin buildString-receiver quirk not reproduced);
    `BucketStats(thresholds, "", "")` explicit default args.
  - `compileOnly org.jetbrains:annotations:24.1.0` added for verbatim Java files.
  - `gradle :lib:compileJava` BUILD SUCCESSFUL (deprecation notes only).

- **Task #4 COMPLETE** — `com.igrium.sfu` public API (data-channel slice):
  `SfuPeerConnection` (Role.OFFERER/ANSWERER; getLocalDescription/setRemoteDescription/
  addRemoteCandidate/createDataChannel/close; wiring mirrors upstream Endpoint/Relay:
  ICE looksLikeDtls→DtlsTransport; DTLS app data→DcSctpHandler→DcSctpTransport; SCTP out→
  sendDtlsData; DTLS CLIENT initiates sctp connect(); DCEP on sequential PacketInfoQueue),
  `SfuPeerConnectionObserver` (default-method callbacks; media callbacks deferred to media
  phase), `DataChannelTrack`, `DataChannelOptions` (ordered/maxRetransmits/lifetime→DCEP
  channelType per RFC 8832; sid parity: DTLS client=even), `IceConnectionState`.
  Remote "actpass" setup → we answer active (mapped to setSetupAttribute("passive")).
  Support ports added: `videobridge/util/PacketUtils`, `videobridge/TransportConfig`
  (queueSize=1024), `nlj/util/PacketInfoQueue`. Library-facing additions (marked in-source):
  `DataChannel.getSid/getLabel/sendBinary`, `SctpConfig.setMaxChannels` (default stays 1).

- **Task #5 COMPLETE** — testapp demo SFU (JDK HttpServer, POST /offer signalling, /debug
  diagnostics endpoint, HTML/JS client) + **Playwright E2E PASS**: two Chromium contexts,
  ICE connected, DTLS complete (server log clean), data channel open, string round-trip
  through the SFU in both directions. Runtime bugs found & fixed during verification:
  (1) DtlsConfig static-init ordering (singleton constructed before DEFAULT_* fields);
  (2) ice4j needs `AbstractUdpListener.USE_PUSH_API=true` (upstream sets in Main.kt) plus
  `ice4j.harvest.udp.use-dynamic-ports=false` / `use-link-local-addresses=false` (upstream
  application.conf) — now applied in IceTransport's static initializer;
  (3) jackson moved to `api` scope (getDebugState returns ObjectNode).
  Per user request, SDP glue promoted from testapp into the lib as optional
  `com.igrium.sfu.sdp.SdpUtils` (parse/buildOffer/buildAnswer/parseCandidate) — core API
  stays SDP-free, nothing in com.igrium.sfu depends on it.
- README.md (full public API reference) and Agents.md written.

**MEDIA PHASE progress** (roadmap M1-M8 in task list; one commit per step):
- **M1 DONE** (`97abb6f`) — nlj util/rtp-types/MediaSourceDesc/stats foundation (45 files).
  BitrateCalculator window/bucket defaults hardcoded w/ TODO(port) markers.
- **M2 DONE** (`c8af3af`) — codec parsing: VP8/VP9/AV1 packets+parsers, Av1 dependency
  descriptor reader, verbatim DePacketizers (org.jitsi_modified). RtpEncodingDesc gained
  setLayersDirect (library addition replacing Kotlin internal-field access).
- **M3 DONE** (`c7d1360`) — rtcp/ handlers (KeyframeRequester, NackHandler, RembHandler,
  RetransmissionRequester, RtcpEventNotifier, Compound/SingleRtcpParser) + common nodes
  (RtpParser, PacketParser, PacketCacher, PacketLossNode, PacketStreamStatsNode,
  SrtpTransformerNode+subnodes, AudioRedHandler, Pcap writers; pcap4j dep added).
- **M4 DONE** — split into M4a (`52cc011`) + M4b (this commit).
  - M4a: rtp/TransportCcEngine/ClassicTransportCcEngine/LossListener/LossTracker,
    rtp/bandwidthestimation/** (GoogleCc v1), rtp/bandwidthestimation2/** (line-by-line
    libwebrtc GoogCc v2 — ~52 files: delay-based BWE (Trendline/InterArrival), loss-based
    (LossBasedBweV2), SendSideBandwidthEstimation, probe controllers, GoogCcNetworkController
    + GoogCcTransportCcEngine integration), plus verbatim org.jitsi_modified remote bitrate
    estimators. NetworkTypes.kt fanned out to many small value classes. `by config` GoogleCc2
    defaults shipped inline (window=1s/bucket=20ms/ignore=0s).
  - M4b: transform/node/incoming/** (16 files incl. VideoBitrateCalculator bundled with
    BitrateCalculator) + **rtcp/RtcpRrGenerator** (was deferred from M3). Multi-public-class
    Kotlin files → nested static classes (IncomingStatisticsSnapshot/IncomingSsrcStats under
    IncomingStatisticsTracker). observableWhenChanged delegates → field + setEnabled(boolean).
  - Also ported early (needed by DiscardableDiscarder): **rtp/ResumableStreamRewriter**
    (originally slated for M5) — faithful, incl. StreamRewriteHistory on the ported ArrayCache.
  - M1 TODO(port) markers in RtpLayerDesc/PacketStreamStats (BitrateCalculator.createBitrateTracker
    sizing) still stand — BitrateCalculator now exists; wire when convenient.
- **M5 DONE** — transform/node/outgoing/** (9 files: AbsSendTime, HeaderExtEncoder,
  HeaderExtStripper, MidStamper, OutgoingStatisticsTracker, ProbingDataSender,
  RetransmissionSender, SentRtcpStats, TccSeqNumTagger) + **rtcp/RtcpSrUpdater** (was
  deferred from M3). OutgoingStatisticsTracker's 3 top-level Kotlin classes →
  nested static (OutgoingStatisticsSnapshot/OutgoingSsrcStats). observable delegates →
  field + setter. ResumableStreamRewriter was already ported in M4b.
- **M6 DONE** — Transceiver, RtpReceiver(+Impl), RtpSender(+Impl), stats/EndpointConnectionStats
  + TransceiverStats. Multi-public-class Kotlin files split: RtpReceiverEventHandler,
  TransceiverEventHandler, stats/RtpReceiverStats become their own top-level files.
  recv/send queue-size configs hardcoded to upstream default 1024. RtpSenderImpl.probingDataSender
  left non-final (javac definite-assignment vs. a probe-send lambda capturing it before assignment;
  upstream construction order preserved). setSrtpInformation wraps checked GeneralSecurityException
  as RuntimeException (same as SrtpTransformerNode). Also restored RembHandler's
  BandwidthListener/addListener plumbing (an M4b TODO(port) — TransportCcEngine now exists), so
  RtpReceiverImpl forwards REMB-derived bandwidth to its event handler. No M7/M8 seams needed.
- **M7 DONE** — jvb cc/** (bitrate controller + frame projection). Split M7a (`c0bdf74`) + M7b.
  - M7a: cc/ projection base (AdaptiveSourceProjection(+Context), Generic..., RewriteException,
    RtpState) + vp8/ (verbatim Java) + vp9/ + av1/ (Kotlin→Java). Av1DDFrame uses
    HeaderExtension.cloneExtension() (ported rename of upstream .clone()).
  - M7b: cc/allocation/** (BitrateController, BandwidthAllocator, SingleSourceAllocation,
    Prioritize, VideoConstraints, AllocationSettings, Layers, ReceiverConstraintsMap,
    PacketHandler, BandwidthAllocation) + cc/BandwidthProbing + cc/config/* + util/BooleanStateTimeTracker.
  - KEY SEAM PRESERVED: BitrateController<T extends MediaSourceContainer> / BandwidthAllocator<T>
    stay generic over the ported MediaSourceContainer interface — host supplies the source roster
    (no Conference/Endpoint). Stripped (noted inline): jvbLastN/ConferenceSizeLastNLimits caps
    (default no-limit), SsrcLimitConfig.maxVideoSsrcs→inlined 50, BridgeChannelMessage/COLIBRI
    serialization on ReceiverVideoConstraintsMessage (plain data holder), jitsi-metaconfig configs
    →reference.conf defaults (videobridge.cc.*). Multi-public-class Kotlin files split out
    (MediaSourceContainer, BitrateControllerStatusSnapshot).
- **M8 (ONLY remaining step)** — public media API + wiring + e2e. This is integration +
  runtime-debug work (NOT bulk translation); drive it in the orchestrator, verify with the
  running testapp + Playwright. Concrete plan / facts gathered (2026-07-11):
  1. **Create a Transceiver in SfuPeerConnection.** Ctor:
     `Transceiver(String id, ExecutorService recvExec, ExecutorService sendExec,
     ScheduledExecutorService bgExec, DiagnosticContext, Logger, TransceiverEventHandler,
     Clock)` (+ overload w/ `Function<Long,String> getMidBySsrc`). Executors: reuse
     TaskPools (CPU_POOL/IO_POOL/SCHEDULED_POOL — check util/TaskPools for the scheduled one).
     DiagnosticContext: `new DiagnosticContext()` (jitsi-utils). TransceiverEventHandler: new
     library impl bridging to SfuPeerConnectionObserver media callbacks.
  2. **SRTP wiring** — at SfuPeerConnection.java:424 TODO in setupDtlsTransport's DTLS event
     handler, call `transceiver.setSrtpInformation(chosenSrtpProtectionProfile, tlsRole,
     keyingMaterial, /*cryptex=*/false)` (exact 4-arg signature confirmed at Transceiver.java:377;
     it builds SrtpTransformers via SrtpUtil internally). Do it before sctpTransport.connect().
  3. **Inbound media** — at SfuPeerConnection.java:380 (currently drops non-DTLS), replace the
     drop with `transceiver.handleIncomingPacket(new PacketInfo(new UnparsedPacket(buf,off,len)))`
     (parse happens in the receiver pipeline). Guard on transceiver!=null / SRTP set.
  4. **Outbound media** — `transceiver.setOutgoingPacketHandler(pktInfo -> iceTransport.send(...))`
     (packets are already SRTP-encrypted by the sender pipeline's SrtpTransformerNode). And
     `transceiver.setIncomingPacketHandler(...)` to receive decrypted RTP for forwarding.
  5. **Public MediaTrack API** (com.igrium.sfu): a MediaTrack handle exposing onRtpPacket
     forwarding + sendRtp injection; media observer callbacks on SfuPeerConnectionObserver
     (onTrack/onRtp...). Register payload types + header extensions on the transceiver
     (transceiver.addPayloadType / addRtpExtension / addReceiveSsrc / setMediaSources) from the
     host-supplied SDP/track config.
  6. **SdpUtils** — add audio/video m-line helpers (com.igrium.sfu.sdp), loosely coupled per the
     standing "don't over-integrate SDP" guidance.
  7. **testapp** media demo + Playwright fake-media (`--use-fake-device-for-media-stream`)
     verification that RTP actually forwards loopback. Watch the Agents.md runtime gotchas
     (.local/prflx candidates, DTLS role/setup parity, DCEP deadlock, USE_PUSH_API).
  M1 leftover: RtpLayerDesc/PacketStreamStats TODO(port) markers can now call
  BitrateCalculator.createBitrateTracker (M4b) — wire opportunistically.
Workflow: dispatch one sonnet agent per step with facts inlined (see Agents.md "Don't
re-verify verified work"); orchestrator verifies compile, commits, pushes.
Build with system gradle (`/opt/gradle/bin/gradle`, not `./gradlew`). Config = plain Java, defaults §5b.
Use sonnet background agents for bulk translation; one focused agent per layer; instruct "no sub-agents, don't commit".

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

## 8. M8 comprehensive implementation plan (media path + public API + e2e)

**Status:** M1–M7 done — the whole jitsi-videobridge media pipeline is ported and `:lib:compileJava`
is green. M8 exposes that pipeline as a programmatic media API on `SfuPeerConnection`, wires the
transceiver into the existing ICE/DTLS transport, and proves media forwards end-to-end.

### 8.0 Design model (what the library owns vs. what the host owns)
- The library models **one** peer connection (`SfuPeerConnection`). It does NOT do conference
  bookkeeping. The "selective forwarding" is the **host's** job: it owns N `SfuPeerConnection`s and
  copies RTP from one to the others (exactly like `DemoSfu` already relays data-channel messages).
- Therefore the library's media responsibilities are only: (a) hand the host fully-received RTP/RTCP
  from a peer, (b) accept RTP from the host and send it (SRTP-encrypted) to a peer, (c) let the host
  describe SSRCs / payload types / header extensions so the pipeline and SDP line up.
- Keep it webrtc-java-flavored and minimal; mirror the existing `DataChannelTrack` pattern.

### 8.1 Media data flow through the ported pipeline (reference)
- **Inbound:** ICE `incomingDataHandler` → bytes that are NOT DTLS are SRTP → wrap as
  `PacketInfo(new UnparsedPacket(buf,off,len))` → `transceiver.handleIncomingPacket(pi)` →
  `RtpReceiverImpl` pipeline (SRTP-decrypt → parse → stats/… ) → the handler registered via
  `transceiver.setIncomingPacketHandler(PacketHandler)` receives a parsed `RtpPacket`/`RtcpPacket`.
  Library dispatches that to the receive `MediaTrack` / observer.
- **Outbound:** host → `MediaTrack.sendRtp(...)` → `transceiver.sendPacket(PacketInfo)` →
  `RtpSenderImpl` pipeline ( … → SRTP-encrypt) → handler registered via
  `transceiver.setOutgoingPacketHandler(PacketHandler)` → `iceTransport.send(...)`.
- **Config surface on Transceiver:** `addPayloadType(PayloadType)`, `addRtpExtension(RtpExtension)`,
  `addReceiveSsrc(long, MediaType)`, `setLocalSsrc(MediaType, long)`, `setMediaSources(MediaSourceDesc[])`
  (simulcast/quality; a basic single-stream loopback can pass a minimal descriptor),
  `setSrtpInformation(profile, TlsRole, keyingMaterial, cryptex)`.

### 8.2 Phase A — Transceiver plumbing in `SfuPeerConnection` (compiles; no behavior change yet)
Files: `lib/.../com/igrium/sfu/SfuPeerConnection.java`.
1. Add `private volatile Transceiver transceiver;` and create it lazily once SRTP is known (or in the
   ctor — decide during impl; lazy-on-DTLS-complete avoids an idle transceiver). Ctor args:
   `new Transceiver(id, TaskPools.CPU_POOL, TaskPools.CPU_POOL, TaskPools.SCHEDULED_POOL,
   new DiagnosticContext(), logger, eventHandler, Clock.systemUTC())`. (Receiver & sender executors
   can share CPU_POOL as upstream does for the bridge; revisit if starvation shows up.)
2. `TransceiverEventHandler` impl (interface only needs `audioLevelReceived(ssrc,level)` and
   `bandwidthEstimationChanged(Bandwidth)` — both `default`). Provide a small private class; forward
   BWE to a new observer hook if useful, ignore audio level initially.
3. **SRTP:** at the DTLS `eventHandler` (SfuPeerConnection.java:~424 TODO) call
   `transceiver.setSrtpInformation(chosenSrtpProtectionProfile, tlsRole, keyingMaterial, false)`
   BEFORE `sctpTransport.connect()`. Cryptex=false (not negotiated in this slice).
4. **Inbound routing:** at the ICE `incomingDataHandler` non-DTLS branch (SfuPeerConnection.java:~380,
   currently drops) → if `transceiver != null` forward as PacketInfo(UnparsedPacket); else drop.
   Keep returning/reusing buffers per the existing ByteBufferPool discipline (the pipeline copies).
5. `transceiver.setOutgoingPacketHandler(pi -> iceTransport.send(pi.getPacket()... ))` — send the
   already-encrypted bytes; `transceiver.setIncomingPacketHandler(pi -> onReceivedRtp(pi))`.
6. `close()` must `transceiver.stop()`/`teardown()`.
Verification for Phase A: `:lib:compileJava` green; existing data-channel Playwright path still works
(media not yet exercised).

### 8.3 Phase B — public media API (`com.igrium.sfu`)
New/edited files: `MediaTrack.java`, `MediaStreamType`/`MediaKind` enum (audio|video), edits to
`SfuPeerConnectionObserver.java` and `SfuPeerConnection.java`.
- `MediaTrack` (mirror `DataChannelTrack`): fields ssrc, kind, and either a receive-listener
  (`onRtpPacket(Consumer<RtpPacket>)`) or a `sendRtp(RtpPacket)` for a local track. Keep the raw
  `org.jitsi.rtp.rtp.RtpPacket` as the currency (host forwards bytes; no transcoding).
- `SfuPeerConnection`:
  - `MediaTrack addReceiveTrack(MediaKind, long ssrc, List<PayloadType>, List<RtpExtension>)` —
    registers payload types/extensions/receive-ssrc on the transceiver, returns a receive track.
  - `MediaTrack createSendTrack(MediaKind, long ssrc, List<PayloadType>, List<RtpExtension>)` —
    sets local ssrc, returns a track whose `sendRtp` calls `transceiver.sendPacket`.
  - route `onReceivedRtp(PacketInfo)` → the matching receive track's listener (by ssrc), else observer.
- `SfuPeerConnectionObserver`: add `default void onRtpPacketReceived(MediaTrack, RtpPacket)` (or drive
  purely through `MediaTrack.onRtpPacket` — pick one; prefer the track listener, keep observer thin).
- Payload-type construction uses the already-ported `org.jitsi.nlj.format.*` (Vp8/Vp9/Av1/H264/Opus/…)
  + `RtpExtension`/`RtpExtensionType`.

### 8.4 Phase C — SDP helpers (`com.igrium.sfu.sdp.SdpUtils`)
- Extend the existing `SdpUtils` (data-channel/transport only today) with **audio/video m-line**
  generation + parsing, staying LOOSELY coupled (standing rule: "if videobridge didn't have it,
  don't over-integrate"). Parse the browser offer's `m=audio`/`m=video`: payload types (rtpmap/fmtp),
  `a=extmap`, `a=ssrc`, direction, and the transport bits already handled. Emit a matching answer
  m-line advertising the SFU's chosen PT/extensions and (for send) our SSRC.
- This is glue for the testapp; it does not need to be a full SDP stack.

### 8.5 Phase D — testapp media demo
Files: `testapp/.../DemoSfu.java`, `testapp/.../web/index.html`.
- `index.html`: `getUserMedia({audio,video})`, `pc.addTrack(...)`, render remote `pc.ontrack` in a
  `<video>`; still POST offer → `/offer`, apply answer. Keep the data-channel path too.
- `DemoSfu`: on offer, use `SdpUtils` to configure the `SfuPeerConnection` receive tracks; **forward**
  each peer's received RTP to the other connected peers (media analog of the existing
  `relayString`/`relayBinary`) via their send tracks — the smallest real SFU loop.
- Loopback option for a single browser (forward a peer's media back to itself) to make the e2e
  assertion trivial and deterministic.

### 8.6 Phase E — Playwright fake-media e2e verification
- New `scratchpad/e2e-media.js` (Playwright): launch Chromium with
  `--use-fake-device-for-media-stream --use-fake-ui-for-media-stream`, load the testapp, start the
  connection, and assert media actually flows: check `RTCPeerConnection.getStats()` for
  `inbound-rtp.bytesReceived > 0` / `packetsReceived > 0` on the browser's receiving side, and/or a
  server-side counter of forwarded RTP packets exposed on `/debug`. Chromium is preinstalled
  (`/opt/pw-browsers/chromium`, `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`) — do NOT run `playwright install`.
- Watch the known runtime gotchas (see Agents.md): `.local`/prflx ICE candidates, DTLS role/setup
  parity, USE_PUSH_API static block, and buffer-pool discipline on the media hot path.

### 8.7 Order & checkpoints
A → B → C compile-checkpointed and committed together (or A alone first if it's large); D+E form the
verification commit. Commit + push after each green checkpoint. When A–E pass e2e, mark task #13 done,
update this file's §5c (M8 DONE) and the README API docs, and note any new runtime lessons in Agents.md.

