/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.igrium.sfu;

import org.jitsi.dcsctp4j.DcSctpMessage;
import org.jitsi.dcsctp4j.ErrorKind;
import org.jitsi.dcsctp4j.SendPacketStatus;
import org.jitsi.dcsctp4j.SendStatus;
import org.jitsi.nlj.MediaSourceDesc;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.RtpEncodingDesc;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.Transceiver;
import org.jitsi.nlj.TransceiverEventHandler;
import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.rtp.RtpExtension;
import org.jitsi.nlj.rtp.VideoRtpPacket;
import org.jitsi.nlj.rtp.codec.vpx.VpxRtpLayerDesc;
import org.jitsi.nlj.srtp.TlsRole;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.PacketInfoQueue;
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.UnparsedPacket;
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;
import org.jitsi.videobridge.TransportConfig;
import org.jitsi.videobridge.cc.AdaptiveSourceProjection;
import org.jitsi.videobridge.cc.RewriteException;
import org.jitsi.videobridge.datachannel.DataChannel;
import org.jitsi.videobridge.datachannel.DataChannelStack;
import org.jitsi.videobridge.datachannel.protocol.DataChannelBinaryMessage;
import org.jitsi.videobridge.datachannel.protocol.DataChannelPacket;
import org.jitsi.videobridge.datachannel.protocol.DataChannelStringMessage;
import org.jitsi.videobridge.dcsctp.DcSctpBaseCallbacks;
import org.jitsi.videobridge.dcsctp.DcSctpHandler;
import org.jitsi.videobridge.dcsctp.DcSctpTransport;
import org.jitsi.videobridge.transport.DtlsFingerprint;
import org.jitsi.videobridge.transport.IceCandidate;
import org.jitsi.videobridge.transport.TransportDescription;
import org.jitsi.videobridge.transport.dtls.DtlsTransport;
import org.jitsi.videobridge.transport.ice.IceTransport;
import org.jitsi.videobridge.util.ByteBufferPool;
import org.jitsi.videobridge.util.PacketUtils;
import org.jitsi.videobridge.util.TaskPools;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * A single WebRTC peer connection: ICE + DTLS + SCTP transport with WebRTC data
 * channels. This is the library's main entry point; it packages the ported
 * jitsi-videobridge transport stack ({@link IceTransport}, {@link DtlsTransport},
 * {@link DcSctpTransport}, {@link DataChannelStack}) behind a programmatic,
 * signalling-agnostic API.
 *
 * <p>The host application brings its own signalling. The local transport
 * parameters (ICE ufrag/password/candidates plus the DTLS fingerprint) are read
 * with {@link #getLocalDescription()}; the remote peer's parameters are applied
 * with {@link #setRemoteDescription(TransportDescription)} (and, for trickled
 * candidates, {@link #addRemoteCandidate(IceCandidate)}). Whether this side is
 * the offerer or the answerer is chosen per-connection via {@link Role}.
 *
 * <p>The wiring between the transports mirrors upstream
 * {@code org.jitsi.videobridge.Endpoint}/{@code Relay}:
 * <ul>
 *   <li>incoming ICE data that looks like DTLS is fed to the DTLS transport;</li>
 *   <li>decrypted DTLS application data is treated as SCTP and fed to the SCTP transport;</li>
 *   <li>outgoing SCTP packets are sent as DTLS application data;</li>
 *   <li>after the DTLS handshake, the DTLS <em>client</em> side initiates the SCTP association;</li>
 *   <li>data-channel (DCEP) messages are processed on a sequential queue to avoid
 *       deadlocks inside the SCTP socket.</li>
 * </ul>
 */
public class SfuPeerConnection implements Closeable
{
    /**
     * The signalling role of this side of the connection.
     */
    public enum Role
    {
        /**
         * This side makes the offer. It is the ICE controlling agent and offers
         * DTLS setup {@code actpass} (the answerer picks the DTLS role).
         */
        OFFERER,
        /**
         * This side answers a remote offer. It is the ICE controlled agent and
         * takes the DTLS role implied by the remote setup attribute (choosing
         * {@code active} when the remote offers {@code actpass}).
         */
        ANSWERER
    }

    /** How long a renegotiation check is deferred so synchronous track changes coalesce. */
    private static final long RENEGOTIATION_COALESCE_MS = 20;

    private final String id;
    private final Role role;
    private final SfuPeerConnectionObserver observer;
    private final Logger logger;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final IceTransport iceTransport;
    private final DtlsTransport dtlsTransport;
    private final DcSctpHandler sctpHandler = new DcSctpHandler();
    private final DcSctpTransport sctpTransport;
    private final DataChannelStack dataChannelStack;

    /** The media pipeline (RTP/RTCP receive + send, SRTP, BWE). Fed once DTLS supplies SRTP keys. */
    private final DiagnosticContext diagnosticContext = new DiagnosticContext();
    private final Transceiver transceiver;

    /** Receive tracks keyed by SSRC, so incoming RTP can be dispatched to the right listener. */
    private final Map<Long, MediaTrack> receiveTracks = new ConcurrentHashMap<>();

    /** Send tracks keyed by local SSRC, so they can be removed for runtime renegotiation. */
    private final Map<Long, MediaTrack> sendTracks = new ConcurrentHashMap<>();

    /**
     * Minimal (non-simulcast) {@link MediaSourceDesc}s for video receive tracks, one per SSRC
     * registered via {@link #addReceiveTrack}. The video receive pipeline
     * ({@code VideoQualityLayerLookup}) drops packets whose SSRC doesn't resolve to a layer here
     * — unlike audio, a bare {@code addReceiveSsrc} isn't enough for video passthrough. Rebuilt
     * and re-pushed to the transceiver (via {@code setMediaSources}, which replaces the whole
     * set) each time a video receive track is added.
     */
    private final List<MediaSourceDesc> videoReceiveSources = new ArrayList<>();

    /**
     * Per-forwarded-source video projections for this connection's <em>send</em> side, keyed by the
     * upstream source SSRC (see {@link #forwardRtp}). Mirrors upstream
     * {@code BitrateController}/{@code PacketHandler.adaptiveSourceProjectionMap}, which lives in the
     * receiving endpoint and holds one {@link AdaptiveSourceProjection} per source it forwards. Each
     * rewrites its source into a clean, gap-free stream so quality/source switches are transparent
     * to the receiver.
     */
    private final Map<Long, AdaptiveSourceProjection> sendProjections = new ConcurrentHashMap<>();

    /**
     * Enforces sequential processing of incoming data channel messages to maintain
     * processing order (and avoid re-entering the SCTP socket lock; see upstream
     * {@code Endpoint.incomingDataChannelMessagesQueue}).
     */
    private final PacketInfoQueue incomingDataChannelMessagesQueue;

    /** Whether the SCTP association is established. */
    private final AtomicBoolean sctpConnected = new AtomicBoolean(false);

    /** Our DTLS role after the handshake; determines data channel sid parity (RFC 8832). */
    private volatile TlsRole tlsRole;

    /** Locally-created tracks waiting for the SCTP association before they can open. */
    private final List<DataChannelTrack> pendingLocalChannels = new ArrayList<>();

    /** Counter for locally-allocated SCTP stream ids (sid = 2*n + parity). */
    private final AtomicInteger nextLocalChannelIndex = new AtomicInteger(0);

    /** The last remote description, kept so trickled candidates can reuse ufrag/password. */
    private TransportDescription remoteDescription;

    /**
     * Whether an initial local description has been handed to the host yet (via
     * {@link #getLocalDescription()}). Until then, runtime track changes need no
     * {@link SfuPeerConnectionObserver#onRenegotiationNeeded()} — the first offer/answer
     * already describes them. After it, a send-side change means the host must re-signal.
     */
    private volatile boolean negotiated = false;

    /**
     * The negotiation-needed latch: {@code renegotiationNeeded} records that a send-side change
     * has happened since the last callback, and {@code renegotiationScheduled} records that a
     * deferred check is already queued. Together they coalesce a burst of synchronous changes
     * into a single {@link SfuPeerConnectionObserver#onRenegotiationNeeded()} while guaranteeing
     * at least one callback after the final change — mirroring the browser negotiation-needed
     * algorithm, which sets a flag and queues one check task rather than firing per {@code addTrack}.
     */
    private final AtomicBoolean renegotiationNeeded = new AtomicBoolean(false);
    private final AtomicBoolean renegotiationScheduled = new AtomicBoolean(false);

    /**
     * Invoked when the remote peer requests a keyframe (RTCP PLI/FIR) for one of the SSRCs we are
     * sending; the argument is that SSRC. A forwarding host should translate this into a
     * {@link #requestKeyFrame} on the upstream source SSRC, so the original encoder emits a fresh
     * keyframe. Without this, a receiver that misses the initial keyframe (e.g. Chrome after a
     * lost packet) can stay stuck on a black frame indefinitely.
     */
    private volatile LongConsumer keyFrameRequestHandler;

    /**
     * Creates a peer connection with a random id and a default logger.
     */
    public SfuPeerConnection(Role role, SfuPeerConnectionObserver observer)
    {
        this(UUID.randomUUID().toString(), role, observer, new LoggerImpl(SfuPeerConnection.class.getName()));
    }

    /**
     * Creates a peer connection.
     *
     * @param id an identifier for this connection, used in logging and thread/queue names.
     * @param role whether this side is the offerer or the answerer.
     * @param observer receives lifecycle and data events. Must not be null.
     * @param parentLogger the logger to create this connection's logger from.
     */
    public SfuPeerConnection(String id, Role role, SfuPeerConnectionObserver observer, Logger parentLogger)
    {
        this.id = id;
        this.role = role;
        this.observer = observer;
        this.logger = parentLogger.createChildLogger(getClass().getName());
        logger.addContext("connection_id", id);

        iceTransport = new IceTransport(
            id,
            /* controlling = */ role == Role.OFFERER,
            /* useUniquePort = */ false,
            /* advertisePrivateAddresses = */ true,
            logger);
        dtlsTransport = new DtlsTransport(logger, id);
        sctpTransport = new DcSctpTransport(id, logger);

        dataChannelStack = new DataChannelStack(
            (data, sid, ppid) -> {
                DcSctpMessage message = new DcSctpMessage((short) sid, ppid, data.array());
                SendStatus status = sctpTransport.send(message, DcSctpTransport.getDefaultSendOptions());
                if (status == SendStatus.kSuccess)
                {
                    return 0;
                }
                logger.error("Error sending to SCTP: " + status);
                return -1;
            },
            logger);
        dataChannelStack.onDataChannelStackEvents(this::dataChannelOpenedRemotely);

        incomingDataChannelMessagesQueue = new PacketInfoQueue(
            getClass().getSimpleName() + "-incoming-data-channel-queue",
            TaskPools.IO_POOL,
            this::processDataChannelPacket,
            TransportConfig.getQueueSize());

        transceiver = new Transceiver(
            id,
            TaskPools.CPU_POOL,
            TaskPools.CPU_POOL,
            TaskPools.SCHEDULED_POOL,
            diagnosticContext,
            logger,
            new TransceiverEventHandler()
            {
                @Override
                public void bandwidthEstimationChanged(Bandwidth newValue)
                {
                    logger.debug(() -> "Bandwidth estimation changed to " + newValue);
                    // Forward to the host (mirrors upstream Endpoint.TransceiverEventHandlerImpl,
                    // which feeds newValue.bps into the bitrate controller / connection stats).
                    observer.onBandwidthEstimateChanged(newValue.getBps());
                }
            },
            Clock.systemUTC());
        // Fully-processed (SRTP-encrypted) outgoing packets go out over ICE. Mirrors upstream
        // Endpoint.doSendSrtp: send, recycle the buffer, then fire the packet's onSent actions.
        // The sent() call is essential — it drives TransportCcEngine.mediaPacketSent (via the
        // TccSeqNumTagger's onSent hook), without which every tagged packet is never marked sent,
        // transport-cc feedback can't be matched ("Received feedback before packet ... was
        // indicated as sent"), and the bandwidth estimate is starved.
        transceiver.setOutgoingPacketHandler(packetInfo -> {
            Packet packet = packetInfo.getPacket();
            iceTransport.send(packet.getBuffer(), packet.getOffset(), packet.getLength());
            ByteBufferPool.returnBuffer(packet.getBuffer());
            packetInfo.sent();
        });
        // Fully-received (decrypted, parsed) RTP/RTCP is handed to the media layer.
        transceiver.setIncomingPacketHandler(this::handleReceivedMediaPacket);

        setupIceTransport();
        setupDtlsTransport();
        sctpTransport.start(new SctpCallbacks(sctpTransport));
        sctpHandler.setSctpTransport(sctpTransport);
    }

    /** The identifier of this connection. */
    public String getId()
    {
        return id;
    }

    /** The signalling role of this side of the connection. */
    public Role getRole()
    {
        return role;
    }

    /**
     * Whether the connection is fully established (ICE connected and DTLS handshake
     * complete).
     */
    public boolean isConnected()
    {
        return iceTransport.isConnected() && dtlsTransport.isConnected();
    }

    /**
     * Returns this side's transport parameters (ICE ufrag/password/candidates and the
     * DTLS fingerprint) for the host to deliver to the remote peer via its own
     * signalling. Candidates are gathered up front, so the returned description is
     * complete (no trickle needed on this side).
     */
    public TransportDescription getLocalDescription()
    {
        TransportDescription description = new TransportDescription();
        iceTransport.describe(description);
        dtlsTransport.describe(description);
        if (!negotiated)
        {
            // Surface each (already fully gathered) local candidate for hosts that trickle;
            // the returned description carries the same complete set (see onIceCandidate).
            for (IceCandidate candidate : description.candidates)
            {
                observer.onIceCandidate(candidate);
            }
        }
        // The host now has a local description to signal; any later send-side track change
        // requires an explicit re-offer, so start reporting renegotiation from here on.
        negotiated = true;
        return description;
    }

    /**
     * Requests an {@link SfuPeerConnectionObserver#onRenegotiationNeeded()} callback because the
     * local send-side media set changed. No-op before the first {@link #getLocalDescription()}
     * (the initial description already covers the starting set) or after {@link #close()}.
     * Multiple synchronous changes are coalesced into one callback.
     */
    private void signalRenegotiationNeeded()
    {
        if (!negotiated || closed.get())
        {
            return;
        }
        renegotiationNeeded.set(true);
        // Defer the check briefly so a burst of synchronous track changes collapses into one
        // callback (the queued check runs after the caller's changes are all in).
        if (renegotiationScheduled.compareAndSet(false, true))
        {
            TaskPools.SCHEDULED_POOL.schedule(
                this::fireRenegotiationIfNeeded, RENEGOTIATION_COALESCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void fireRenegotiationIfNeeded()
    {
        renegotiationScheduled.set(false);
        if (renegotiationNeeded.getAndSet(false) && negotiated && !closed.get())
        {
            observer.onRenegotiationNeeded();
        }
    }

    /**
     * Applies the remote peer's transport parameters and starts (or updates) ICE
     * connectivity establishment. May be called again later with additional
     * candidates (or use {@link #addRemoteCandidate(IceCandidate)}).
     */
    public void setRemoteDescription(TransportDescription remote)
    {
        this.remoteDescription = remote;

        // DTLS fingerprints, keyed by lower-case hash function (mirrors Endpoint.setTransportInfo).
        Map<String, List<String>> remoteFingerprints = new HashMap<>();
        for (DtlsFingerprint fingerprint : remote.fingerprints)
        {
            if (fingerprint.hash != null && fingerprint.fingerprint != null)
            {
                remoteFingerprints
                    .computeIfAbsent(fingerprint.hash.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(fingerprint.fingerprint);
            }
            else
            {
                logger.info("Ignoring empty DtlsFingerprint");
            }
        }
        dtlsTransport.setRemoteFingerprints(remoteFingerprints);

        if (!remote.fingerprints.isEmpty())
        {
            String setup = remote.fingerprints.get(0).setup;
            if (setup != null && setup.equalsIgnoreCase("actpass"))
            {
                // The remote peer offered both roles; per RFC 5763 the answerer should
                // choose "active" (DTLS client). DtlsTransport.setSetupAttribute takes
                // the *remote* setup, so tell it the remote will be the passive/server side.
                setup = "passive";
            }
            dtlsTransport.setSetupAttribute(setup);
        }

        observer.onIceConnectionStateChange(IceConnectionState.CHECKING);
        iceTransport.startConnectivityEstablishment(remote);
    }

    /**
     * Adds a single trickled remote ICE candidate. {@link #setRemoteDescription} must
     * have been called first (its ufrag/password are reused).
     */
    public void addRemoteCandidate(IceCandidate candidate)
    {
        TransportDescription remote = this.remoteDescription;
        if (remote == null)
        {
            throw new IllegalStateException("setRemoteDescription must be called before addRemoteCandidate");
        }
        TransportDescription update = new TransportDescription();
        update.ufrag = remote.ufrag;
        update.password = remote.password;
        update.candidates.add(candidate);
        iceTransport.startConnectivityEstablishment(update);
    }

    /**
     * Creates a data channel with default (reliable, ordered) options.
     * @see #createDataChannel(String, DataChannelOptions)
     */
    public DataChannelTrack createDataChannel(String label)
    {
        return createDataChannel(label, new DataChannelOptions());
    }

    /**
     * Creates a data channel. If the connection is not established yet, the channel
     * opens automatically once the SCTP association is up;
     * {@link SfuPeerConnectionObserver#onDataChannelOpen(DataChannelTrack)} fires when
     * the remote side has acknowledged the channel.
     */
    public DataChannelTrack createDataChannel(String label, DataChannelOptions options)
    {
        DataChannelTrack track = new DataChannelTrack(this, label, options);
        synchronized (pendingLocalChannels)
        {
            if (!sctpConnected.get())
            {
                pendingLocalChannels.add(track);
                return track;
            }
        }
        openLocalChannel(track);
        return track;
    }

    /**
     * Registers a media stream to be received from the remote peer. The host provides the
     * SSRC, kind, and the negotiated payload types and header extensions (typically parsed
     * from the remote SDP). Received RTP for this SSRC is delivered to the returned track's
     * {@link MediaTrack#onRtpPacket} listener.
     *
     * @param kind audio or video.
     * @param ssrc the remote SSRC to receive.
     * @param payloadTypes the payload types negotiated for this media.
     * @param extensions the RTP header extensions negotiated for this media.
     * @return a receive {@link MediaTrack}.
     */
    public MediaTrack addReceiveTrack(
        MediaKind kind, long ssrc, List<PayloadType> payloadTypes, List<RtpExtension> extensions)
    {
        for (PayloadType payloadType : payloadTypes)
        {
            transceiver.addPayloadType(payloadType);
        }
        for (RtpExtension extension : extensions)
        {
            transceiver.addRtpExtension(extension);
        }
        transceiver.addReceiveSsrc(ssrc, kind.toMediaType());
        MediaTrack track = new MediaTrack(this, kind, ssrc, /* local = */ false);
        if (kind == MediaKind.VIDEO)
        {
            // Keep the source description so a send track can build a source projection for it.
            track.setSourceDesc(registerVideoReceiveSource(ssrc));
        }
        receiveTracks.put(ssrc, track);
        return track;
    }

    /**
     * Registers a minimal single-layer (non-simulcast) {@link MediaSourceDesc} for a video
     * receive SSRC and pushes the full accumulated set to the transceiver. Required for the
     * incoming video pipeline to resolve a packet's encoding (see {@link #videoReceiveSources}).
     */
    private synchronized MediaSourceDesc registerVideoReceiveSource(long ssrc)
    {
        RtpLayerDesc layer = new VpxRtpLayerDesc(0, 0, 0, RtpLayerDesc.NO_HEIGHT, RtpLayerDesc.NO_FRAME_RATE);
        RtpEncodingDesc encoding = new RtpEncodingDesc(ssrc, new RtpLayerDesc[] { layer });
        MediaSourceDesc source = new MediaSourceDesc(new RtpEncodingDesc[] { encoding }, id, "video-" + ssrc);
        videoReceiveSources.add(source);
        transceiver.setMediaSources(videoReceiveSources.toArray(new MediaSourceDesc[0]));
        return source;
    }

    /**
     * Creates a media stream to be sent to the remote peer. The host provides the local
     * SSRC, kind, and the payload types and header extensions to use (typically mirrored
     * into the local SDP). Use {@link MediaTrack#sendRtp} to send RTP on the returned track.
     *
     * @param kind audio or video.
     * @param ssrc the local SSRC to send with.
     * @param payloadTypes the payload types to use.
     * @param extensions the RTP header extensions to use.
     * @return a send {@link MediaTrack}.
     */
    public MediaTrack createSendTrack(
        MediaKind kind, long ssrc, List<PayloadType> payloadTypes, List<RtpExtension> extensions)
    {
        for (PayloadType payloadType : payloadTypes)
        {
            transceiver.addPayloadType(payloadType);
        }
        for (RtpExtension extension : extensions)
        {
            transceiver.addRtpExtension(extension);
        }
        transceiver.setLocalSsrc(kind.toMediaType(), ssrc);
        MediaTrack track = new MediaTrack(this, kind, ssrc, /* local = */ true);
        sendTracks.put(ssrc, track);
        // Adding an outbound stream after the connection is negotiated changes what the remote
        // must expect, so ask the host to re-offer (mirrors browser addTrack → negotiationneeded).
        signalRenegotiationNeeded();
        return track;
    }

    /**
     * Removes a send track previously created with {@link #createSendTrack}, stopping
     * outbound media on its SSRC and asking the host to re-signal without it (fires
     * {@link SfuPeerConnectionObserver#onRenegotiationNeeded()} if the connection is already
     * negotiated). The host must stop calling {@link MediaTrack#sendRtp}/{@link MediaTrack#forwardRtp}
     * on the track before removing it.
     *
     * @param track a send track from {@link #createSendTrack}.
     */
    public void removeSendTrack(MediaTrack track)
    {
        if (!track.isLocal())
        {
            throw new IllegalArgumentException("removeSendTrack requires a send track");
        }
        if (sendTracks.remove(track.getSsrc()) == null)
        {
            return;
        }
        signalRenegotiationNeeded();
    }

    /**
     * Removes a receive track previously registered with {@link #addReceiveTrack}: stops
     * dispatching its RTP, drops it from the media pipeline, and releases any forwarding
     * projection built from it. This is the host acting on the remote having stopped a stream
     * (or on its own re-offer), so it does <em>not</em> fire
     * {@link SfuPeerConnectionObserver#onRenegotiationNeeded()} — receive-side changes follow
     * from the remote's signalling, they do not originate a new offer from this side.
     *
     * @param track a receive track from {@link #addReceiveTrack}.
     */
    public void removeReceiveTrack(MediaTrack track)
    {
        if (track.isLocal())
        {
            throw new IllegalArgumentException("removeReceiveTrack requires a receive track");
        }
        long ssrc = track.getSsrc();
        receiveTracks.remove(ssrc);
        transceiver.removeReceiveSsrc(ssrc);
        // Drop the per-source forwarding projection (keyed by the source SSRC; see forwardRtp).
        sendProjections.remove(ssrc);
        MediaSourceDesc sourceDesc = track.getSourceDesc();
        if (sourceDesc != null)
        {
            synchronized (this)
            {
                if (videoReceiveSources.remove(sourceDesc))
                {
                    transceiver.setMediaSources(videoReceiveSources.toArray(new MediaSourceDesc[0]));
                }
            }
        }
    }

    /**
     * Sends an RTP packet to the remote peer (called by a send {@link MediaTrack}). The
     * packet is cloned into a pipeline-owned buffer, so the caller keeps ownership of theirs.
     */
    void sendRtp(RtpPacket packet)
    {
        transceiver.sendPacket(new PacketInfo(packet.clone()));
    }

    /**
     * Forwards a packet received on {@code source} out over {@code sendTrack} (called by
     * {@link MediaTrack#forwardRtp}). Video is projected onto the send track's SSRC through an
     * {@link AdaptiveSourceProjection} — the same class upstream's {@code Endpoint.preProcess} uses
     * — rewriting sequence numbers, timestamps and picture IDs into a clean, gap-free stream so the
     * relay is transparent to the receiver (a requirement in practice for Chrome). Because this is a
     * passthrough SFU with no bandwidth adaptation, the projection target is pinned to "forward every
     * temporal layer". Audio has no such requirement and is forwarded as-is with only its SSRC
     * rewritten.
     */
    void forwardRtp(MediaTrack sendTrack, RtpPacket packet, MediaTrack source)
    {
        long downlinkSsrc = sendTrack.getSsrc();
        if (sendTrack.getKind() == MediaKind.VIDEO
            && source.getSourceDesc() != null
            && packet instanceof VideoRtpPacket)
        {
            forwardVideoProjected(downlinkSsrc, (VideoRtpPacket) packet, source);
        }
        else
        {
            RtpPacket clone = packet.clone();
            clone.setSsrc(downlinkSsrc);
            transceiver.sendPacket(new PacketInfo(clone));
        }
    }

    private void forwardVideoProjected(long downlinkSsrc, VideoRtpPacket packet, MediaTrack source)
    {
        long sourceSsrc = source.getSsrc();
        AdaptiveSourceProjection projection = sendProjections.computeIfAbsent(sourceSsrc, s -> {
            AdaptiveSourceProjection created = new AdaptiveSourceProjection(
                diagnosticContext,
                source.getSourceDesc(),
                // When the projection needs a keyframe (e.g. before it can start), ask the upstream
                // source to emit one. Mirrors upstream BitrateController.keyframeNeeded.
                () -> source.getConnection().requestKeyFrame(sourceSsrc),
                logger);
            // Passthrough: forward all temporal layers (no bandwidth-driven layer selection).
            created.setTargetIndex(RtpLayerDesc.getIndex(0, 0, 7));
            return created;
        });

        PacketInfo packetInfo = new PacketInfo(packet.clone());
        // The projection drops packets until it has a keyframe to anchor the clean stream; it
        // requests one via the keyframe callback above, so dropping here is expected and recovers.
        if (!projection.accept(packetInfo))
        {
            return;
        }
        try
        {
            projection.rewriteRtp(packetInfo);
        }
        catch (RewriteException e)
        {
            logger.warn("Failed to project a forwarded RTP packet", e);
            return;
        }
        // Rewrite onto this connection's downlink SSRC (the projection rewrote to the source SSRC).
        ((RtpPacket) packetInfo.getPacket()).setSsrc(downlinkSsrc);
        transceiver.sendPacket(packetInfo);
    }

    /**
     * Sends an RTCP keyframe request (PLI) to the remote peer for the given media SSRC, asking its
     * encoder to emit a fresh keyframe. Use this when forwarding video so a downstream receiver
     * that missed the initial keyframe can recover (see {@link #setKeyFrameRequestHandler}).
     *
     * @param mediaSsrc the SSRC of the remote stream to request a keyframe for.
     */
    public void requestKeyFrame(long mediaSsrc)
    {
        transceiver.requestKeyFrame(null, mediaSsrc);
    }

    /**
     * Registers a handler invoked when the remote peer requests a keyframe (RTCP PLI/FIR) for an
     * SSRC we are sending. The handler receives that SSRC; a forwarding host typically maps it to
     * the upstream source and calls {@link #requestKeyFrame} on the connection carrying it.
     */
    public void setKeyFrameRequestHandler(LongConsumer handler)
    {
        this.keyFrameRequestHandler = handler;
    }

    /**
     * Returns a JSON-friendly snapshot of this connection's transports, for
     * diagnostics.
     */
    public com.fasterxml.jackson.databind.node.ObjectNode getDebugState()
    {
        com.fasterxml.jackson.databind.node.ObjectNode node =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        node.put("id", id);
        node.put("role", role.toString());
        node.set("ice_transport", iceTransport.getDebugState());
        node.set("dtls_transport", dtlsTransport.getDebugState());
        node.set("sctp", sctpTransport.getDebugState());
        node.set("transceiver", transceiver.getTransceiverStats().toJson());
        if (!sendProjections.isEmpty())
        {
            com.fasterxml.jackson.databind.node.ObjectNode proj =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            sendProjections.forEach((ssrc, p) ->
                proj.set(Long.toString(ssrc), p.getDebugState(org.jitsi.nlj.DebugStateMode.FULL)));
            node.set("send_projections", proj);
        }
        return node;
    }

    /**
     * Tears the connection down: stops SCTP, DTLS and ICE and fires
     * {@link SfuPeerConnectionObserver#onClosed()}. Idempotent.
     */
    @Override
    public void close()
    {
        if (!closed.compareAndSet(false, true))
        {
            return;
        }
        logger.info("Closing");
        try
        {
            transceiver.stop();
            transceiver.teardown();
            sctpTransport.stop();
            dtlsTransport.stop();
            iceTransport.stop();
            incomingDataChannelMessagesQueue.close();
        }
        catch (Throwable t)
        {
            logger.error("Error closing peer connection", t);
        }
        observer.onIceConnectionStateChange(IceConnectionState.CLOSED);
        observer.onClosed();
    }

    // ------------------------------------------------------------------
    // Internal wiring (mirrors upstream Endpoint.setupIceTransport etc.)
    // ------------------------------------------------------------------

    private void setupIceTransport()
    {
        iceTransport.incomingDataHandler = buffer -> {
            if (PacketUtils.looksLikeDtls(buffer.getBuffer(), buffer.getOffset(), buffer.getLength()))
            {
                // DTLS transport is responsible for making its own copy, because it will
                // manage its own buffers.
                dtlsTransport.enqueueBuffer(buffer);
            }
            else
            {
                // SRTP/media: hand to the transceiver's receive pipeline (it decrypts, parses,
                // and recycles the buffer, mirroring upstream Endpoint's media handling).
                transceiver.handleIncomingPacket(new PacketInfo(
                    new UnparsedPacket(buffer.getBuffer(), buffer.getOffset(), buffer.getLength())));
            }
        };
        iceTransport.eventHandler = new IceTransport.EventHandler()
        {
            @Override
            public void writeable()
            {
                logger.info("ICE writeable");
                TaskPools.IO_POOL.execute(dtlsTransport::startDtlsHandshake);
            }

            @Override
            public void connected()
            {
                logger.info("ICE connected");
                observer.onIceConnectionStateChange(IceConnectionState.CONNECTED);
            }

            @Override
            public void failed()
            {
                logger.warn("ICE failed");
                observer.onIceConnectionStateChange(IceConnectionState.FAILED);
                observer.onDisconnected();
            }

            @Override
            public void consentUpdated(Instant time)
            {
                // No media pipeline activity tracking in this slice.
            }
        };
    }

    private void setupDtlsTransport()
    {
        dtlsTransport.incomingDataHandler = this::dtlsAppPacketReceived;
        dtlsTransport.outgoingDataHandler = iceTransport::send;
        dtlsTransport.eventHandler = new DtlsTransport.EventHandler()
        {
            @Override
            public void handshakeComplete(int chosenSrtpProtectionProfile, TlsRole tlsRole, byte[] keyingMaterial)
            {
                logger.info("DTLS handshake complete");
                SfuPeerConnection.this.tlsRole = tlsRole;
                // Hand the negotiated SRTP profile + keying material to the media pipeline so the
                // receiver/sender can decrypt/encrypt. (cryptex is not negotiated in this slice.)
                transceiver.setSrtpInformation(chosenSrtpProtectionProfile, tlsRole, keyingMaterial, false);
                if (tlsRole == TlsRole.CLIENT)
                {
                    // The DTLS client initiates the SCTP association (mirrors upstream Relay;
                    // when we are the server, the remote peer connects to us).
                    sctpTransport.connect();
                }
                observer.onConnected();
            }

            @Override
            public void handshakeFailed(Throwable t)
            {
                logger.error("DTLS handshake failed", t);
                observer.onDtlsError(t);
            }
        };
    }

    /**
     * Handles a decrypted DTLS application data packet — in this slice, always SCTP.
     * (Mirrors {@code Endpoint.dtlsAppPacketReceived}.)
     */
    private void dtlsAppPacketReceived(byte[] data, int off, int len)
    {
        sctpHandler.processPacket(new PacketInfo(new UnparsedPacket(data, off, len)));
    }

    /**
     * Terminal handler for fully-received (SRTP-decrypted, parsed) RTP/RTCP from the peer.
     * Dispatches RTP to the receive {@link MediaTrack} registered for its SSRC (if any); the
     * packet is valid only for the duration of the listener call, after which its buffer is
     * returned to the pool. RTCP and unmatched packets are dropped.
     */
    private void handleReceivedMediaPacket(PacketInfo packetInfo)
    {
        Packet packet = packetInfo.getPacket();
        try
        {
            if (packet instanceof RtpPacket)
            {
                MediaTrack track = receiveTracks.get(((RtpPacket) packet).getSsrc());
                if (track != null)
                {
                    track.dispatch((RtpPacket) packet);
                }
            }
            else if (packet instanceof RtcpFbPliPacket || packet instanceof RtcpFbFirPacket)
            {
                // The remote is asking for a keyframe on a stream we're sending. Surface the
                // requested SSRC so a forwarding host can request one from the upstream source.
                LongConsumer handler = keyFrameRequestHandler;
                if (handler != null)
                {
                    handler.accept(((RtcpFbPacket) packet).getMediaSourceSsrc());
                }
            }
        }
        catch (Throwable t)
        {
            logger.warn("Exception dispatching received RTP packet", t);
        }
        finally
        {
            ByteBufferPool.returnBuffer(packet.getBuffer());
        }
    }

    /**
     * Handler for the sequential incoming data channel message queue.
     */
    private boolean processDataChannelPacket(PacketInfo packetInfo)
    {
        DataChannelPacket packet = (DataChannelPacket) packetInfo.getPacket();
        dataChannelStack.onIncomingDataChannelPacket(
            ByteBuffer.wrap(packet.getBuffer()), packet.sid, packet.ppid);
        return true;
    }

    /**
     * Called (from the data channel message queue) when the remote peer opens a data
     * channel.
     */
    private void dataChannelOpenedRemotely(DataChannel dataChannel)
    {
        logger.info("Remote side opened a data channel: " + dataChannel.getLabel());
        DataChannelTrack track = new DataChannelTrack(this, dataChannel);
        registerChannelListeners(track, dataChannel);
        observer.onDataChannel(track);
    }

    /**
     * Allocates a stream id and opens a locally-created channel. Only call once the
     * SCTP association is established (the DTLS role determines sid parity: the DTLS
     * client uses even stream ids, the server odd ones — RFC 8832).
     */
    private void openLocalChannel(DataChannelTrack track)
    {
        int parity = (tlsRole == TlsRole.CLIENT) ? 0 : 1;
        int sid = 2 * nextLocalChannelIndex.getAndIncrement() + parity;
        DataChannelOptions options = track.options();
        DataChannel dataChannel = dataChannelStack.createDataChannel(
            options.channelType(),
            options.getPriority(),
            options.reliability(),
            sid,
            track.getLabel());
        registerChannelListeners(track, dataChannel);
        track.setChannel(dataChannel);
        dataChannel.open();
    }

    private void registerChannelListeners(DataChannelTrack track, DataChannel dataChannel)
    {
        dataChannel.onDataChannelEvents(() -> observer.onDataChannelOpen(track));
        dataChannel.onDataChannelMessage(message -> {
            if (message instanceof DataChannelStringMessage)
            {
                observer.onDataChannelStringMessage(track, ((DataChannelStringMessage) message).data);
            }
            else if (message instanceof DataChannelBinaryMessage)
            {
                observer.onDataChannelBinaryMessage(track, ((DataChannelBinaryMessage) message).data);
            }
            // DCEP control messages (open/ack) are handled by the stack/event listener.
        });
    }

    /**
     * SCTP socket callbacks: bridges the SCTP socket to the DTLS transport (outgoing)
     * and the data channel stack (incoming). Mirrors {@code Endpoint.SctpCallbacks}.
     */
    private class SctpCallbacks extends DcSctpBaseCallbacks
    {
        SctpCallbacks(DcSctpTransport transport)
        {
            super(transport);
        }

        @Override
        public SendPacketStatus sendPacketWithStatus(byte[] packet)
        {
            try
            {
                byte[] newBuf = ByteBufferPool.getBuffer(packet.length);
                System.arraycopy(packet, 0, newBuf, 0, packet.length);
                dtlsTransport.sendDtlsData(newBuf, 0, packet.length);
                return SendPacketStatus.kSuccess;
            }
            catch (Throwable e)
            {
                logger.warn("Exception sending SCTP packet", e);
                return SendPacketStatus.kError;
            }
        }

        @Override
        public void OnMessageReceived(DcSctpMessage message)
        {
            try
            {
                // We assume all data coming over SCTP will be datachannel data.
                DataChannelPacket packet = new DataChannelPacket(message);
                // Post the rest of the task on the queue because the current context is
                // holding a lock inside the SctpSocket which can cause a deadlock if both
                // peers send data channel messages to each other at the same time.
                incomingDataChannelMessagesQueue.add(new PacketInfo(packet));
            }
            catch (Throwable e)
            {
                logger.warn("Exception processing SCTP message", e);
            }
        }

        @Override
        public void OnError(ErrorKind error, String message)
        {
            logger.warn("SCTP error " + error + ": " + message);
        }

        @Override
        public void OnAborted(ErrorKind error, String message)
        {
            logger.info("SCTP aborted with error " + error + ": " + message);
            observer.onError(new RuntimeException("SCTP aborted with error " + error + ": " + message));
        }

        @Override
        public void OnConnected()
        {
            try
            {
                logger.info("SCTP connection is ready");
                List<DataChannelTrack> toOpen;
                synchronized (pendingLocalChannels)
                {
                    sctpConnected.set(true);
                    toOpen = new ArrayList<>(pendingLocalChannels);
                    pendingLocalChannels.clear();
                }
                for (DataChannelTrack track : toOpen)
                {
                    openLocalChannel(track);
                }
            }
            catch (Throwable e)
            {
                logger.warn("Exception processing SCTP connected event", e);
            }
        }

        @Override
        public void OnClosed()
        {
            logger.info("SCTP connection closed");
        }
    }
}
