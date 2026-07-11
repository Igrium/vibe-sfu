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

package com.igrium.sfu.sdp;

import org.ice4j.ice.CandidateType;
import org.jitsi.videobridge.transport.DtlsFingerprint;
import org.jitsi.videobridge.transport.IceCandidate;
import org.jitsi.videobridge.transport.TransportDescription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional SDP&nbsp;&harr;&nbsp;{@link TransportDescription} utilities for
 * data-channel-only sessions, for hosts whose signalling peers speak SDP
 * (browsers do).
 *
 * <p>The library core is deliberately SDP-free — the host brings its own
 * signalling, and upstream jitsi-videobridge signalled with COLIBRI/Jingle, not
 * SDP — so nothing in {@code com.igrium.sfu} depends on this class. It is a
 * standalone convenience, comparable to the SDP helpers that webrtc-java ships.
 *
 * <p>Typical answerer flow (browser sends the offer):
 * <pre>{@code
 * SdpUtils.ParsedSdp offer = SdpUtils.parse(offerSdp);
 * connection.setRemoteDescription(offer.transport);
 * String answerSdp = SdpUtils.buildAnswer(connection.getLocalDescription(), offer);
 * }</pre>
 *
 * <p>Typical offerer flow (this side sends the offer):
 * <pre>{@code
 * String offerSdp = SdpUtils.buildOffer(connection.getLocalDescription(), "0");
 * // ... send to peer, receive answerSdp ...
 * connection.setRemoteDescription(SdpUtils.parse(answerSdp).transport);
 * }</pre>
 */
public final class SdpUtils
{
    private SdpUtils()
    {
    }

    /**
     * The result of parsing an SDP offer or answer: the ICE/DTLS transport
     * parameters plus the session attributes needed to build the reply.
     */
    public static class ParsedSdp
    {
        /** ICE ufrag/password, DTLS fingerprints + setup, and any ICE candidates. */
        public final TransportDescription transport = new TransportDescription();
        /** The (data-channel) media-line id ({@code a=mid:}), echoed back in an answer. */
        public String mid = "0";
        /** The SCTP port ({@code a=sctp-port:}), default 5000. */
        public int sctpPort = 5000;
        /** Every {@code m=} section in the SDP, in document order (audio/video/application). */
        public final List<MediaDescription> mediaDescriptions = new ArrayList<>();

        /** The first {@code m=} section of the given kind ({@code "audio"}/{@code "video"}), or null. */
        public MediaDescription getMedia(String kind)
        {
            for (MediaDescription md : mediaDescriptions)
            {
                if (md.kind.equals(kind))
                {
                    return md;
                }
            }
            return null;
        }
    }

    /**
     * A parsed RTP payload type: PT number, codec name, clock rate/channels, {@code a=fmtp}
     * parameters and {@code a=rtcp-fb} feedback types. Plain data — the host (not this class)
     * maps this to an {@code org.jitsi.nlj.format.PayloadType}.
     */
    public static class Codec
    {
        public int pt;
        public String name;
        public int clockRate;
        /** Audio channel count; 0/1 for video or unspecified. */
        public int channels = 1;
        public final Map<String, String> fmtp = new LinkedHashMap<>();
        public final List<String> rtcpFb = new ArrayList<>();

        public Codec()
        {
        }

        public Codec(int pt)
        {
            this.pt = pt;
        }
    }

    /** A parsed {@code a=extmap} RTP header extension (id + URI). */
    public static class Extmap
    {
        public int id;
        public String uri;

        public Extmap()
        {
        }

        public Extmap(int id, String uri)
        {
            this.id = id;
            this.uri = uri;
        }
    }

