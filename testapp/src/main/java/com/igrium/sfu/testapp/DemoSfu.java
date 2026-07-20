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

    /** Local (server-chosen) SSRCs for the loopback send tracks; fits a 32-bit unsigned RTP SSRC. */
    private static final long LOCAL_AUDIO_SSRC = 0x1BADB002L;
    private static final long LOCAL_VIDEO_SSRC = 0x1BADB003L;

    private static final class Peer
    {
        final String id;
        final SfuPeerConnection connection;
        volatile DataChannelTrack track;
        /** The browser's video source SSRC, so a looped-back keyframe request can be mapped back to it. */
        volatile long remoteVideoSsrc = -1;
        /** Count of RTP packets forwarded (looped back) for this peer, exposed at {@code /debug}. */
        final AtomicLong forwardedRtpPackets = new AtomicLong();

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

        // When the browser (as receiver of our looped-back video) asks for a keyframe, forward the
        // request to its own encoder by requesting a keyframe on the source SSRC. Without this a
        // receiver that misses the browser's single initial keyframe stays stuck on a black frame.
        connection.setKeyFrameRequestHandler(requestedSsrc -> {
            long source = peer.remoteVideoSsrc;
            if (source >= 0)
            {
                connection.requestKeyFrame(source);
            }
        });

        connection.setRemoteDescription(offer.transport);

        List<SdpUtils.MediaAnswer> mediaAnswers = new ArrayList<>();
        for (SdpUtils.MediaDescription md : offer.mediaDescriptions)
        {
            if ("audio".equals(md.kind) || "video".equals(md.kind))
            {
                SdpUtils.MediaAnswer mediaAnswer = setupMediaLoopback(peer, md);
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
     * Registers a receive track for the browser's SSRC on this media section and a send track
     * with our own SSRC, and wires the receive track's RTP straight back out the send track —
     * the media analog of {@link #relayString}/{@link #relayBinary}, looped back to the same
     * peer so a single browser is enough to prove RTP forwards end-to-end.
     *
     * @return our answer for this section, or null if we can't/won't accept it (e.g. no
     *         supported codec offered), in which case the section is rejected in the SDP answer.
     */
    private SdpUtils.MediaAnswer setupMediaLoopback(Peer peer, SdpUtils.MediaDescription offered)
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
        if (kind == MediaKind.VIDEO)
        {
            peer.remoteVideoSsrc = remoteSsrc;
        }

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
        receiveTrack.onRtpPacket(packet -> {
            // Loopback: re-stamp with our own send SSRC (the one we declare in the SDP answer)
            // and hand it straight back out, undecoded/unmodified otherwise.
            packet.setSsrc(localSsrc);
            sendTrack.sendRtp(packet);
            peer.forwardedRtpPackets.incrementAndGet();
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
        }

        @Override
        public void onDataChannel(DataChannelTrack track)
        {
            System.out.println("[" + peerId + "] data channel opened: " + track.getLabel());
            Peer peer = peers.get(peerId);
            if (peer != null)
            {
                peer.track = track;
            }
        }

        @Override
        public void onDataChannelStringMessage(DataChannelTrack track, String message)
        {
            System.out.println("[" + peerId + "] message: " + message);
            relayString(peerId, message);
        }

        @Override
        public void onDataChannelBinaryMessage(DataChannelTrack track, byte[] message)
        {
            relayBinary(peerId, message);
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
