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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igrium.sfu.DataChannelTrack;
import com.igrium.sfu.IceConnectionState;
import com.igrium.sfu.MediaDirection;
import com.igrium.sfu.MediaKind;
import com.igrium.sfu.MediaTrack;
import com.igrium.sfu.MediaTransceiver;
import com.igrium.sfu.SfuPeerConnection;
import com.igrium.sfu.SfuPeerConnectionObserver;
import com.igrium.sfu.sdp.SdpUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jitsi.nlj.format.OpusPayloadType;
import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.format.Vp8PayloadType;
import org.jitsi.nlj.rtp.RtpExtension;
import org.jitsi.nlj.rtp.RtpExtensionType;
import org.jitsi.rtp.rtp.RtpPacket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A dynamic multi-party conference SFU, demonstrating the library's
 * {@link MediaTransceiver} abstraction and <em>SFU-initiated</em> signalling.
 *
 * <p>Where {@link DemoSfu} is the answerer — it can only describe media the browser already
 * offered, so it needs the browser to re-offer whenever the set of streams changes — this demo
 * inverts the roles: the <b>SFU is always the offerer</b>. It declares its own media sections up
 * front with {@link SfuPeerConnection#addTransceiver}, so a participant's very first offer already
 * carries a downlink for every other participant in the room, and later joins/leaves are published
 * by re-offering over the SFU's own data channel. The browser never originates negotiation, which
 * means glare is impossible by construction.
 *
 * <p>Per participant the SFU offers:
 * <ul>
 *   <li>one {@code m=application} section for a data channel the <em>SFU</em> creates (signalling
 *       + chat) — also something only an offerer can put in the initial offer;</li>
 *   <li>two {@link MediaDirection#RECVONLY} sections (the uplink: this participant's mic/camera);</li>
 *   <li>two {@link MediaDirection#SENDONLY} sections per <em>other</em> participant (their
 *       downlink), tagged with an {@code msid} stream id equal to that participant's id so the
 *       browser groups their audio and video into one {@code MediaStream}.</li>
 * </ul>
 *
 * <p>When someone joins, every existing participant gains a downlink pair and gets
 * {@link SfuPeerConnectionObserver#onRenegotiationNeeded()}; the fresh offer goes out over their
 * data channel and the answer comes back the same way. When someone leaves, their downlink
 * transceivers are {@link MediaTransceiver#stop() stopped} — the section stays in place, rejected,
 * as JSEP requires — and the same re-offer path runs. Any number of participants can come and go.
 *
 * <p>Serves HTTP on port 8081 by default ({@link DemoSfu} uses 8080). Both demos harvest the same
 * single ICE UDP port (10000), so run one at a time.
 */
public final class ConferenceSfu
{
    private static final int HTTP_PORT = Integer.getInteger("conference.http.port", 8081);

    /**
     * The codecs and header extension ids the SFU offers. Because the SFU is the offerer, <em>it</em>
     * picks the payload type numbers and extension ids and the browser must use them — the reverse
     * of {@link DemoSfu}, which adopts whatever the browser offered. All sections are BUNDLEd onto
     * one transport, so extension ids must be unique across audio and video alike.
     */
    private static final byte OPUS_PT = 111;
    private static final byte VP8_PT = 96;
    private static final int EXT_AUDIO_LEVEL_ID = 1;
    private static final int EXT_ABS_SEND_TIME_ID = 2;
    private static final int EXT_TRANSPORT_CC_ID = 3;

    /** Feedback we advertise (and the pipeline implements) for video. */
    private static final Set<String> VIDEO_RTCP_FB =
        Set.of("ccm fir", "nack", "nack pli", "goog-remb", "transport-cc");
    private static final Set<String> AUDIO_RTCP_FB = Set.of("transport-cc");

    // Connection-status thresholds, as in DemoSfu (modelled on jvb's EndpointConnectionStatusMonitor):
    // a participant that never starts sending, or that goes quiet, is expired and removed.
    private static final long FIRST_TRANSFER_TIMEOUT_MS = 20_000;
    private static final long MAX_INACTIVITY_LIMIT_MS = 8_000;
    private static final long STATUS_CHECK_INTERVAL_MS = 500;

    private final Map<String, Participant> participants = new ConcurrentHashMap<>();
    private final AtomicInteger nextParticipantId = new AtomicInteger(1);
    private final ObjectMapper json = new ObjectMapper();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "conference-sfu-scheduler");
        t.setDaemon(true);
        return t;
    });

    /** The pair of send-side transceivers carrying one other participant's media to a participant. */
    private static final class Downlink
    {
        final MediaTransceiver audio;
        final MediaTransceiver video;

        Downlink(MediaTransceiver audio, MediaTransceiver video)
        {
            this.audio = audio;
            this.video = video;
        }
    }

    private static final class Participant
    {
        final String id;
        final SfuPeerConnection connection;
        /** The mid of the data channel section, reserved before any media section (so it is mid 0). */
        final String dataChannelMid;
        /** The SFU-created data channel used for renegotiation and chat. */
        final DataChannelTrack signalChannel;
        /** This participant's mic/camera, as offered to them (recvonly from the SFU's side). */
        final MediaTransceiver uplinkAudio;
        final MediaTransceiver uplinkVideo;
        /** Downlinks by source participant id — the streams this participant receives. */
        final Map<String, Downlink> downlinks = new ConcurrentHashMap<>();
        /** True once the answer has been applied and the uplink SSRCs are bound. */
        volatile boolean published = false;
        /**
         * Set when a renegotiation was needed but the signalling channel wasn't open yet (a
         * downlink can be added in the window between the answer and SCTP coming up). Drained when
         * the channel opens, so no offer is lost.
         */
        volatile boolean offerPending = false;
        volatile String displayName;

        final AtomicLong forwardedRtpPackets = new AtomicLong();
        final long creationMs = System.currentTimeMillis();
        volatile long lastIncomingActivityMs = 0;

        Participant(String id, SfuPeerConnection connection, String dataChannelMid,
            DataChannelTrack signalChannel, MediaTransceiver uplinkAudio, MediaTransceiver uplinkVideo)
        {
            this.id = id;
            this.connection = connection;
            this.dataChannelMid = dataChannelMid;
            this.signalChannel = signalChannel;
            this.uplinkAudio = uplinkAudio;
            this.uplinkVideo = uplinkVideo;
            this.displayName = id;
        }

        /** The receive track for this participant's camera, or null before they publish. */
        MediaTrack videoIn()
        {
            return uplinkVideo.getReceiver();
        }

        MediaTrack audioIn()
        {
            return uplinkAudio.getReceiver();
        }
    }

    public static void main(String[] args) throws Exception
    {
        new ConferenceSfu().start();
    }

    private void start() throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::handleIndex);
        server.createContext("/join", this::handleJoin);
        server.createContext("/answer", this::handleAnswer);
        server.createContext("/leave", this::handleLeave);
        server.createContext("/debug", this::handleDebug);
        server.start();
        scheduler.scheduleWithFixedDelay(this::monitorConnectionStatus,
            STATUS_CHECK_INTERVAL_MS, STATUS_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        System.out.println("Conference SFU listening on http://localhost:" + HTTP_PORT + "/");
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private void handleIndex(HttpExchange exchange) throws IOException
    {
        if (!"GET".equals(exchange.getRequestMethod()))
        {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        byte[] body;
        try (InputStream in = ConferenceSfu.class.getResourceAsStream("/web/conference.html"))
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

    /**
     * Creates a participant and returns the SFU's <em>offer</em> — the whole point of this demo.
     * The offer already contains the data channel, the participant's uplink sections, and a
     * downlink pair for every participant already in the room, so a browser that joins a busy room
     * needs exactly one round trip and no renegotiation.
     */
    private void handleJoin(HttpExchange exchange) throws IOException
    {
        if (!"POST".equals(exchange.getRequestMethod()))
        {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        try
        {
            String name = queryParam(exchange, "name");
            ObjectNode response = join(name);
            respondJson(exchange, 200, response);
        }
        catch (Exception e)
        {
            System.err.println("Failed to handle join: " + e);
            e.printStackTrace();
            respondText(exchange, 500, String.valueOf(e));
        }
    }

    /** Applies the browser's answer to the join offer and starts forwarding its media. */
    private void handleAnswer(HttpExchange exchange) throws IOException
    {
        if (!"POST".equals(exchange.getRequestMethod()))
        {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        try
        {
            JsonNode body = json.readTree(exchange.getRequestBody());
            String peerId = body.path("peerId").asText();
            Participant participant = participants.get(peerId);
            if (participant == null)
            {
                respondText(exchange, 404, "no such participant: " + peerId);
                return;
            }
            applyAnswer(participant, body.path("sdp").asText());
            respondJson(exchange, 200, roomState(participant));
        }
        catch (Exception e)
        {
            System.err.println("Failed to handle answer: " + e);
            e.printStackTrace();
            respondText(exchange, 500, String.valueOf(e));
        }
    }

    /** Explicit departure (the page sends this on unload), so tiles disappear promptly. */
    private void handleLeave(HttpExchange exchange) throws IOException
    {
        String peerId = queryParam(exchange, "peerId");
        if (peerId != null)
        {
            removeParticipant(peerId, "left");
        }
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void handleDebug(HttpExchange exchange) throws IOException
    {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        for (Participant participant : participants.values())
        {
            ObjectNode node = participant.connection.getDebugState();
            node.put("forwarded_rtp_packets", participant.forwardedRtpPackets.get());
            node.put("published", participant.published);
            node.put("downlinks", participant.downlinks.size());
            ObjectNode transceivers = JsonNodeFactory.instance.objectNode();
            for (MediaTransceiver transceiver : participant.connection.getTransceivers())
            {
                transceivers.put(transceiver.getMid(), transceiver.toString());
            }
            node.set("transceivers", transceivers);
            root.set(participant.id, node);
        }
        respondJson(exchange, 200, root);
    }

    // ------------------------------------------------------------------
    // Conference logic
    // ------------------------------------------------------------------

    private ObjectNode join(String name)
    {
        String peerId = "peer-" + nextParticipantId.getAndIncrement();

        SfuPeerConnection connection = new SfuPeerConnection(
            peerId,
            SfuPeerConnection.Role.OFFERER,
            new Observer(peerId),
            new org.jitsi.utils.logging2.LoggerImpl(ConferenceSfu.class.getName()));

        // Reserve the data channel's mid first so it lands on mid 0 and the m= section order stays
        // stable for every later re-offer (JSEP forbids reordering sections between offers).
        String dataChannelMid = connection.getDataChannelMid();
        DataChannelTrack signalChannel = connection.createDataChannel("signal");

        // The uplink: we receive this participant's mic and camera. Declaring it recvonly here means
        // the browser attaches its own tracks to sections *we* chose, instead of offering its own.
        MediaTransceiver uplinkAudio = connection.addTransceiver(
            MediaKind.AUDIO, MediaDirection.RECVONLY, audioPayloadTypes(), audioExtensions());
        MediaTransceiver uplinkVideo = connection.addTransceiver(
            MediaKind.VIDEO, MediaDirection.RECVONLY, videoPayloadTypes(), videoExtensions());

        Participant participant = new Participant(
            peerId, connection, dataChannelMid, signalChannel, uplinkAudio, uplinkVideo);
        if (name != null && !name.isBlank())
        {
            participant.displayName = name;
        }

        // Route keyframe requests from this participant's browser back to whichever source feeds
        // the downlink it asked about (mirrors jvb's BitrateController.keyframeNeeded). Without it,
        // a browser that joins mid-stream can sit on a black frame until the encoder's next
        // periodic keyframe.
        connection.setKeyFrameRequestHandler(requestedSsrc -> requestKeyFrameForDownlink(participant, requestedSsrc));

        participants.put(peerId, participant);

        // Every participant already in the room gets a downlink on this connection, so the very
        // first offer is complete.
        for (Participant other : participants.values())
        {
            if (other != participant && other.published)
            {
                addDownlink(participant, other);
            }
        }

        ObjectNode response = offerMessage(participant);
        System.out.println("[" + peerId + "] joined; offering " + connection.getTransceivers().size()
            + " media sections (" + participant.downlinks.size() + " downlinks)");
        return response;
    }

    /**
     * Applies a participant's answer: binds the SSRCs their browser chose for the uplink sections
     * and wires the resulting receive tracks to every other participant's downlink.
     *
     * <p>Only transport parameters that the remote can actually change need
     * {@link SfuPeerConnection#setRemoteDescription}; a renegotiation that only adds or removes
     * media reuses the established ICE/DTLS transport, so later answers are parsed for their SSRCs
     * alone.
     */
    private void applyAnswer(Participant participant, String answerSdp)
    {
        SdpUtils.ParsedSdp answer = SdpUtils.parse(answerSdp);
        boolean first = !participant.published;
        if (first)
        {
            participant.connection.setRemoteDescription(answer.transport);
        }

        bindUplink(participant, participant.uplinkAudio, answer, MediaKind.AUDIO);
        bindUplink(participant, participant.uplinkVideo, answer, MediaKind.VIDEO);

        if (first)
        {
            participant.published = true;
            participant.lastIncomingActivityMs = System.currentTimeMillis();
            // Publish this participant to everyone else (and pick up anyone who joined meanwhile).
            syncDownlinks();
        }
    }

    /**
     * Binds the SSRC the browser answered with on one uplink section, and hooks the receive track
     * up to the fan-out. A re-answer for an unchanged SSRC is a no-op.
     */
    private void bindUplink(Participant participant, MediaTransceiver uplink, SdpUtils.ParsedSdp answer, MediaKind kind)
    {
        SdpUtils.MediaDescription section = answer.getMediaByMid(uplink.getMid());
        if (section == null || section.isRejected() || section.ssrcs.isEmpty())
        {
            System.out.println("[" + participant.id + "] no " + kind + " uplink in the answer (mid "
                + uplink.getMid() + ")");
            return;
        }
        long ssrc = section.ssrcs.get(0);
        if (uplink.getRemoteSsrc() == ssrc)
        {
            return;
        }
        MediaTrack receiveTrack = uplink.setRemoteSsrc(ssrc);
        receiveTrack.onRtpPacket(packet -> {
            participant.lastIncomingActivityMs = System.currentTimeMillis();
            fanOut(participant, kind, receiveTrack, packet);
        });
        System.out.println("[" + participant.id + "] receiving " + kind + " ssrc " + ssrc
            + " (mid " + uplink.getMid() + ")");
    }

    /**
     * Makes sure every published participant has a downlink for every other published participant,
     * and none for anyone who has gone. Called after a join or a leave; each change fires
     * {@code onRenegotiationNeeded} on the affected connection, which pushes a fresh offer.
     */
    private synchronized void syncDownlinks()
    {
        for (Participant to : participants.values())
        {
            for (Participant from : participants.values())
            {
                if (from != to && from.published && !to.downlinks.containsKey(from.id))
                {
                    addDownlink(to, from);
                }
            }
            for (String sourceId : List.copyOf(to.downlinks.keySet()))
            {
                if (!participants.containsKey(sourceId))
                {
                    removeDownlink(to, sourceId);
                }
            }
        }
    }

    /**
     * Adds the pair of {@link MediaDirection#SENDONLY} transceivers carrying {@code from}'s media to
     * {@code to}. Both share {@code from}'s id as their {@code msid} stream id, so the browser
     * delivers them as a single {@code MediaStream} and can label the tile without extra signalling.
     */
    private void addDownlink(Participant to, Participant from)
    {
        MediaTransceiver audio = to.connection.addTransceiver(
            MediaKind.AUDIO, MediaDirection.SENDONLY, audioPayloadTypes(), audioExtensions(),
            from.id, from.id + "-audio");
        MediaTransceiver video = to.connection.addTransceiver(
            MediaKind.VIDEO, MediaDirection.SENDONLY, videoPayloadTypes(), videoExtensions(),
            from.id, from.id + "-video");
        to.downlinks.put(from.id, new Downlink(audio, video));
        System.out.println("[" + to.id + "] + downlink for " + from.id
            + " (mids " + audio.getMid() + "/" + video.getMid() + ")");
        // The new receiver needs a keyframe to start decoding; ask the source for one now and again
        // shortly after, to cover the gap before its answer is applied.
        for (long delayMs : new long[] { 0, 600, 1500 })
        {
            scheduler.schedule(() -> requestKeyFrame(from), delayMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Retires the downlink carrying {@code sourceId}'s media. The transceivers are stopped rather
     * than forgotten: their {@code m=} sections keep their place in every later offer, emitted as
     * rejected, which is what lets sections come and go without breaking JSEP's ordering rule.
     */
    private void removeDownlink(Participant to, String sourceId)
    {
        Downlink downlink = to.downlinks.remove(sourceId);
        if (downlink == null)
        {
            return;
        }
        downlink.audio.stop();
        downlink.video.stop();
        System.out.println("[" + to.id + "] - downlink for " + sourceId);
    }

    /** Forwards one received RTP packet to every other participant's downlink for this source. */
    private void fanOut(Participant from, MediaKind kind, MediaTrack source, RtpPacket packet)
    {
        for (Participant to : participants.values())
        {
            if (to == from)
            {
                continue;
            }
            Downlink downlink = to.downlinks.get(from.id);
            if (downlink == null)
            {
                continue;
            }
            MediaTransceiver transceiver = kind == MediaKind.AUDIO ? downlink.audio : downlink.video;
            MediaTrack sendTrack = transceiver.getSender();
            if (sendTrack != null)
            {
                // forwardRtp clones the packet and (for video) projects it into a clean, gap-free
                // stream on the downlink SSRC, so forwarding per destination is safe and decodable.
                sendTrack.forwardRtp(packet, source);
                to.forwardedRtpPackets.incrementAndGet();
            }
        }
    }

    /**
     * A participant's browser asked for a keyframe on one of the SSRCs we send it. Find which
     * downlink that SSRC belongs to and ask the participant feeding it for a fresh keyframe.
     */
    private void requestKeyFrameForDownlink(Participant to, long requestedSsrc)
    {
        for (Map.Entry<String, Downlink> entry : to.downlinks.entrySet())
        {
            if (entry.getValue().video.getSendSsrc() == requestedSsrc)
            {
                Participant source = participants.get(entry.getKey());
                if (source != null)
                {
                    requestKeyFrame(source);
                }
                return;
            }
        }
    }

    /** Asks a participant's browser for a fresh video keyframe (RTCP PLI on their uplink SSRC). */
    private void requestKeyFrame(Participant participant)
    {
        MediaTrack videoIn = participant.videoIn();
        if (videoIn != null)
        {
            participant.connection.requestKeyFrame(videoIn.getSsrc());
        }
    }

    private void removeParticipant(String peerId, String reason)
    {
        Participant participant = participants.remove(peerId);
        if (participant == null)
        {
            return;
        }
        System.out.println("[" + peerId + "] " + reason + "; closing");
        participant.connection.close();
        // Retire their downlink on everyone else, which re-offers each remaining participant.
        syncDownlinks();
        broadcastRoster();
    }

    /**
     * Periodic sweep, as in {@link DemoSfu}: a participant that never starts sending, or that goes
     * quiet, is expired. This is what removes a browser that closed after ICE fully completed —
     * that path surfaces no ICE failure, so without the sweep such peers linger as ghosts.
     */
    private void monitorConnectionStatus()
    {
        long now = System.currentTimeMillis();
        for (Participant participant : participants.values())
        {
            long lastActivity = participant.lastIncomingActivityMs;
            boolean expired = lastActivity == 0
                ? now - participant.creationMs > FIRST_TRANSFER_TIMEOUT_MS
                : now - lastActivity > MAX_INACTIVITY_LIMIT_MS;
            if (expired)
            {
                removeParticipant(participant.id, "expired (no activity)");
            }
        }
    }

    // ------------------------------------------------------------------
    // Signalling: the SFU always offers
    // ------------------------------------------------------------------

    /**
     * Builds the offer for a participant from its transceivers, plus the map the page needs to make
     * sense of it: which mids are its uplink, and which participant each downlink section carries.
     */
    private ObjectNode offerMessage(Participant participant)
    {
        List<SdpUtils.MediaSection> sections = new ArrayList<>();
        sections.add(SdpUtils.MediaSection.dataChannel(participant.dataChannelMid));

        Map<String, String> midToSource = new LinkedHashMap<>();
        for (Map.Entry<String, Downlink> entry : participant.downlinks.entrySet())
        {
            midToSource.put(entry.getValue().audio.getMid(), entry.getKey());
            midToSource.put(entry.getValue().video.getMid(), entry.getKey());
        }

        // Sections must appear in transceiver order, including the stopped ones (emitted rejected).
        for (MediaTransceiver transceiver : participant.connection.getTransceivers())
        {
            sections.add(toSection(transceiver));
        }

        ObjectNode message = JsonNodeFactory.instance.objectNode();
        message.put("type", "offer");
        message.put("peerId", participant.id);
        message.put("sdp", SdpUtils.buildOffer(participant.connection.getLocalDescription(), sections));
        ObjectNode uplink = message.putObject("uplink");
        uplink.put("audio", participant.uplinkAudio.getMid());
        uplink.put("video", participant.uplinkVideo.getMid());
        ObjectNode streams = message.putObject("streams");
        midToSource.forEach(streams::put);
        message.set("participants", rosterNode());
        return message;
    }

    /**
     * Maps one transceiver onto the plain SDP data {@code SdpUtils} deals in. The
     * {@code PayloadType} &rarr; SDP codec mapping lives here, in the host app, and not in
     * {@code SdpUtils} — the library core stays SDP-free and {@code SdpUtils} stays free of
     * {@code org.jitsi.nlj} (the same rule {@code DemoSfu.toPayloadType} follows in the other
     * direction).
     */
    private static SdpUtils.MediaSection toSection(MediaTransceiver transceiver)
    {
        String kind = transceiver.getKind() == MediaKind.AUDIO ? "audio" : "video";
        SdpUtils.MediaSection section = new SdpUtils.MediaSection(transceiver.getMid(), kind);
        section.rejected = transceiver.isStopped();
        section.direction = transceiver.getDirection().getSdpToken();
        section.cname = transceiver.getCname();
        section.msidStream = transceiver.getStreamId();
        section.msidTrack = transceiver.getTrackId();
        if (transceiver.getDirection().isSending())
        {
            section.ssrcs.add(transceiver.getSendSsrc());
        }
        for (PayloadType payloadType : transceiver.getPayloadTypes())
        {
            section.codecs.add(toCodec(payloadType));
        }
        for (RtpExtension extension : transceiver.getRtpExtensions())
        {
            section.extmaps.add(new SdpUtils.Extmap(extension.getId(), extension.getType().getUri()));
        }
        return section;
    }

    private static SdpUtils.Codec toCodec(PayloadType payloadType)
    {
        SdpUtils.Codec codec = new SdpUtils.Codec(payloadType.getPt() & 0xFF);
        // SDP spells these VP8/opus; PayloadTypeEncoding is upper-case.
        codec.name = payloadType.getEncoding() == org.jitsi.nlj.format.PayloadTypeEncoding.OPUS
            ? "opus" : payloadType.getEncoding().name();
        codec.clockRate = payloadType.getClockRate();
        codec.channels = payloadType.getMediaType() == org.jitsi.utils.MediaType.AUDIO ? 2 : 1;
        codec.fmtp.putAll(payloadType.getParameters());
        codec.rtcpFb.addAll(payloadType.getRtcpFeedbackSet());
        return codec;
    }

    /** Pushes a fresh offer to a participant over the SFU-created data channel. */
    private void sendOffer(Participant participant)
    {
        DataChannelTrack channel = participant.signalChannel;
        if (channel == null || !channel.isOpen())
        {
            // Not connected yet. Remember, and send it as soon as the channel opens — a downlink can
            // be added between the join answer and SCTP coming up, and that offer must not be lost.
            participant.offerPending = true;
            return;
        }
        participant.offerPending = false;
        try
        {
            ObjectNode offer = offerMessage(participant);
            channel.sendString(json.writeValueAsString(offer));
            System.out.println("[" + participant.id + "] re-offering ("
                + participant.downlinks.size() + " downlinks)");
        }
        catch (Exception e)
        {
            System.err.println("[" + participant.id + "] failed to send offer: " + e);
        }
    }

    /** Handles a signalling message the browser sent back over the data channel. */
    private void handleSignal(Participant participant, String message)
    {
        try
        {
            JsonNode node = json.readTree(message);
            String type = node.path("type").asText();
            if ("answer".equals(type))
            {
                applyAnswer(participant, node.path("sdp").asText());
            }
            else if ("chat".equals(type))
            {
                relayChat(participant, node.path("text").asText());
            }
            else if ("name".equals(type))
            {
                participant.displayName = node.path("name").asText(participant.id);
                broadcastRoster();
            }
        }
        catch (Exception e)
        {
            System.err.println("[" + participant.id + "] bad signalling message: " + e);
        }
    }

    private void relayChat(Participant from, String text)
    {
        ObjectNode message = JsonNodeFactory.instance.objectNode();
        message.put("type", "chat");
        message.put("from", from.id);
        message.put("name", from.displayName);
        message.put("text", text);
        broadcast(message, from);
    }

    private void broadcastRoster()
    {
        ObjectNode message = JsonNodeFactory.instance.objectNode();
        message.put("type", "roster");
        message.set("participants", rosterNode());
        broadcast(message, null);
    }

    private ObjectNode rosterNode()
    {
        ObjectNode roster = JsonNodeFactory.instance.objectNode();
        for (Participant participant : participants.values())
        {
            roster.put(participant.id, participant.displayName);
        }
        return roster;
    }

    private void broadcast(ObjectNode message, Participant except)
    {
        String text;
        try
        {
            text = json.writeValueAsString(message);
        }
        catch (Exception e)
        {
            return;
        }
        for (Participant participant : participants.values())
        {
            DataChannelTrack channel = participant.signalChannel;
            if (participant != except && channel != null && channel.isOpen())
            {
                channel.sendString(text);
            }
        }
    }

    /** The room state returned with the answer acknowledgement. */
    private ObjectNode roomState(Participant participant)
    {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("peerId", participant.id);
        node.set("participants", rosterNode());
        return node;
    }

    // ------------------------------------------------------------------
    // Codec / extension sets offered by this SFU
    // ------------------------------------------------------------------

    private static List<PayloadType> audioPayloadTypes()
    {
        Map<String, String> fmtp = new java.util.LinkedHashMap<>();
        fmtp.put("minptime", "10");
        fmtp.put("useinbandfec", "1");
        OpusPayloadType opus = new OpusPayloadType(OPUS_PT, 48000, 2, fmtp);
        opus.getRtcpFeedbackSet().addAll(AUDIO_RTCP_FB);
        return List.of(opus);
    }

    private static List<PayloadType> videoPayloadTypes()
    {
        // The feedback set is what tells the pipeline it may send PLI/FIR/NACK for this codec, and
        // it is echoed into the offer's a=rtcp-fb lines so the browser knows to honour them.
        return List.of(new Vp8PayloadType(VP8_PT, new java.util.LinkedHashMap<>(), VIDEO_RTCP_FB));
    }

    private static List<RtpExtension> audioExtensions()
    {
        return List.of(
            new RtpExtension((byte) EXT_AUDIO_LEVEL_ID, RtpExtensionType.SSRC_AUDIO_LEVEL),
            new RtpExtension((byte) EXT_TRANSPORT_CC_ID, RtpExtensionType.TRANSPORT_CC));
    }

    private static List<RtpExtension> videoExtensions()
    {
        return List.of(
            new RtpExtension((byte) EXT_ABS_SEND_TIME_ID, RtpExtensionType.ABS_SEND_TIME),
            new RtpExtension((byte) EXT_TRANSPORT_CC_ID, RtpExtensionType.TRANSPORT_CC));
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private static String queryParam(HttpExchange exchange, String name)
    {
        String query = exchange.getRequestURI().getQuery();
        if (query == null)
        {
            return null;
        }
        for (String pair : query.split("&"))
        {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name))
            {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void respondJson(HttpExchange exchange, int status, ObjectNode body) throws IOException
    {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(bytes);
        }
    }

    private void respondText(HttpExchange exchange, int status, String body) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(bytes);
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

        /**
         * A send-side change (a downlink added or stopped) means this participant's browser needs a
         * new offer. Because the SFU is the only offerer, this is the entire renegotiation trigger.
         */
        @Override
        public void onRenegotiationNeeded()
        {
            Participant participant = participants.get(peerId);
            if (participant != null)
            {
                sendOffer(participant);
            }
        }

        @Override
        public void onDataChannelOpen(DataChannelTrack track)
        {
            System.out.println("[" + peerId + "] data channel open: " + track.getLabel());
            Participant participant = participants.get(peerId);
            if (participant != null)
            {
                participant.lastIncomingActivityMs = System.currentTimeMillis();
                if (participant.offerPending)
                {
                    sendOffer(participant);
                }
                broadcastRoster();
            }
        }

        @Override
        public void onDataChannelStringMessage(DataChannelTrack track, String message)
        {
            Participant participant = participants.get(peerId);
            if (participant != null)
            {
                participant.lastIncomingActivityMs = System.currentTimeMillis();
                handleSignal(participant, message);
            }
        }

        @Override
        public void onDisconnected()
        {
            removeParticipant(peerId, "disconnected");
        }

        @Override
        public void onClosed()
        {
            participants.remove(peerId);
        }

        @Override
        public void onError(Throwable t)
        {
            System.err.println("[" + peerId + "] error: " + t);
        }
    }
}