    /**
     * One {@code m=audio}/{@code m=video}/{@code m=application} section of a parsed offer or
     * answer: the codecs/extensions/ssrcs/direction the remote side signalled. Plain data — no
     * dependency on {@code org.jitsi.nlj}.
     */
    public static class MediaDescription
    {
        /** {@code "audio"}, {@code "video"}, or {@code "application"}. */
        public String kind;
        /** The {@code a=mid:} value for this section. */
        public String mid = "0";
        public int port;
        /** {@code sendrecv}, {@code recvonly}, {@code sendonly}, or {@code inactive}. */
        public String direction = "sendrecv";
        public final List<Codec> codecs = new ArrayList<>();
        public final List<Extmap> extmaps = new ArrayList<>();
        /** Remote SSRCs signalled for this section (via {@code a=ssrc:}). */
        public final List<Long> ssrcs = new ArrayList<>();
        /** The {@code cname} of the first {@code a=ssrc:...cname:...} line seen, if any. */
        public String cname;

        /** The first codec whose name matches (case-insensitively), or null. */
        public Codec findCodec(String name)
        {
            for (Codec c : codecs)
            {
                if (c.name != null && c.name.equalsIgnoreCase(name))
                {
                    return c;
                }
            }
            return null;
        }

        Codec findOrCreatePt(int pt)
        {
            for (Codec c : codecs)
            {
                if (c.pt == pt)
                {
                    return c;
                }
            }
            Codec c = new Codec(pt);
            codecs.add(c);
            return c;
        }
    }

    /**
     * What we (the answerer) advertise for one audio/video {@code m=} section of an answer:
     * our chosen codec subset (must be PTs offered by the remote side), header extensions
     * (echoing offered id/URI pairs we support), our sending SSRC(s), and direction.
     */
    public static class MediaAnswer
    {
        /** Must match the {@code mid} of the offer section this answers. */
        public final String mid;
        /** {@code "audio"} or {@code "video"}. */
        public final String kind;
        public String direction = "sendrecv";
        public final List<Codec> codecs = new ArrayList<>();
        public final List<Extmap> extmaps = new ArrayList<>();
        /** Our local SSRC(s) sent on this section. */
        public final List<Long> ssrcs = new ArrayList<>();
        public String cname = "sfu";

        public MediaAnswer(String mid, String kind)
        {
            this.mid = mid;
            this.kind = kind;
        }
    }

    /**
     * Extracts ICE/DTLS transport parameters from a (data-channel-only) SDP offer
     * or answer.
     */
    public static ParsedSdp parse(String sdp)
    {
        ParsedSdp parsed = new ParsedSdp();
        MediaDescription current = null;
        for (String rawLine : sdp.split("\r?\n"))
        {
            String line = rawLine.trim();
            if (line.startsWith("m="))
            {
                current = parseMediaLine(line);
                parsed.mediaDescriptions.add(current);
            }
            else if (line.startsWith("a=ice-ufrag:"))
            {
                parsed.transport.ufrag = line.substring("a=ice-ufrag:".length());
            }
            else if (line.startsWith("a=ice-pwd:"))
            {
                parsed.transport.password = line.substring("a=ice-pwd:".length());
            }
            else if (line.startsWith("a=fingerprint:"))
            {
                // a=fingerprint:sha-256 AB:CD:...
                String[] parts = line.substring("a=fingerprint:".length()).split(" ", 2);
                if (parts.length == 2)
                {
                    DtlsFingerprint fingerprint = new DtlsFingerprint();
                    fingerprint.hash = parts[0];
                    fingerprint.fingerprint = parts[1];
                    parsed.transport.fingerprints.add(fingerprint);
                }
            }
            else if (line.startsWith("a=setup:"))
            {
                String setup = line.substring("a=setup:".length());
                for (DtlsFingerprint fingerprint : parsed.transport.fingerprints)
                {
                    fingerprint.setup = setup;
                }
            }
            else if (line.startsWith("a=mid:"))
            {
                String mid = line.substring("a=mid:".length());
                if (current != null)
                {
                    current.mid = mid;
                }
                if (current == null || "application".equals(current.kind))
                {
                    parsed.mid = mid;
                }
            }
            else if (line.startsWith("a=sctp-port:"))
            {
                parsed.sctpPort = Integer.parseInt(line.substring("a=sctp-port:".length()));
            }
            else if (line.startsWith("a=candidate:"))
            {
                IceCandidate candidate = parseCandidate(line.substring("a=".length()));
                if (candidate != null)
                {
                    parsed.transport.candidates.add(candidate);
                }
            }
            else if (current != null && ("audio".equals(current.kind) || "video".equals(current.kind)))
            {
                parseMediaAttribute(line, current);
            }
        }
        return parsed;
    }

