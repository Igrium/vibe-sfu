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
        /** The media-line id ({@code a=mid:}), echoed back in an answer. */
        public String mid = "0";
        /** The SCTP port ({@code a=sctp-port:}), default 5000. */
        public int sctpPort = 5000;
    }

    /**
     * Extracts ICE/DTLS transport parameters from a (data-channel-only) SDP offer
     * or answer.
     */
    public static ParsedSdp parse(String sdp)
    {
        ParsedSdp parsed = new ParsedSdp();
        for (String rawLine : sdp.split("\r?\n"))
        {
            String line = rawLine.trim();
            if (line.startsWith("a=ice-ufrag:"))
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
                parsed.mid = line.substring("a=mid:".length());
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
        }
        return parsed;
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
     */
    public static String buildAnswer(TransportDescription local, ParsedSdp offer)
    {
        return buildDescription(local, offer.mid, offer.sctpPort);
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
        return buildDescription(local, mid, 5000);
    }

    private static String buildDescription(TransportDescription local, String mid, int sctpPort)
    {
        StringBuilder sdp = new StringBuilder();
        sdp.append("v=0\r\n");
        sdp.append("o=- 0 2 IN IP4 127.0.0.1\r\n");
        sdp.append("s=-\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("a=group:BUNDLE ").append(mid).append("\r\n");
        sdp.append("a=msid-semantic: WMS\r\n");
        sdp.append("m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n");
        sdp.append("c=IN IP4 0.0.0.0\r\n");
        sdp.append("a=mid:").append(mid).append("\r\n");
        sdp.append("a=ice-ufrag:").append(local.ufrag).append("\r\n");
        sdp.append("a=ice-pwd:").append(local.password).append("\r\n");
        for (DtlsFingerprint fingerprint : local.fingerprints)
        {
            sdp.append("a=fingerprint:").append(fingerprint.hash)
                .append(' ').append(fingerprint.fingerprint).append("\r\n");
            sdp.append("a=setup:").append(fingerprint.setup).append("\r\n");
        }
        sdp.append("a=sctp-port:").append(sctpPort).append("\r\n");
        sdp.append("a=max-message-size:262144\r\n");
        for (IceCandidate candidate : local.candidates)
        {
            sdp.append("a=").append(candidateAttribute(candidate)).append("\r\n");
        }
        sdp.append("a=end-of-candidates\r\n");
        return sdp.toString();
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
