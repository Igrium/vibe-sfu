# Agents.md — codebase orientation for AI agents (and humans)

This file explains how this repository is structured, where the code came from, and the
rules to follow when extending it.

## What this project is

A standalone Java library (the `vibe-sfu` Gradle module) exposing a programmatic WebRTC SFU
stack, produced by **porting** jitsi-videobridge's pipeline to Java — not by re-designing it.
Two demo applications (`testapp`) exercise it end-to-end against real browsers.

Current state: **complete**. ICE, DTLS, SCTP, data channels *and* the full RTP/SRTP media path
(Transceiver, incoming/outgoing pipelines, codec parsing, simulcast/SVC frame projection, BWE)
are ported and verified with Playwright + Chromium (fake media devices). The public API also
covers SFU-initiated negotiation: `MediaTransceiver` declares `m=` sections before the remote
peer has signalled anything, so the host can build its own offers.

## Layout

```
vibe-sfu/src/main/java/
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
testapp/                    ← two demo SFUs + browser clients:
    DemoSfu / web/index.html      ← browser offers, SFU answers (port 8080)
    ConferenceSfu / web/conference.html
                                  ← SFU offers: transceivers + SFU-created data
                                    channel, dynamic join/leave (port 8081)
reference/jitsi-videobridge ← upstream source (READ-ONLY reference for porting)
DECISIONS.md                ← decision log + porting resume state
```

## Workflow rules

- **Commit and push after every completed feature or step** — at minimum once per work
  session, ideally more. A "step" is anything that compiles and stands on its own (one
  ported layer, one bug fix verified, one doc update). Push to the designated feature
  branch with `git push -u origin <branch>`. Don't let finished work sit uncommitted:
  the dev sandbox is ephemeral.
- **Don't re-verify verified work.** When delegating to a subagent, paste the exact
  signatures/facts it needs into the prompt instead of sending it to re-read
  already-ported files, and tell it to trust them. Subagents compile once at the end at
  most; the orchestrator's own final `gradle :vibe-sfu:compileJava` before committing is the
  verification of record. Reports cover exceptions (deviations, skips), not restatements
  of what went as instructed.

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

`testapp` + Playwright is the end-to-end harness. There are two demos, and **both** must stay
green after a change to the public API or the SDP glue:

```sh
gradle :testapp:installDist
testapp/build/install/testapp/bin/testapp &      # http://localhost:8080 (browser offers)
testapp/build/install/testapp/bin/conference &   # http://localhost:8081 (SFU offers)
```

They share the single ICE UDP port (10000), so **run one at a time**.

- `testapp`: drive two browser contexts at `/`. Both must reach ICE connected + data channel
  open, messages sent from each must arrive at the other, and with
  `--use-fake-device-for-media-stream` each must see `inbound-rtp` audio *and* video with
  `bytesReceived > 0`.
- `conference`: drive three contexts at `/`. Each must reach `testState.dcOpen` (the data
  channel is created by the *server*), end up with `testState.remoteStreams.length == 2`
  without ever offering, and receive media; closing one context must drop exactly that entry
  from the others' `remoteStreams` while media keeps flowing between the survivors.

`/debug` shows transport counters on both (`num_packets_received == 0` on `ice_transport`
means the push API is broken); the conference demo also lists each participant's transceivers.

## Signalling directions the API supports

Both are exercised by a demo; keep both working.

| | `DemoSfu` | `ConferenceSfu` |
|---|---|---|
| Role | `ANSWERER` | `OFFERER` |
| Media declared via | `addReceiveTrack` / `createSendTrack` | `addTransceiver` (`MediaTransceiver`) |
| SDP built with | `SdpUtils.buildAnswer` | `SdpUtils.buildOffer(local, List<MediaSection>)` |
| Data channel | opened by the browser | created by the **SFU**, in the initial offer |
| Renegotiation | needs the browser to re-offer | SFU re-offers over its own data channel |

Things that bite in the offerer direction:

- **Section order is load-bearing.** JSEP forbids reordering or dropping `m=` sections between
  offers, which is why `MediaTransceiver.stop()` keeps the transceiver in `getTransceivers()`
  and its section is re-emitted as rejected (port 0). Reserve the data channel's mid
  (`getDataChannelMid()`) *before* adding transceivers so it stays section 0.
- **Don't re-apply transport parameters on renegotiation.** A re-offer that only changes media
  reuses the established ICE/DTLS transport; `setRemoteDescription` belongs to the initial
  exchange (or an ICE restart) only.
- **The browser attaches its media with `replaceTrack`**, not `addTrack`: the SFU offers the
  uplink as `recvonly`, the browser flips that transceiver to `sendonly` and replaces the track.
  `addTrack` would create sections of its own and force the browser to offer.
- **Extension ids must be unique across all bundled sections**, audio and video alike.
- Because only the SFU offers, **glare is impossible** — worth preserving in any new demo.
