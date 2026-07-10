# vibe-sfu

A standalone Java library for building WebRTC SFUs (Selective Forwarding Units) on the JVM,
created by porting the media pipeline and transport stack of
[jitsi-videobridge](https://github.com/jitsi/jitsi-videobridge) into a reusable,
programmatic, signalling-agnostic library — in the spirit of
[webrtc-java](https://github.com/devopvoid/webrtc-java), but SFU-oriented: media can be
forwarded between peers **without decoding or re-encoding**.

All conference/room semantics, XMPP/COLIBRI signalling, REST APIs, health checks and stats
reporting of the original videobridge are stripped; what remains is the packet pipeline
(RTP/RTCP model, DTLS, SRTP, SCTP, data channels, and — in later phases — jitter handling,
simulcast/SVC and bandwidth estimation) behind a small public API. The host application
brings its own signalling.

> **Status: data-channel vertical slice.** ICE + DTLS + SCTP + WebRTC data channels work
> end-to-end against browsers (verified with Playwright; see [testapp](#the-testapp-demo)).
> The RTP/SRTP media path (audio/video forwarding, simulcast, BWE) is ported next; the
> packet model (`org.jitsi.rtp`) and pipeline framework (`org.jitsi.nlj`) it needs are
> already in the tree.

## Modules

| Gradle project | What it is |
|---|---|
| `lib` | The library: ported jitsi-videobridge code (`org.jitsi.rtp`, `org.jitsi.nlj`, `org.jitsi.videobridge.*`) + the public API (`com.igrium.sfu`). |
| `testapp` | A runnable demo SFU + browser test client. |

Build with Java 21+:

```sh
gradle :lib:build          # the library
gradle :testapp:installDist # the demo SFU
```

## Quick start

```java
import com.igrium.sfu.*;
import com.igrium.sfu.sdp.SdpUtils;

// 1. Create a connection. We answer an offer made by a browser.
SfuPeerConnection connection = new SfuPeerConnection(
        SfuPeerConnection.Role.ANSWERER,
        new SfuPeerConnectionObserver() {
            @Override public void onConnected() {
                System.out.println("connected");
            }
            @Override public void onDataChannel(DataChannelTrack track) {
                track.sendString("welcome!");
            }
            @Override public void onDataChannelStringMessage(DataChannelTrack track, String msg) {
                System.out.println("got: " + msg);
            }
        });

// 2. Signalling is yours. If your peer speaks SDP (browsers do), the optional
//    SdpUtils helper maps SDP <-> the library's transport-parameter objects:
SdpUtils.ParsedSdp offer = SdpUtils.parse(offerSdpFromBrowser);
connection.setRemoteDescription(offer.transport);
String answerSdp = SdpUtils.buildAnswer(connection.getLocalDescription(), offer);
// ... deliver answerSdp to the browser ...
```

## Public API reference (`com.igrium.sfu`)

### `SfuPeerConnection`

One WebRTC peer connection (ICE + DTLS + SCTP + data channels). `Closeable`.

| Member | Description |
|---|---|
| `enum Role { OFFERER, ANSWERER }` | Which side of signalling this connection is. The offerer is the ICE controlling agent and offers DTLS `actpass`; the answerer takes the DTLS role implied by the remote setup attribute (choosing `active` when offered `actpass`). |
| `SfuPeerConnection(Role, SfuPeerConnectionObserver)` | Create with a random id and default logger. |
| `SfuPeerConnection(String id, Role, SfuPeerConnectionObserver, Logger parentLogger)` | Create with explicit id and logger (jitsi-utils `logging2.Logger`). |
| `String getId()` | The connection's identifier. |
| `Role getRole()` | The signalling role. |
| `boolean isConnected()` | True once ICE is connected **and** the DTLS handshake completed. |
| `TransportDescription getLocalDescription()` | Our ICE ufrag/password/candidates + DTLS fingerprint, for the host to signal to the remote peer. Candidates are gathered up front (no local trickle needed). |
| `void setRemoteDescription(TransportDescription)` | Apply the remote peer's transport parameters and start/refresh ICE connectivity establishment. |
| `void addRemoteCandidate(IceCandidate)` | Add one trickled remote candidate (requires a prior `setRemoteDescription`). |
| `DataChannelTrack createDataChannel(String label)` | Create a reliable/ordered data channel. |
| `DataChannelTrack createDataChannel(String label, DataChannelOptions)` | Create a data channel with explicit options. Opens automatically once the SCTP association is up. |
| `ObjectNode getDebugState()` | JSON diagnostics snapshot of the ICE/DTLS/SCTP transports. |
| `void close()` | Tear everything down; fires `onClosed()`. Idempotent. |

### `SfuPeerConnectionObserver`

All methods have no-op defaults. Callbacks run on internal library threads — don't block.

| Callback | Fired when |
|---|---|
| `onIceConnectionStateChange(IceConnectionState)` | ICE state transitions (`CHECKING`, `CONNECTED`, `FAILED`, `CLOSED`). |
| `onConnected()` | ICE connected **and** DTLS handshake complete — the connection is usable. |
| `onDisconnected()` | Transport lost (ICE failure). |
| `onDataChannel(DataChannelTrack)` | The remote peer opened a data channel (already open and usable). |
| `onDataChannelOpen(DataChannelTrack)` | A locally-created channel finished opening (remote acknowledged it). |
| `onDataChannelStringMessage(DataChannelTrack, String)` | Inbound string message. |
| `onDataChannelBinaryMessage(DataChannelTrack, byte[])` | Inbound binary message. |
| `onClosed()` | The connection is fully torn down. |
| `onError(Throwable)` | Unrecoverable pipeline error (e.g. SCTP abort). |

Media callbacks (remote track appeared, decrypted RTP for forwarding, bandwidth estimate)
are added together with the media pipeline in a later phase.

### `DataChannelTrack`

| Member | Description |
|---|---|
| `String getLabel()` | The channel label. |
| `int getSid()` | The SCTP stream id, or `-1` before the channel is assigned to a stream. |
| `boolean isOpen()` | Whether the channel is open (locally-created channels: remote acknowledged). |
| `SfuPeerConnection getConnection()` | Owning connection. |
| `void sendString(String)` | Send a UTF-8 string message. Throws `IllegalStateException` if not open. |
| `void sendBinary(byte[])` | Send a binary message. Throws `IllegalStateException` if not open. |

### `DataChannelOptions`

Mirrors the browser `RTCDataChannelInit` dictionary; fluent setters.

| Member | Description |
|---|---|
| `ordered(boolean)` / `isOrdered()` | In-order delivery (default `true`). |
| `maxRetransmits(int)` / `getMaxRetransmits()` | Partial reliability by retransmit count. Mutually exclusive with lifetime. |
| `maxPacketLifeTimeMs(int)` / `getMaxPacketLifeTimeMs()` | Partial reliability by time. Mutually exclusive with retransmits. |
| `priority(int)` / `getPriority()` | DCEP priority (default 0). |

### `IceConnectionState`

`NEW`, `CHECKING`, `CONNECTED`, `FAILED`, `CLOSED`.

### Transport-parameter objects (`org.jitsi.videobridge.transport`)

These plain DTOs replace the XMPP/Jingle extension classes of upstream jitsi-videobridge
and are the library's signalling currency:

| Class | Fields |
|---|---|
| `TransportDescription` | `ufrag`, `password`, `rtcpMux` (always true), `List<IceCandidate> candidates`, `List<DtlsFingerprint> fingerprints`. Returned by `getLocalDescription()`, consumed by `setRemoteDescription(...)`. |
| `IceCandidate` | `foundation`, `component`, `protocol`, `priority`, `ip`, `port`, `type` (ice4j `CandidateType`), `generation`, `id`, `network`, `relAddr`/`relPort`. `Comparable` (host < reflexive < relayed). |
| `DtlsFingerprint` | `hash` (e.g. `sha-256`), `fingerprint`, `setup` (`active`/`passive`/`actpass`), `cryptex`. |

### `com.igrium.sfu.sdp.SdpUtils` (optional)

SDP glue for hosts whose peers speak SDP. The library core never touches SDP.

| Method | Description |
|---|---|
| `ParsedSdp parse(String sdp)` | Extract `TransportDescription` + `mid` + `sctp-port` from a data-channel-only offer or answer. |
| `IceCandidate parseCandidate(String value)` | Parse one `candidate:...` attribute value (e.g. a trickled `RTCIceCandidate.candidate`). |
| `String buildAnswer(TransportDescription local, ParsedSdp offer)` | Build the SDP answer to an offer. |
| `String buildOffer(TransportDescription local, String mid)` | Build a data-channel-only SDP offer. |
| `String candidateAttribute(IceCandidate)` | Format one candidate as a `candidate:...` attribute value. |

### Configuration

Upstream's HOCON/metaconfig system is replaced by plain Java config singletons that hold
the same upstream default values:

| Class | Values (upstream defaults) |
|---|---|
| `org.jitsi.videobridge.ice.IceConfig` | ICE single UDP port `10000`, keep-alive `selected_only`, nomination `NominateFirstHostOrReflexiveValid`, `advertisePrivateCandidates=true`, `resolveRemoteCandidates=false`. |
| `org.jitsi.videobridge.sctp.SctpConfig` | SCTP enabled, `maxChannels=1` (settable via `setMaxChannels` — a library-facing addition). |
| `org.jitsi.nlj.dtls.DtlsConfig` | 30s handshake timeout, upstream cipher-suite list, `sha-256` local fingerprint. |
| `org.jitsi.nlj.srtp.SrtpConfig` | Upstream SRTP protection profiles, OpenSSL-first factory. |

The library also applies, programmatically, the ice4j settings upstream ships in its
application config: the single-port-harvester push API is enabled and dynamic-port host
candidates and link-local addresses are disabled (see the static initializer of
`org.jitsi.videobridge.transport.ice.IceTransport`).

## The testapp demo

```sh
gradle :testapp:installDist
testapp/build/install/testapp/bin/testapp   # serves http://localhost:8080/
```

Open `http://localhost:8080/` in two browser tabs: each tab creates an
`RTCPeerConnection` with a `chat` data channel, POSTs its offer to `/offer`, and the demo
SFU answers and relays every data channel message to all other connected peers.
`/debug` returns the transports' debug state as JSON.

The repository's end-to-end test drives exactly this with Playwright: two Chromium
contexts connect (ICE `connected`, DTLS complete, no errors) and exchange messages
through the SFU in both directions.

## Architecture

```
                    com.igrium.sfu.SfuPeerConnection
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        ▼                          ▼                          ▼
  IceTransport  ──DTLS?──▶   DtlsTransport   ──SCTP──▶  DcSctpTransport
  (ice4j, single            (BouncyCastle via           (dcsctp4j)
   port harvester)           org.jitsi.nlj.dtls)              │
        ▲                          │                          ▼
        └────────── send ──────────┘                  DataChannelStack
                                                      (DCEP, RFC 8832)
```

The wiring mirrors upstream `org.jitsi.videobridge.Endpoint`/`Relay`: incoming ICE data
that looks like DTLS goes to the DTLS transport; decrypted DTLS application data is SCTP;
outgoing SCTP is sent as DTLS application data; after the handshake the DTLS *client*
side initiates the SCTP association; data-channel messages are processed on a sequential
queue to avoid deadlocks inside the SCTP socket. Non-DTLS (SRTP) traffic will be handed
to the ported `org.jitsi.nlj` transceiver when the media phase lands.

### Port provenance

Ported code keeps upstream package/class/method names so it stays diffable against
jitsi-videobridge:

- `org.jitsi.rtp` — the RTP/RTCP packet model (from upstream `rtp/`, Kotlin → Java).
- `org.jitsi.nlj` — pipeline framework, DTLS stack, SRTP transformers (from upstream
  `jitsi-media-transform/`, Kotlin → Java; media-path nodes follow in the next phase).
- `org.jitsi.videobridge.*` — ICE/DTLS/SCTP transports and the data channel stack (from
  upstream `jvb/`; the data channel stack and buffer/task utilities were already Java and
  are copied near-verbatim).

Real, published dependencies are used as-is (never re-implemented): `ice4j`,
`jitsi-utils`, `jitsi-srtp`, `jitsi-dcsctp` (dcsctp4j), BouncyCastle, Jackson, Guava,
SLF4J.

See `DECISIONS.md` for the decision log and `Agents.md` for codebase orientation.

## License

Apache License 2.0. Ported files retain their upstream copyright headers
(© 8x8, Inc. and contributors — jitsi-videobridge is Apache-2.0).
