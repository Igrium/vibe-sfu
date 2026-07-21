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

package com.igrium.sfu.testapp;

import com.igrium.sfu.DataChannelTrack;
import com.igrium.sfu.IceConnectionState;
import com.igrium.sfu.MediaKind;
import com.igrium.sfu.MediaTrack;
import com.igrium.sfu.SfuPeerConnection;
import com.igrium.sfu.SfuPeerConnectionObserver;
import com.igrium.sfu.sdp.SdpUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jitsi.nlj.format.Av1PayloadType;
import org.jitsi.nlj.format.H264PayloadType;
import org.jitsi.nlj.format.OpusPayloadType;
import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.format.Vp8PayloadType;
import org.jitsi.nlj.format.Vp9PayloadType;
import org.jitsi.nlj.rtp.RtpExtension;
import org.jitsi.nlj.rtp.RtpExtensionType;
import org.jitsi.videobridge.transport.TransportDescription;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A minimal demo SFU built on the jitsi-sfu library.
 *
 * <p>Serves a browser test client at {@code http://localhost:8080/} and accepts
 * WebRTC (data-channel-only) offers at {@code POST /offer}. Every string/binary
 * message received on any peer's data channel is relayed to all other connected
 * peers — the smallest possible "selective forwarding" demo.
 */
public final class DemoSfu
{
    private static final int HTTP_PORT = Integer.getInteger("demo.http.port", 8080);

    /** Connected peers by id. */
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final AtomicInteger nextPeerId = new AtomicInteger(1);

    /** Drives the join-time keyframe requests and the periodic connection-status sweep. */
    private final java.util.concurrent.ScheduledExecutorService scheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "demo-sfu-scheduler");
            t.setDaemon(true);
            return t;
        });

    // Connection-status thresholds, mirroring jvb's ep-connection-status defaults
    // (EndpointConnectionStatusConfig): give a peer this long to *start* sending before we give up
    // on it, then drop it once it goes quiet for longer than the inactivity limit. Checked on a
    // fixed interval. Adapted from jvb's EndpointConnectionStatusMonitor: jvb only *flags* inactive
    // endpoints (removal comes later via Colibri signaling); this demo has no such signal, so a
    // peer that goes inactive is simply expired and removed.
    private static final long FIRST_TRANSFER_TIMEOUT_MS = 15_000;
    private static final long MAX_INACTIVITY_LIMIT_MS = 8_000;
    private static final long STATUS_CHECK_INTERVAL_MS = 500;

    /**
     * SSRCs the SFU uses for the downlink it sends to each peer (the media forwarded <em>from</em>
     * the other peer). One audio + one video SSRC per peer is enough for the two-peer case; fits a
     * 32-bit unsigned RTP SSRC.
     */
    private static final long LOCAL_AUDIO_SSRC = 0x1BADB002L;
    private static final long LOCAL_VIDEO_SSRC = 0x1BADB003L;

    private static final class Peer
    {
        final String id;
        final SfuPeerConnection connection;
        volatile DataChannelTrack track;
        /** The browser's uploaded video source SSRC, so keyframe requests can be routed back to it. */
        volatile long videoSourceSsrc = -1;
        /** The downlink tracks the SFU sends to this peer (carrying the other peer's media). */
        volatile MediaTrack audioSendTrack;
        volatile MediaTrack videoSendTrack;
        /** Count of RTP packets forwarded to this peer, exposed at {@code /debug}. */
        final AtomicLong forwardedRtpPackets = new AtomicLong();
        /** Wall-clock ms when this peer was created, for the first-transfer timeout. */
        final long creationMs = System.currentTimeMillis();
        /** Wall-clock ms of the last packet received from this peer; 0 means "never" (jvb's NEVER). */
        volatile long lastIncomingActivityMs = 0;

        Peer(String id, SfuPeerConnection connection)
        {
            this.id = id;
            this.connection = connection;
        }
    }

    public static void main(String[] args) throws Exception
    {
        new DemoSfu().start();
    }

    private void start() throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::handleIndex);
        server.createContext("/offer", this::handleOffer);
        server.createContext("/debug", this::handleDebug);
        server.start();
        scheduler.scheduleWithFixedDelay(this::monitorConnectionStatus,
            STATUS_CHECK_INTERVAL_MS, STATUS_CHECK_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        System.out.println("Demo SFU listening on http://localhost:" + HTTP_PORT + "/");
    }

    private void handleIndex(HttpExchange exchange) throws IOException
    {
        if (!"GET".equals(exchange.getRequestMethod()))
        {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        byte[] body;
        try (InputStream in = DemoSfu.class.getResourceAsStream("/web/index.html"))
        {
            body = in.readAllBytes();
        }
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(body);
        }
    }

    /** Dumps the debug state of all connected peers as JSON. */
    private void handleDebug(HttpExchange exchange) throws IOException
    {
        com.fasterxml.jackson.databind.node.ObjectNode root =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        for (Peer peer : peers.values())
        {
            com.fasterxml.jackson.databind.node.ObjectNode node = peer.connection.getDebugState();
            node.put("forwarded_rtp_packets", peer.forwardedRtpPackets.get());
            root.set(peer.id, node);
        }
        byte[] body = root.toPrettyString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(body);
        }
    }

    /**
     * Accepts a browser offer (SDP as the request body) and responds with our SDP
     * answer.
     */
    private void handleOffer(HttpExchange exchange) throws IOException
    {
        if (!"POST".equals(exchange.getRequestMethod()))
        {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        try
        {
            String offerSdp = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String answerSdp = connectPeer(offerSdp);
            byte[] body = answerSdp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/sdp");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody())
            {
                out.write(body);
            }
        }
        catch (Exception e)
        {
            System.err.println("Failed to handle offer: " + e);
            e.printStackTrace();
            byte[] body = String.valueOf(e).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody())
            {
                out.write(body);
            }
        }
    }

    /**
     * Creates a new peer connection (as the answerer) for a browser offer and
     * returns the SDP answer.
     */
    private String connectPeer(String offerSdp)
    {
        String peerId = "peer-" + nextPeerId.getAndIncrement();
        SdpUtils.ParsedSdp offer = SdpUtils.parse(offerSdp);

        SfuPeerConnection connection = new SfuPeerConnection(
            peerId,
            SfuPeerConnection.Role.ANSWERER,
            new Observer(peerId),
            new org.jitsi.utils.logging2.LoggerImpl(DemoSfu.class.getName()));
        Peer peer = new Peer(peerId, connection);
        peers.put(peerId, peer);

        // When this peer (a receiver of forwarded video) asks for a keyframe on its downlink, route
        // the request to the source peer(s) feeding it, so their encoder emits a fresh keyframe.
        // Mirrors jvb's BitrateController.keyframeNeeded. Without it a receiver that joins mid-stream
        // (or drops a packet) stays stuck on a black frame.
        connection.setKeyFrameRequestHandler(requestedSsrc -> {
            for (Peer source : peers.values())
            {
                if (source != peer && source.videoSourceSsrc >= 0)
                {
                    source.connection.requestKeyFrame(source.videoSourceSsrc);
                }
            }
        });

        connection.setRemoteDescription(offer.transport);

        List<SdpUtils.MediaAnswer> mediaAnswers = new ArrayList<>();
        for (SdpUtils.MediaDescription md : offer.mediaDescriptions)
        {
            if ("audio".equals(md.kind) || "video".equals(md.kind))
            {
                SdpUtils.MediaAnswer mediaAnswer = setupMediaForward(peer, md);
                if (mediaAnswer != null)
                {
                    mediaAnswers.add(mediaAnswer);
                }
            }
        }

        TransportDescription local = connection.getLocalDescription();
        String answer = SdpUtils.buildAnswer(local, offer, mediaAnswers);
        System.out.println("[" + peerId + "] answering offer (" + offer.transport.candidates.size()
            + " remote candidates, " + local.candidates.size() + " local candidates, "
            + mediaAnswers.size() + " media sections accepted)");
        return answer;
    }

    /**
     * Sets up one media section for a peer: a receive track for the browser's uploaded SSRC, and a
     * downlink send track (with the SFU's own SSRC, declared in the answer) carrying the media
     * forwarded <em>from the other peers</em>. Received RTP is re-stamped onto each other peer's
     * matching downlink and sent out — a real selective-forwarding path (A's camera → B, B → A),
     * not a self-loopback. The RTP is forwarded raw: no decode/re-encode.
     *
     * @return our answer for this section, or null if we can't/won't accept it (e.g. no
     *         supported codec offered), in which case the section is rejected in the SDP answer.
     */
    private SdpUtils.MediaAnswer setupMediaForward(Peer peer, SdpUtils.MediaDescription offered)
    {
        MediaKind kind = "audio".equals(offered.kind) ? MediaKind.AUDIO : MediaKind.VIDEO;
        String codecName = kind == MediaKind.AUDIO ? "opus" : "VP8";
        SdpUtils.Codec offeredCodec = offered.findCodec(codecName);
        if (offeredCodec == null || offered.ssrcs.isEmpty())
        {
            System.out.println("[" + peer.id + "] rejecting " + offered.kind + " (mid " + offered.mid
                + "): no usable " + codecName + " codec or ssrc offered");
            return null;
        }
        PayloadType payloadType = toPayloadType(offeredCodec);
        if (payloadType == null)
        {
            return null;
        }
        long remoteSsrc = offered.ssrcs.get(0);
        long localSsrc = kind == MediaKind.AUDIO ? LOCAL_AUDIO_SSRC : LOCAL_VIDEO_SSRC;

        List<RtpExtension> extensions = new ArrayList<>();
        List<SdpUtils.Extmap> answeredExtmaps = new ArrayList<>();
        for (SdpUtils.Extmap extmap : offered.extmaps)
        {
            RtpExtensionType type = RtpExtensionType.createFromUri(extmap.uri);
            if (type != null)
            {
                extensions.add(new RtpExtension((byte) extmap.id, type));
                answeredExtmaps.add(extmap);
            }
        }

        List<PayloadType> payloadTypes = List.of(payloadType);
        MediaTrack receiveTrack = peer.connection.addReceiveTrack(kind, remoteSsrc, payloadTypes, extensions);
        MediaTrack sendTrack = peer.connection.createSendTrack(kind, localSsrc, payloadTypes, extensions);
        if (kind == MediaKind.AUDIO)
        {
            peer.audioSendTrack = sendTrack;
        }
        else
        {
            peer.videoSendTrack = sendTrack;
            peer.videoSourceSsrc = remoteSsrc;
        }
        receiveTrack.onRtpPacket(packet -> {
            peer.lastIncomingActivityMs = System.currentTimeMillis();
            forwardToOtherPeers(peer, kind, receiveTrack, packet);
        });

        SdpUtils.MediaAnswer answer = new SdpUtils.MediaAnswer(offered.mid, offered.kind);
        answer.direction = "sendrecv";
        answer.codecs.add(offeredCodec);
        answer.extmaps.addAll(answeredExtmaps);
        answer.ssrcs.add(localSsrc);
        answer.cname = "sfu-" + peer.id;
        return answer;
    }

    /**
     * Periodic connection-status sweep, modeled on jvb's {@code EndpointConnectionStatusMonitor}: a
     * peer that never starts sending (within {@link #FIRST_TRANSFER_TIMEOUT_MS}) or that goes quiet
     * for longer than {@link #MAX_INACTIVITY_LIMIT_MS} is considered gone and expired. This is what
     * removes a peer whose browser closed after ICE fully completed — that path surfaces no ICE
     * failure (see IceTransport: a post-completion {@code COMPLETED -> TERMINATED} is normal, not a
     * disconnect), so without this sweep such peers would linger as ghosts.
     */
    private void monitorConnectionStatus()
    {
        long now = System.currentTimeMillis();
        for (Peer peer : peers.values())
        {
            long lastActivity = peer.lastIncomingActivityMs;
            boolean expired;
            if (lastActivity == 0)
            {
                expired = now - peer.creationMs > FIRST_TRANSFER_TIMEOUT_MS;
            }
            else
            {
                expired = now - lastActivity > MAX_INACTIVITY_LIMIT_MS;
            }
            if (expired && peers.remove(peer.id, peer))
            {
                System.out.println("[" + peer.id + "] expired (no activity); closing");
                peer.connection.close();
            }
        }
    }

    /** Requests a keyframe from every connected peer's video source. */
    private void requestKeyframesFromAll()
    {
        for (Peer p : peers.values())
        {
            if (p.videoSourceSsrc >= 0)
            {
                p.connection.requestKeyFrame(p.videoSourceSsrc);
            }
        }
    }

    /**
     * Forwards a received RTP packet to every other connected peer's matching downlink track. Each
     * destination's send track re-stamps the packet onto the SFU's downlink SSRC; for video it also
     * projects the source into a clean, gap-free stream (see {@link MediaTrack#forwardRtp}) so the
     * relay is decodable across browsers — forwarding raw sequence numbers works in Firefox but not
     * Chrome. The packet is only valid during this call, but {@code forwardRtp} clones it, so
     * forwarding per destination is safe. (Two peers is the intended case; with three or more,
     * sources would collide on the single downlink SSRC — a demo limitation, not a library one.)
     */
    private void forwardToOtherPeers(Peer from, MediaKind kind, MediaTrack source,
        org.jitsi.rtp.rtp.RtpPacket packet)
    {
        for (Peer to : peers.values())
        {
            if (to == from)
            {
                continue;
            }
            MediaTrack dst = kind == MediaKind.AUDIO ? to.audioSendTrack : to.videoSendTrack;
            if (dst != null)
            {
                dst.forwardRtp(packet, source);
                to.forwardedRtpPackets.incrementAndGet();
            }
        }
    }

    /**
     * Maps a plain {@link SdpUtils.Codec} (as parsed from a browser offer) to the nlj
     * {@link PayloadType} the media pipeline understands. This mapping intentionally lives here
     * (in the testapp), not in {@code SdpUtils} — see the library's "don't over-integrate SDP"
     * rule. Only the codecs this demo actually uses are mapped; others (red, rtx, cn,
     * telephone-event, ...) return null and are skipped.
     */
    private static PayloadType toPayloadType(SdpUtils.Codec codec)
    {
        String name = codec.name == null ? "" : codec.name.toLowerCase(Locale.ROOT);
        byte pt = (byte) codec.pt;
        Map<String, String> params = new HashMap<>(codec.fmtp);
        switch (name)
        {
            case "opus":
                int clockRate = codec.clockRate > 0 ? codec.clockRate : 48000;
                int channels = codec.channels > 0 ? codec.channels : 2;
                return new OpusPayloadType(pt, clockRate, channels, params);
            case "vp8":
                return new Vp8PayloadType(pt, params);
            case "vp9":
                return new Vp9PayloadType(pt, params);
            case "h264":
                return new H264PayloadType(pt, params);
            case "av1":
                return new Av1PayloadType(pt, params);
            default:
                return null;
        }
    }

    /** Relays a message from one peer to all other peers with an open channel. */
    private void relayString(String fromPeerId, String message)
    {
        for (Peer other : peers.values())
        {
            DataChannelTrack track = other.track;
            if (!other.id.equals(fromPeerId) && track != null && track.isOpen())
            {
                track.sendString(message);
            }
        }
    }

    private void relayBinary(String fromPeerId, byte[] message)
    {
        for (Peer other : peers.values())
        {
            DataChannelTrack track = other.track;
            if (!other.id.equals(fromPeerId) && track != null && track.isOpen())
            {
                track.sendBinary(message);
            }
        }
    }

    private class Observer implements SfuPeerConnectionObserver
    {
        private final String peerId;

        Observer(String peerId)
        {
            this.peerId = peerId;
        }

        @Override
        public void onIceConnectionStateChange(IceConnectionState state)
        {
            System.out.println("[" + peerId + "] ICE state: " + state);
        }

        @Override
        public void onConnected()
        {
            System.out.println("[" + peerId + "] connected (DTLS established)");
            // A newly-connected peer begins sending and receiving now. Ask every peer for a fresh
            // keyframe (a few times, to cover the setup race) so each receiver gets a decodable
            // keyframe promptly instead of waiting on the encoder's periodic one. Mirrors jvb
            // requesting a keyframe when it starts forwarding a source to a receiver.
            for (long delayMs : new long[] { 0, 500, 1200 })
            {
                scheduler.schedule(DemoSfu.this::requestKeyframesFromAll,
                    delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }

        @Override
        public void onDataChannel(DataChannelTrack track)
        {
            System.out.println("[" + peerId + "] data channel opened: " + track.getLabel());
            Peer peer = peers.get(peerId);
            if (peer != null)
            {
                peer.track = track;
                // Opening the channel counts as activity, so a data-only peer isn't expired before
                // its first message arrives.
                peer.lastIncomingActivityMs = System.currentTimeMillis();
            }
        }

        @Override
        public void onDataChannelStringMessage(DataChannelTrack track, String message)
        {
            System.out.println("[" + peerId + "] message: " + message);
            stampActivity();
            relayString(peerId, message);
        }

        @Override
        public void onDataChannelBinaryMessage(DataChannelTrack track, byte[] message)
        {
            stampActivity();
            relayBinary(peerId, message);
        }

        private void stampActivity()
        {
            Peer peer = peers.get(peerId);
            if (peer != null)
            {
                peer.lastIncomingActivityMs = System.currentTimeMillis();
            }
        }

        @Override
        public void onDisconnected()
        {
            System.out.println("[" + peerId + "] disconnected");
            Peer peer = peers.remove(peerId);
            if (peer != null)
            {
                peer.connection.close();
            }
        }

        @Override
        public void onClosed()
        {
            peers.remove(peerId);
        }

        @Override
        public void onError(Throwable t)
        {
            System.err.println("[" + peerId + "] error: " + t);
        }
    }
}
