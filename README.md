# vibe-sfu

A standalone Java library for building WebRTC SFUs (Selective Forwarding Units) on the JVM,
created by porting the media pipeline and transport stack of
[jitsi-videobridge](https://github.com/jitsi/jitsi-videobridge) into a reusable,
programmatic, signalling-agnostic library — in the spirit of
[webrtc-java](https://github.com/devopvoid/webrtc-java), but SFU-oriented: media can be
forwarded between peers **without decoding or re-encoding**.

All conference/room semantics, XMPP/COLIBRI signalling, REST APIs, health checks and stats
reporting of the original videobridge are stripped; what remains is the packet pipeline
(RTP/RTCP model, DTLS, SRTP, SCTP, data channels, jitter handling, simulcast/SVC codec
parsing, bandwidth estimation and the bitrate controller) behind a small public API. The
host application brings its own signalling.

> **Status: full media pipeline complete.** ICE + DTLS + SCTP + WebRTC data channels **and**
> the RTP/SRTP media path (audio/video forwarding, congestion control, bitrate control,
> simulcast/SVC frame projection) are ported and work end-to-end against real browsers —
> verified with Playwright fake-media devices (`inbound-rtp` packets/bytes received both
> ways; see [testapp](#the-testapp-demo)). Media is forwarded as raw RTP with **no decode /
> re-encode**.

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

### Data channels

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

### Forwarding audio/video (the SFU contract)

Media is forwarded as **raw RTP** — the library hands you the SRTP-decrypted packets and
accepts RTP back for the outbound peer, with no transcoding. You register the remote peer's
SSRCs (typically parsed from its SDP) as *receive* tracks, and create *send* tracks for what
you forward out. To route media between two peers, forward each receive track's packets to
the other peer's send track:

```java
import org.jitsi.nlj.format.*;      // PayloadType (OpusPayloadType, Vp8PayloadType, ...)
import org.jitsi.nlj.rtp.*;         // RtpExtension, RtpExtensionType
import org.jitsi.rtp.rtp.RtpPacket;
import java.util.List;

long opusPt = 111, vp8Pt = 96;

// On peerA: receive the browser's audio + video (SSRCs from its SDP offer).
MediaTrack aAudioIn = peerA.addReceiveTrack(MediaKind.AUDIO, remoteAudioSsrc,
        List.of(new OpusPayloadType((byte) opusPt, 48000, 2)), List.of());
MediaTrack aVideoIn = peerA.addReceiveTrack(MediaKind.VIDEO, remoteVideoSsrc,
        List.of(new Vp8PayloadType((byte) vp8Pt, java.util.Map.of())), List.of());

// On peerB: send tracks with our own SSRCs, same payload types.
MediaTrack bAudioOut = peerB.createSendTrack(MediaKind.AUDIO, 0xA0A0A0L,
        List.of(new OpusPayloadType((byte) opusPt, 48000, 2)), List.of());
MediaTrack bVideoOut = peerB.createSendTrack(MediaKind.VIDEO, 0xB0B0B0L,
        List.of(new Vp8PayloadType((byte) vp8Pt, java.util.Map.of())), List.of());

// Forward: raw RTP in -> raw RTP out (sendRtp clones, so the packet stays valid).
aAudioIn.onRtpPacket(pkt -> bAudioOut.sendRtp(pkt));
aVideoIn.onRtpPacket(pkt -> bVideoOut.sendRtp(pkt));
```

The `RtpPacket` passed to `onRtpPacket` is valid only during the callback (its buffer is
recycled afterwards); forward it from inside the callback. See
[`testapp`](#the-testapp-demo)'s `DemoSfu.setupMediaLoopback` for a complete, working
example of mapping SDP codecs to payload types and wiring the tracks.

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
| `MediaTrack addReceiveTrack(MediaKind, long ssrc, List<PayloadType>, List<RtpExtension>)` | Register a media stream to receive from the remote peer (SSRC, payload types and header extensions, typically parsed from its SDP). Decrypted RTP for this SSRC is delivered to the returned track's `onRtpPacket` listener. |
| `MediaTrack createSendTrack(MediaKind, long ssrc, List<PayloadType>, List<RtpExtension>)` | Create a media stream to send to the remote peer (local SSRC + payload types/extensions). Use the returned track's `sendRtp` to send. |
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

Received media is delivered per-track via `MediaTrack.onRtpPacket` (below), not through the
observer — the host registers the tracks it expects (from signalling), keeping the observer
thin.

### `MediaTrack`

A single media flow (one SSRC), created by `addReceiveTrack` (receive) or `createSendTrack`
(send). This is the raw-RTP SFU surface: forward decrypted RTP with no transcode.

| Member | Description |
|---|---|
| `MediaKind getKind()` | `AUDIO` or `VIDEO`. |
| `long getSsrc()` | The track's RTP SSRC. |
| `boolean isLocal()` | `true` for a send track, `false` for a receive track. |
| `void onRtpPacket(Consumer<RtpPacket>)` | *(receive only)* Register a listener for decrypted, parsed RTP. The packet is valid **only during the callback** (its buffer is recycled after); forward it via a send track's `sendRtp` from inside the callback. |
| `void sendRtp(RtpPacket)` | *(send only)* Send an RTP packet to the remote peer (SRTP-encrypted). The packet is cloned, so the caller keeps ownership. |

### `MediaKind`

`AUDIO`, `VIDEO`. `toMediaType()` returns the corresponding jitsi `MediaType`.

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

SDP glue for hosts whose peers speak SDP. The library core never touches SDP; `SdpUtils`
deals only in plain SDP data (codec/PT/clock/fmtp/extmap/ssrc) and the transport DTOs — it
does **not** depend on `org.jitsi.nlj`, so mapping SDP codecs to payload types is the host's
job (see `DemoSfu`).

| Method | Description |
|---|---|
| `ParsedSdp parse(String sdp)` | Extract the `TransportDescription`, `mid`, `sctp-port`, and any audio/video `MediaDescription`s (codecs, extmaps, SSRCs, direction) from an offer or answer. |
| `IceCandidate parseCandidate(String value)` | Parse one `candidate:...` attribute value (e.g. a trickled `RTCIceCandidate.candidate`). |
| `String buildAnswer(TransportDescription local, ParsedSdp offer)` | Build a data-channel-only SDP answer. |
| `String buildAnswer(TransportDescription local, ParsedSdp offer, List<MediaAnswer>)` | Build an audio/video (± data channel) SDP answer: BUNDLE + rtcp-mux, with the given per-m-line codecs/extensions/SSRCs. |
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

The `testapp` module is a tiny demo SFU (`DemoSfu`) that serves a browser client and
answers WebRTC offers over plain HTTP — the smallest thing that exercises the library
against a real browser.

**Run it:**

```sh
gradle :testapp:run
# ...or build a launcher script:
gradle :testapp:installDist
testapp/build/install/testapp/bin/testapp
# either way it serves http://localhost:8080/
```

**Data channels.** Open `http://localhost:8080/` in two browser tabs: each tab creates an
`RTCPeerConnection` with a `chat` data channel, POSTs its offer to `/offer`, and the demo
SFU answers and relays every data channel message to all other connected peers.

**Audio/video.** The page also calls `getUserMedia({audio, video})` and adds the tracks to
the offer. `DemoSfu.setupMediaLoopback` parses the offer's media m-lines (via `SdpUtils`),
maps each SDP codec to a payload type and each `extmap` to an `RtpExtension`, registers a
receive `MediaTrack` per audio/video SSRC and a send track under the SFU's own SSRC, and
**echoes the received RTP back** — so the browser sees its own media returned through the
SFU (rendered in the page's `<video>`). It's a loopback, which makes the media path easy to
observe; forwarding between two peers is the same call wired to a different peer's send
track (see the [quick start](#forwarding-audiovideo-the-sfu-contract)).

**Diagnostics.** `/debug` returns the transports' debug state plus a running
`forwarded_rtp_packets` counter as JSON.

**End-to-end tests** (Playwright, headless Chromium):

- *Data channels* — two Chromium contexts connect (ICE `connected`, DTLS complete) and
  exchange messages through the SFU both ways.
- *Media* — one context with **fake audio/video devices**
  (`--use-fake-device-for-media-stream`) connects, and the test asserts real media flows by
  polling `RTCPeerConnection.getStats()` for `inbound-rtp` reports with
  `bytesReceived > 0 && packetsReceived > 0` for both audio and video, plus a nonzero
  server-side forward counter. (`DemoSfu` requires no DTLS certs or config — it generates
  everything at startup.)

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
queue to avoid deadlocks inside the SCTP socket. Non-DTLS (SRTP) traffic is handed to the
ported `org.jitsi.nlj` `Transceiver`, whose receive pipeline decrypts/parses RTP (delivered
to `MediaTrack.onRtpPacket`) and whose send pipeline encrypts injected RTP back out over ICE.

### Port provenance

Ported code keeps upstream package/class/method names so it stays diffable against
jitsi-videobridge:

- `org.jitsi.rtp` — the RTP/RTCP packet model (from upstream `rtp/`, Kotlin → Java).
- `org.jitsi.nlj` — the full media pipeline (from upstream `jitsi-media-transform/`,
  Kotlin → Java): the node framework, DTLS stack, SRTP transformers, RTCP handling, VP8/VP9/
  AV1 codec parsing, congestion control / bandwidth estimation (`TransportCcEngine` + GoogCc),
  and the `Transceiver`/`RtpReceiver`/`RtpSender` assembly.
- `org.jitsi.videobridge.*` — ICE/DTLS/SCTP transports, the data channel stack, and the
  `cc/` bitrate controller + simulcast/SVC frame projection (from upstream `jvb/`; the data
  channel stack and buffer/task utilities were already Java and are copied near-verbatim).

Real, published dependencies are used as-is (never re-implemented): `ice4j`,
`jitsi-utils`, `jitsi-srtp`, `jitsi-dcsctp` (dcsctp4j), BouncyCastle, Jackson, Guava,
SLF4J.

See `DECISIONS.md` for the decision log and `Agents.md` for codebase orientation.

## License

Apache License 2.0. Ported files retain their upstream copyright headers
(© 8x8, Inc. and contributors — jitsi-videobridge is Apache-2.0).