    /**
     * Parses an {@code m=} line into a (mostly empty) {@link MediaDescription}: kind, port, and
     * (for audio/video) the offered payload-type numbers, later filled in by {@code a=rtpmap}
     * etc. via {@link #parseMediaAttribute}.
     */
    private static MediaDescription parseMediaLine(String line)
    {
        // m=<kind> <port> <proto> <fmt> <fmt> ...
        String[] tokens = line.substring("m=".length()).split(" ");
        MediaDescription md = new MediaDescription();
        md.kind = tokens[0];
        if (tokens.length > 1)
        {
            try
            {
                md.port = Integer.parseInt(tokens[1]);
            }
            catch (NumberFormatException ignored)
            {
                // e.g. malformed line; leave port at 0
            }
        }
        if ("audio".equals(md.kind) || "video".equals(md.kind))
        {
            for (int i = 3; i < tokens.length; i++)
            {
                try
                {
                    md.codecs.add(new Codec(Integer.parseInt(tokens[i])));
                }
                catch (NumberFormatException ignored)
                {
                    // non-numeric fmt (shouldn't happen for audio/video)
                }
            }
        }
        return md;
    }

    /** Parses one media-section attribute line ({@code a=rtpmap}, {@code a=fmtp}, etc.) into {@code md}. */
    private static void parseMediaAttribute(String line, MediaDescription md)
    {
        if (line.startsWith("a=rtpmap:"))
        {
            // a=rtpmap:<pt> <name>/<clockrate>[/<channels>]
            String rest = line.substring("a=rtpmap:".length());
            int sp = rest.indexOf(' ');
            if (sp < 0)
            {
                return;
            }
            Codec c = parsePt(md, rest.substring(0, sp));
            if (c == null)
            {
                return;
            }
            String[] parts = rest.substring(sp + 1).split("/");
            c.name = parts[0];
            if (parts.length > 1)
            {
                try
                {
                    c.clockRate = Integer.parseInt(parts[1]);
                }
                catch (NumberFormatException ignored)
                {
                }
            }
            if (parts.length > 2)
            {
                try
                {
                    c.channels = Integer.parseInt(parts[2]);
                }
                catch (NumberFormatException ignored)
                {
                }
            }
        }
        else if (line.startsWith("a=fmtp:"))
        {
            // a=fmtp:<pt> key=value;key=value...
            String rest = line.substring("a=fmtp:".length());
            int sp = rest.indexOf(' ');
            if (sp < 0)
            {
                return;
            }
            Codec c = parsePt(md, rest.substring(0, sp));
            if (c == null)
            {
                return;
            }
            for (String kv : rest.substring(sp + 1).split(";"))
            {
                String trimmed = kv.trim();
                if (trimmed.isEmpty())
                {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq > 0)
                {
                    c.fmtp.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
                }
                else
                {
                    c.fmtp.put(trimmed, "");
                }
            }
        }
        else if (line.startsWith("a=rtcp-fb:"))
        {
            // a=rtcp-fb:<pt|*> <feedback type> [...]
            String rest = line.substring("a=rtcp-fb:".length());
            int sp = rest.indexOf(' ');
            if (sp < 0)
            {
                return;
            }
            String ptToken = rest.substring(0, sp);
            String fb = rest.substring(sp + 1);
            if ("*".equals(ptToken))
            {
                for (Codec c : md.codecs)
                {
                    c.rtcpFb.add(fb);
                }
            }
            else
            {
                Codec c = parsePt(md, ptToken);
                if (c != null)
                {
                    c.rtcpFb.add(fb);
                }
            }
        }
        else if (line.startsWith("a=extmap:"))
        {
            // a=extmap:<id>[/<direction>] <uri> [ext-attributes]
            String rest = line.substring("a=extmap:".length());
            int sp = rest.indexOf(' ');
            if (sp < 0)
            {
                return;
            }
            String idToken = rest.substring(0, sp);
            int slash = idToken.indexOf('/');
            try
            {
                int id = Integer.parseInt(slash < 0 ? idToken : idToken.substring(0, slash));
                String uriRest = rest.substring(sp + 1).trim();
                int uriEnd = uriRest.indexOf(' ');
                String uri = uriEnd < 0 ? uriRest : uriRest.substring(0, uriEnd);
                md.extmaps.add(new Extmap(id, uri));
            }
            catch (NumberFormatException ignored)
            {
            }
        }
        else if (line.startsWith("a=ssrc:"))
        {
            // a=ssrc:<ssrc> <attribute>[:<value>]
            String rest = line.substring("a=ssrc:".length());
            int sp = rest.indexOf(' ');
            String ssrcToken = sp < 0 ? rest : rest.substring(0, sp);
            long ssrc;
            try
            {
                ssrc = Long.parseLong(ssrcToken);
            }
            catch (NumberFormatException e)
            {
                return;
            }
            if (!md.ssrcs.contains(ssrc))
            {
                md.ssrcs.add(ssrc);
            }
            if (sp >= 0)
            {
                String attr = rest.substring(sp + 1);
                if (attr.startsWith("cname:") && md.cname == null)
                {
                    md.cname = attr.substring("cname:".length());
                }
            }
        }
        else if (line.equals("a=sendrecv") || line.equals("a=recvonly")
            || line.equals("a=sendonly") || line.equals("a=inactive"))
        {
            md.direction = line.substring("a=".length());
        }
    }

