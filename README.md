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
> ways; see [testapp](#the-testapp-demos)). Media is forwarded as raw RTP with **no decode /
> re-encode**.

## Modules

| Gradle project | What it is |
|---|---|
| `vibe-sfu` | The library: ported jitsi-videobridge code (`org.jitsi.rtp`, `org.jitsi.nlj`, `org.jitsi.videobridge.*`) + the public API (`com.igrium.sfu`). |
| `testapp` | Two runnable demo SFUs + browser test clients (see [the testapp demos](#the-testapp-demos)). |

Build with Java 21+:

```sh
gradle :vibe-sfu:build      # the library
gradle :testapp:installDist # the demo SFUs
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
[`testapp`](#the-testapp-demos)'s `DemoSfu.setupMediaForward` for a complete, working
example of mapping SDP codecs to payload types and wiring the tracks.

### Transceivers: declaring media *before* the peer offers it

`addReceiveTrack`/`createSendTrack` describe media that already exists in signalling, which is
enough when the browser offers first and the SFU answers. It is not enough for an SFU that has
to *introduce* streams — a new participant joining a room — because the answerer can only
describe sections the offer already contains.

A **`MediaTransceiver`** is the library's analogue of the browser's `RTCRtpTransceiver`: one
`m=` section, with a stable mid, at most one send track and at most one receive track, and the
codecs, SSRC and `msid` needed to describe it. It can be created before the remote peer has
said anything, so the host can put it in its **own offer**:

```java
import com.igrium.sfu.*;
import com.igrium.sfu.sdp.SdpUtils;
import java.util.List;

// We are the offerer this time.
SfuPeerConnection pc = new SfuPeerConnection(SfuPeerConnection.Role.OFFERER, observer);

// Reserve the data channel's mid first, so it stays section 0 across every later re-offer,
// then create the channel — an offerer can put its own data channel in the initial offer.
String dataMid = pc.getDataChannelMid();
DataChannelTrack signalling = pc.createDataChannel("signal");

// The peer's uplink: we receive their camera. Declaring it here means they attach their track
// to a section *we* chose, rather than having to offer one of their own.
MediaTransceiver uplink = pc.addTransceiver(
        MediaKind.VIDEO, MediaDirection.RECVONLY, videoPayloadTypes, videoExtensions);

// A downlink carrying some other participant's camera. `msid` stream id = that participant's
// id, so the browser groups their audio and video into one MediaStream.
MediaTransceiver downlink = pc.addTransceiver(
        MediaKind.VIDEO, MediaDirection.SENDONLY, videoPayloadTypes, videoExtensions,
        /* streamId = */ otherParticipantId, /* trackId = */ otherParticipantId + "-video");

// Build the offer from the transceivers (see ConferenceSfu.toSection for the mapping).
List<SdpUtils.MediaSection> sections = new ArrayList<>();
sections.add(SdpUtils.MediaSection.dataChannel(dataMid));
for (MediaTransceiver t : pc.getTransceivers()) sections.add(toSection(t));
String offerSdp = SdpUtils.buildOffer(pc.getLocalDescription(), sections);
// ... signal offerSdp, receive answerSdp ...

// Bind the SSRC the peer answered with; the returned receive track delivers its RTP.
SdpUtils.ParsedSdp answer = SdpUtils.parse(answerSdp);
pc.setRemoteDescription(answer.transport);                // initial exchange only
uplink.setRemoteSsrc(answer.getMediaByMid(uplink.getMid()).ssrcs.get(0))
      .onRtpPacket(pkt -> downlinkOnSomeOtherPeer.forwardRtp(pkt, uplinkTrack));
```

Adding or stopping a transceiver after the first `getLocalDescription()` fires
`onRenegotiationNeeded()`; the host builds a fresh offer the same way and delivers it however
it likes — the [conference demo](#the-testapp-demos) sends it over the SFU's own data channel.
`MediaTransceiver.stop()` **keeps** the transceiver in `getTransceivers()` so its `m=` section
can keep being emitted as rejected (port 0): JSEP forbids reordering or dropping sections
between offers, so a stream that goes away leaves a gap rather than shifting everything after it.

Because only the SFU ever offers, glare is impossible by construction — a useful property that
falls out of the SFU owning negotiation. Renegotiation that only changes media reuses the
established ICE/DTLS transport, so `setRemoteDescription` is only needed for the initial
exchange (or an ICE restart).

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
| `MediaTrack createSendTrack(MediaKind, long ssrc, List<PayloadType>, List<RtpExtension>)` | Create a media stream to send to the remote peer (local SSRC + payload types/extensions). Use the returned track's `sendRtp` to send. Adding one after the first `getLocalDescription()` fires `onRenegotiationNeeded()`. |
| `void removeReceiveTrack(MediaTrack)` | Stop receiving a track from `addReceiveTrack`: drops it from the pipeline and releases its forwarding projection. Does **not** fire `onRenegotiationNeeded()` (receive-side changes follow the remote's signalling). |
| `void removeSendTrack(MediaTrack)` | Stop sending a track from `createSendTrack`; fires `onRenegotiationNeeded()` if already negotiated. Stop calling `sendRtp`/`forwardRtp` on it first. |
| `MediaTransceiver addTransceiver(MediaKind, MediaDirection, List<PayloadType>, List<RtpExtension>)` | Add a media section with an auto-allocated mid, send SSRC and `msid`. Unlike the track methods, this can be called **before** the remote peer signals anything, so the host can describe it in its own offer. A sending direction creates the send track immediately and fires `onRenegotiationNeeded()` once negotiated. |
| `MediaTransceiver addTransceiver(..., String streamId, String trackId)` | As above with explicit `msid` identifiers — tracks sharing a `streamId` arrive grouped in one `MediaStream`. |
| `MediaTransceiver addTransceiver(String mid, MediaKind, MediaDirection, List<PayloadType>, List<RtpExtension>, long sendSsrc, String streamId, String trackId)` | Full control over mid and SSRC, e.g. to mirror the sections of a remote offer. |
| `List<MediaTransceiver> getTransceivers()` | All media sections in creation order, **including stopped ones** — the order every offer's `m=` lines must keep. |
| `MediaTransceiver getTransceiver(String mid)` | Look one up by mid, or null. |
| `String reserveMid()` | Take the next media-line id. Media and data sections share one id space. |
| `String getDataChannelMid()` | The mid for this connection's `m=application` section, reserving one on first call. Call it before adding transceivers to keep the data section first. |
| `ObjectNode getDebugState()` | JSON diagnostics snapshot of the ICE/DTLS/SCTP transports. |
| `void close()` | Tear everything down; fires `onClosed()`. Idempotent. |

### `SfuPeerConnectionObserver`

All methods have no-op defaults. Callbacks run on internal library threads — don't block.

| Callback | Fired when |
|---|---|
| `onIceConnectionStateChange(IceConnectionState)` | ICE state transitions (`CHECKING`, `CONNECTED`, `FAILED`, `CLOSED`). |
| `onIceCandidate(IceCandidate)` | A local ICE candidate is available to trickle. Candidates are gathered synchronously and also returned complete from `getLocalDescription()`, so there is nothing to trickle from our side — this replays that complete set (once, on the first `getLocalDescription()`) for hosts that prefer an event. |
| `onConnected()` | ICE connected **and** DTLS handshake complete — the connection is usable. |
| `onDisconnected()` | Transport lost (ICE failure). |
| `onDtlsError(Throwable)` | The DTLS handshake failed; the connection can't carry media or data. Tear it down (and retry with fresh transport if desired). |
| `onRenegotiationNeeded()` | A send track was added/removed after the first `getLocalDescription()`, so the host must generate a fresh offer and exchange it with the remote peer — the analogue of a browser `RTCPeerConnection`'s `negotiationneeded`. Only local send-side changes fire this (`createSendTrack`/`removeSendTrack`); registering a receive track follows the remote's offer and does not. Bursts of synchronous changes are coalesced into one callback. |
| `onDataChannel(DataChannelTrack)` | The remote peer opened a data channel (already open and usable). |
| `onDataChannelOpen(DataChannelTrack)` | A locally-created channel finished opening (remote acknowledged it). |
| `onDataChannelStringMessage(DataChannelTrack, String)` | Inbound string message. |
| `onDataChannelBinaryMessage(DataChannelTrack, byte[])` | Inbound binary message. |
| `onBandwidthEstimateChanged(long bps)` | The connection's bandwidth estimate changed (bits per second), forwarded from the media pipeline's estimator — a forwarding host can use it to decide how much media to relay onto this connection. |
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

### `MediaTransceiver`

One `m=` section — the library's `RTCRtpTransceiver`. Created by
`SfuPeerConnection.addTransceiver`, and the only way to declare media *before* the remote peer
has offered it. See [transceivers](#transceivers-declaring-media-before-the-peer-offers-it).

| Member | Description |
|---|---|
| `String getMid()` | The media-line id of this section. |
| `MediaKind getKind()` | `AUDIO` or `VIDEO`. |
| `SfuPeerConnection getConnection()` | The owning connection. |
| `MediaDirection getDirection()` | Current direction, from this side's point of view. |
| `void setDirection(MediaDirection)` | Change it, creating/removing the send track to match. Entering or leaving a sending direction fires `onRenegotiationNeeded()`; leaving a receiving direction also releases the receive track. |
| `List<PayloadType> getPayloadTypes()` / `List<RtpExtension> getRtpExtensions()` | What to signal for this section (and what the pipeline was given). |
| `long getSendSsrc()` | The local SSRC this section sends with. Allocated at creation — even for a `RECVONLY` section — so it is stable and can be signalled up front. |
| `String getStreamId()` / `String getTrackId()` | The `msid` identifiers. Tracks sharing a stream id are grouped into one `MediaStream` by the receiver, so an SFU gives each forwarded participant its own stream id. |
| `String getCname()` / `void setCname(String)` | The RTCP `cname` to signal for our SSRC. |
| `MediaTrack getSender()` | The send track, or null when not sending. |
| `MediaTrack getReceiver()` | The receive track, or null until `setRemoteSsrc`. |
| `long getRemoteSsrc()` | The bound remote SSRC, or -1. |
| `MediaTrack setRemoteSsrc(long)` | Bind the SSRC the remote answered with and get the receive track for it. Does **not** fire `onRenegotiationNeeded()` (it follows the remote's signalling). Re-binding the same SSRC is a no-op; a different one replaces the track, so re-register `onRtpPacket`. |
| `void stop()` | Retire the transceiver: both tracks removed, direction `INACTIVE`, `onRenegotiationNeeded()` fired. It **stays** in `getTransceivers()` so later offers keep emitting its section as rejected. Idempotent. |
| `boolean isStopped()` | Whether `stop()` has been called. |

### `MediaDirection`

`SENDRECV`, `SENDONLY`, `RECVONLY`, `INACTIVE` — the browser's `RTCRtpTransceiverDirection`,
from this side's point of view. `getSdpToken()` gives the SDP attribute name, `isSending()` /
`isReceiving()` test it, `reversed()` returns it as the remote peer sees it, and
`fromSdpToken(String, MediaDirection fallback)` parses one.

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
| `ParsedSdp parse(String sdp)` | Extract the `TransportDescription`, `mid`, `sctp-port`, and every `MediaDescription` (codecs, extmaps, SSRCs, `msid`, direction, rejected-or-not) from an offer or answer. |
| `IceCandidate parseCandidate(String value)` | Parse one `candidate:...` attribute value (e.g. a trickled `RTCIceCandidate.candidate`). |
| `String buildAnswer(TransportDescription local, ParsedSdp offer)` | Build a data-channel-only SDP answer. |
| `String buildAnswer(TransportDescription local, ParsedSdp offer, List<MediaAnswer>)` | Build an audio/video (± data channel) SDP answer: BUNDLE + rtcp-mux, with the given per-m-line codecs/extensions/SSRCs. |
| `String buildOffer(TransportDescription local, String mid)` | Build a data-channel-only SDP offer. |
| `String buildOffer(TransportDescription local, List<MediaSection>)` | Build a full SDP offer from the sections you pass — the counterpart of `buildAnswer` for a host that *originates* negotiation. Emits them in list order, BUNDLEd with rtcp-mux, with `msid` and `a=rtcp-fb`. |
| `String candidateAttribute(IceCandidate)` | Format one candidate as a `candidate:...` attribute value. |

Supporting types: `ParsedSdp` (with `getMedia(kind)` and `getMediaByMid(mid)`),
`MediaDescription` (a parsed `m=` section: `codecs`, `extmaps`, `ssrcs`, `msidStream`/`msidTrack`,
`direction`, `isRejected()`), `Codec`, `Extmap`, and `MediaSection` — one `m=` section *we*
emit (`direction`, `codecs`, `extmaps`, `ssrcs`, `cname`, `msidStream`/`msidTrack`, `rejected`,
`sctpPort`), with `MediaSection.dataChannel(mid)` for an `m=application` section and
`MediaAnswer` as its answer-side subclass.

Mapping between `MediaSection`/`Codec` and `org.jitsi.nlj` payload types stays in the host
application, in both directions — `DemoSfu.toPayloadType` reads a browser offer, and
`ConferenceSfu.toSection` writes an offer from transceivers.

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

## The testapp demos

The `testapp` module contains two demo SFUs, each with its own browser client. They exercise
the two directions of signalling, and neither needs DTLS certs or config — everything is
generated at startup.

| Demo | Who offers | Port | Run |
|---|---|---|---|
| `DemoSfu` | the **browser** offers, the SFU answers | 8080 | `gradle :testapp:run` |
| `ConferenceSfu` | the **SFU** offers | 8081 | `gradle :testapp:runConference` |

```sh
gradle :testapp:installDist            # ...or build launcher scripts
testapp/build/install/testapp/bin/testapp     # http://localhost:8080/
testapp/build/install/testapp/bin/conference  # http://localhost:8081/
```

They serve HTTP on different ports, but both harvest the same single ICE UDP port (10000),
so **run one at a time**.

### `DemoSfu` — the SFU as answerer

The smallest thing that exercises the library against a real browser.

**Data channels.** Open `http://localhost:8080/` in two browser tabs: each tab creates an
`RTCPeerConnection` with a `chat` data channel, POSTs its offer to `/offer`, and the demo
SFU answers and relays every data channel message to all other connected peers.

**Audio/video.** The page also calls `getUserMedia({audio, video})` and adds the tracks to
the offer. `DemoSfu.setupMediaForward` parses the offer's media m-lines (via `SdpUtils`),
maps each SDP codec to a payload type and each `extmap` to an `RtpExtension`, registers a
receive `MediaTrack` per audio/video SSRC and a send track under the SFU's own SSRC, and
forwards each peer's RTP to the other's downlink — raw RTP, no decode/re-encode.

**Diagnostics.** `/debug` returns the transports' debug state plus a running
`forwarded_rtp_packets` counter as JSON.

### `ConferenceSfu` — the SFU as offerer, with dynamic join/leave

A multi-party conference where the SFU owns negotiation, built on
[`MediaTransceiver`](#transceivers-declaring-media-before-the-peer-offers-it). Open
`http://localhost:8081/` in several tabs and watch tiles appear and disappear as tabs open
and close.

Joining takes **one round trip**: `POST /join` returns the SFU's offer, already containing

- an `m=application` section for a data channel the **SFU** creates (used for renegotiation
  *and* chat — something only an offerer can put in the initial offer);
- two `RECVONLY` sections for the joiner's own mic and camera (the browser attaches its
  tracks with `replaceTrack` on the transceivers the SFU chose, and never calls `addTrack`);
- two `SENDONLY` sections **per other participant already in the room**, each tagged with an
  `msid` stream id equal to that participant's id, so the browser groups their audio and video
  into one `MediaStream` and can label the tile with no extra signalling.

After that, every change is driven by the SFU: a join adds a downlink pair to every existing
participant, `onRenegotiationNeeded()` fires, and the new offer goes out over that data channel
with the answer coming back the same way. A leave calls `MediaTransceiver.stop()`, so the
section is re-offered as rejected and the tile disappears. Because the browser never offers,
glare cannot happen.

`/debug` also lists each participant's transceivers and downlink count.

### End-to-end tests

Both demos are verified with Playwright and headless Chromium using **fake audio/video
devices** (`--use-fake-device-for-media-stream`), asserting real media by polling
`RTCPeerConnection.getStats()` for `inbound-rtp` reports with
`bytesReceived > 0 && packetsReceived > 0`.

- *`DemoSfu`* — two Chromium contexts connect (ICE `connected`, DTLS complete), exchange data
  channel messages both ways, and receive each other's audio and video; the server's
  `forwarded_rtp_packets` counter is nonzero for both.
- *`ConferenceSfu`* — a peer joins an empty room; a second joins and both renegotiate
  **without the browser ever offering**; audio and video flow both ways; a third joins and
  every peer ends up with a downlink for every other; chat is relayed over the SFU-created
  data channel; then a peer leaves and the others drop exactly that tile while media keeps
  flowing between the survivors.

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