    private static Codec parsePt(MediaDescription md, String ptToken)
    {
        try
        {
            return md.findOrCreatePt(Integer.parseInt(ptToken));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /**
     * Parses one {@code candidate:...} attribute value (RFC 5245
     * candidate-attribute grammar, as emitted by browsers). Also usable for
     * candidates trickled individually (e.g. from {@code RTCIceCandidate.candidate}).
     *
     * @return the candidate, or null if the value is not parseable.
     */
    public static IceCandidate parseCandidate(String value)
    {
        // candidate:<foundation> <component> <transport> <priority> <ip> <port> typ <type> [...]
        String[] tokens = value.substring("candidate:".length()).split(" ");
        if (tokens.length < 8 || !"typ".equals(tokens[6]))
        {
            return null;
        }
        IceCandidate candidate = new IceCandidate();
        candidate.foundation = tokens[0];
        candidate.component = Integer.parseInt(tokens[1]);
        candidate.protocol = tokens[2];
        candidate.priority = Long.parseLong(tokens[3]);
        candidate.ip = tokens[4];
        candidate.port = Integer.parseInt(tokens[5]);
        candidate.type = CandidateType.parse(tokens[7]);
        for (int i = 8; i + 1 < tokens.length; i += 2)
        {
            switch (tokens[i])
            {
                case "raddr" -> candidate.relAddr = tokens[i + 1];
                case "rport" -> candidate.relPort = Integer.parseInt(tokens[i + 1]);
                case "generation" -> candidate.generation = Integer.parseInt(tokens[i + 1]);
                default ->
                {
                    // ignore unknown extension attributes
                }
            }
        }
        return candidate;
    }

    /**
     * Builds a data-channel-only SDP answer to the given offer from our local
     * transport parameters (from
     * {@link com.igrium.sfu.SfuPeerConnection#getLocalDescription()}).
     *
     * <p>Equivalent to {@code buildAnswer(local, offer, List.of())}: if the offer has audio/video
     * {@code m=} sections and no {@link MediaAnswer} is supplied for them, they are answered as
     * rejected (port 0).
     */
    public static String buildAnswer(TransportDescription local, ParsedSdp offer)
    {
        return buildAnswer(local, offer, Collections.emptyList());
    }

    /**
     * Builds an SDP answer to the given offer, covering the data channel (if offered) plus one
     * audio/video {@code m=} section per entry in {@code mediaAnswers}. All {@code m=} sections
     * are BUNDLEd (in the offer's order, which JSEP requires an answer to preserve) onto the
     * single ICE/DTLS transport described by {@code local}, with {@code rtcp-mux}.
     *
     * @param mediaAnswers our answer for each audio/video section we accept, keyed internally by
     *                     {@link MediaAnswer#mid} (which must match a {@code mid} from {@code offer}).
     *                     An offered audio/video section with no corresponding entry is answered
     *                     as rejected (port 0), per JSEP (m= line count/order must be preserved).
     */
    public static String buildAnswer(TransportDescription local, ParsedSdp offer, List<MediaAnswer> mediaAnswers)
    {
        Map<String, MediaAnswer> byMid = new LinkedHashMap<>();
        for (MediaAnswer answer : mediaAnswers)
        {
            byMid.put(answer.mid, answer);
        }

        StringBuilder sdp = new StringBuilder();
        sdp.append("v=0\r\n");
        sdp.append("o=- 0 2 IN IP4 127.0.0.1\r\n");
        sdp.append("s=-\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("a=group:BUNDLE");
        for (MediaDescription md : offer.mediaDescriptions)
        {
            sdp.append(' ').append(md.mid);
        }
        sdp.append("\r\n");
        sdp.append("a=msid-semantic: WMS\r\n");

        for (MediaDescription md : offer.mediaDescriptions)
        {
            if ("application".equals(md.kind))
            {
                appendApplicationMLine(sdp, local, md.mid, offer.sctpPort);
            }
            else
            {
                appendMediaMLine(sdp, local, md, byMid.get(md.mid));
            }
        }
        return sdp.toString();
    }

    /**
     * Builds a data-channel-only SDP offer from our local transport parameters.
     * (Before the DTLS role is known the setup attribute is {@code actpass},
     * which is what an offer requires.)
     *
     * @param mid the media-line id to use, e.g. {@code "0"}.
     */
    public static String buildOffer(TransportDescription local, String mid)
    {
        StringBuilder sdp = new StringBuilder();
        sdp.append("v=0\r\n");
        sdp.append("o=- 0 2 IN IP4 127.0.0.1\r\n");
        sdp.append("s=-\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("a=group:BUNDLE ").append(mid).append("\r\n");
        sdp.append("a=msid-semantic: WMS\r\n");
        appendApplicationMLine(sdp, local, mid, 5000);
        return sdp.toString();
    }

    /** Appends an {@code m=application ... webrtc-datachannel} section (data channel transport). */
    private static void appendApplicationMLine(StringBuilder sdp, TransportDescription local, String mid, int sctpPort)
    {
        sdp.append("m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n");
        sdp.append("c=IN IP4 0.0.0.0\r\n");
        sdp.append("a=mid:").append(mid).append("\r\n");
        appendIceAndDtls(sdp, local);
        sdp.append("a=sctp-port:").append(sctpPort).append("\r\n");
        sdp.append("a=max-message-size:262144\r\n");
        appendCandidates(sdp, local);
    }

    /**
     * Appends an {@code m=audio}/{@code m=video} section. If {@code answer} is null (we have
     * nothing to offer for this section), the section is rejected (port 0), per JSEP's
     * requirement that the answer preserve the offer's {@code m=} line count/order.
     */
    private static void appendMediaMLine(StringBuilder sdp, TransportDescription local, MediaDescription offered, MediaAnswer answer)
    {
        if (answer == null || answer.codecs.isEmpty())
        {
            sdp.append("m=").append(offered.kind).append(" 0 UDP/TLS/RTP/SAVPF 0\r\n");
            sdp.append("c=IN IP4 0.0.0.0\r\n");
            sdp.append("a=mid:").append(offered.mid).append("\r\n");
            return;
        }

        sdp.append("m=").append(offered.kind).append(" 9 UDP/TLS/RTP/SAVPF");
        for (Codec codec : answer.codecs)
        {
            sdp.append(' ').append(codec.pt);
        }
        sdp.append("\r\n");
        sdp.append("c=IN IP4 0.0.0.0\r\n");
        sdp.append("a=rtcp-mux\r\n");
        sdp.append("a=mid:").append(offered.mid).append("\r\n");
        appendIceAndDtls(sdp, local);
        for (Extmap extmap : answer.extmaps)
        {
            sdp.append("a=extmap:").append(extmap.id).append(' ').append(extmap.uri).append("\r\n");
        }
        sdp.append("a=").append(answer.direction).append("\r\n");
        for (Codec codec : answer.codecs)
        {
            sdp.append("a=rtpmap:").append(codec.pt).append(' ').append(codec.name)
                .append('/').append(codec.clockRate);
            if (codec.channels > 1)
            {
                sdp.append('/').append(codec.channels);
            }
            sdp.append("\r\n");
            if (!codec.fmtp.isEmpty())
            {
                sdp.append("a=fmtp:").append(codec.pt).append(' ');
                boolean first = true;
                for (Map.Entry<String, String> param : codec.fmtp.entrySet())
                {
                    if (!first)
                    {
                        sdp.append(';');
                    }
                    sdp.append(param.getKey());
                    if (!param.getValue().isEmpty())
                    {
                        sdp.append('=').append(param.getValue());
                    }
                    first = false;
                }
                sdp.append("\r\n");
            }
        }
        for (Long ssrc : answer.ssrcs)
        {
            sdp.append("a=ssrc:").append(ssrc).append(" cname:").append(answer.cname).append("\r\n");
        }
        appendCandidates(sdp, local);
    }

    private static void appendIceAndDtls(StringBuilder sdp, TransportDescription local)
    {
        sdp.append("a=ice-ufrag:").append(local.ufrag).append("\r\n");
        sdp.append("a=ice-pwd:").append(local.password).append("\r\n");
        for (DtlsFingerprint fingerprint : local.fingerprints)
        {
            sdp.append("a=fingerprint:").append(fingerprint.hash)
                .append(' ').append(fingerprint.fingerprint).append("\r\n");
            sdp.append("a=setup:").append(fingerprint.setup).append("\r\n");
        }
    }

    private static void appendCandidates(StringBuilder sdp, TransportDescription local)
    {
        for (IceCandidate candidate : local.candidates)
        {
            sdp.append("a=").append(candidateAttribute(candidate)).append("\r\n");
        }
        sdp.append("a=end-of-candidates\r\n");
    }

    /**
     * Formats one of our candidates as a {@code candidate:...} attribute value
     * (without the {@code a=} prefix).
     */
    public static String candidateAttribute(IceCandidate candidate)
    {
        StringBuilder sb = new StringBuilder("candidate:");
        sb.append(candidate.foundation).append(' ')
            .append(candidate.component).append(' ')
            .append(candidate.protocol).append(' ')
            .append(candidate.priority).append(' ')
            .append(candidate.ip).append(' ')
            .append(candidate.port)
            .append(" typ ").append(candidate.type.toString());
        if (candidate.relAddr != null)
        {
            sb.append(" raddr ").append(candidate.relAddr).append(" rport ").append(candidate.relPort);
        }
        sb.append(" generation ").append(candidate.generation);
        return sb.toString();
    }
}
