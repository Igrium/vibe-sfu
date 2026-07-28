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

import org.jitsi.nlj.MediaSourceDesc;
import org.jitsi.rtp.rtp.RtpPacket;

import java.util.function.Consumer;

/**
 * A single media flow (one SSRC) on a {@link SfuPeerConnection}. Tracks come in two
 * directions:
 *
 * <ul>
 *   <li><b>Receive tracks</b> ({@link SfuPeerConnection#addReceiveTrack}) deliver the
 *       SRTP-<em>decrypted</em>, parsed {@link RtpPacket}s arriving from the remote peer
 *       via {@link #onRtpPacket}. This is the core SFU contract: the host receives raw
 *       RTP (headers + payload) and forwards it without transcoding.</li>
 *   <li><b>Send tracks</b> ({@link SfuPeerConnection#createSendTrack}) accept
 *       {@link RtpPacket}s via {@link #sendRtp}; they are SRTP-encrypted and sent to the
 *       remote peer.</li>
 * </ul>
 *
 * <p>The {@link RtpPacket} passed to an {@link #onRtpPacket} listener is only valid for
 * the duration of the callback; its backing buffer is recycled afterwards. To forward it,
 * pass it to a send track's {@link #sendRtp} (which clones it) from within the callback.
 */
public class MediaTrack
{
    private final SfuPeerConnection connection;
    private final MediaKind kind;
    private final long ssrc;
    private final boolean local;

    private volatile Consumer<RtpPacket> rtpListener;

    /**
     * The {@link MediaSourceDesc} the library built for this (video receive) track. Non-null only
     * for video receive tracks; it lets a send track project this source's packets into a clean,
     * gap-free stream when forwarding (see {@link #forwardRtp}).
     */
    private volatile MediaSourceDesc sourceDesc;

    MediaTrack(SfuPeerConnection connection, MediaKind kind, long ssrc, boolean local)
    {
        this.connection = connection;
        this.kind = kind;
        this.ssrc = ssrc;
        this.local = local;
    }

    /** The kind of media (audio or video) this track carries. */
    public MediaKind getKind()
    {
        return kind;
    }

    /** The RTP SSRC of this track. */
    public long getSsrc()
    {
        return ssrc;
    }

    /** Whether this is a local (send) track. A receive track returns {@code false}. */
    public boolean isLocal()
    {
        return local;
    }

    /**
     * Registers a listener for RTP packets received on this track. Only valid on a receive
     * track. Replaces any previously registered listener.
     */
    public void onRtpPacket(Consumer<RtpPacket> listener)
    {
        if (local)
        {
            throw new IllegalStateException("onRtpPacket is only valid on a receive track");
        }
        this.rtpListener = listener;
    }

    /**
     * Sends an RTP packet to the remote peer. Only valid on a send track. The packet is
     * cloned, so the caller retains ownership of its buffer.
     *
     * <p>This forwards the packet <em>as-is</em> (only the SSRC is left to the caller). When
     * relaying media received from another peer, prefer {@link #forwardRtp}, which rewrites the
     * stream so quality/source switches are transparent to the receiver; forwarding raw sequence
     * numbers and picture IDs is tolerated by some browsers (Firefox) but rejected by others
     * (Chrome), which then never decodes a frame.
     */
    public void sendRtp(RtpPacket packet)
    {
        if (!local)
        {
            throw new IllegalStateException("sendRtp is only valid on a send track");
        }
        connection.sendRtp(packet);
    }

    /**
     * Forwards an RTP packet received on {@code source} out over this send track, relaying it
     * without transcoding. For video this projects the source stream onto this track's SSRC with
     * clean, gap-free sequence numbers, timestamps and picture IDs (mirroring jitsi-videobridge's
     * {@code Endpoint.preProcess} → {@code AdaptiveSourceProjection}); audio is relayed as-is with
     * only its SSRC rewritten. The rewriting is what makes forwarded video decodable across
     * browsers — Chrome in particular discards a stream whose sequence numbers or picture IDs are
     * discontinuous. Packets are dropped while the projection waits for a keyframe (a keyframe
     * request is issued back to the source automatically).
     *
     * <p>Only valid on a send track. The packet is cloned, so the caller retains ownership of its
     * buffer; {@code source} must be a video receive track from {@link SfuPeerConnection#addReceiveTrack}.
     *
     * @param packet the RTP packet received on {@code source}.
     * @param source the receive track the packet arrived on (its SSRC keys the projection).
     */
    public void forwardRtp(RtpPacket packet, MediaTrack source)
    {
        if (!local)
        {
            throw new IllegalStateException("forwardRtp is only valid on a send track");
        }
        connection.forwardRtp(this, packet, source);
    }

    /** The connection this track belongs to. */
    SfuPeerConnection getConnection()
    {
        return connection;
    }

    /** The source description built for this (video receive) track, or null. */
    MediaSourceDesc getSourceDesc()
    {
        return sourceDesc;
    }

    /** Records the source description the library built for this video receive track. */
    void setSourceDesc(MediaSourceDesc sourceDesc)
    {
        this.sourceDesc = sourceDesc;
    }

    /** Called by {@link SfuPeerConnection} to deliver a received packet to the listener. */
    void dispatch(RtpPacket packet)
    {
        Consumer<RtpPacket> listener = rtpListener;
        if (listener != null)
        {
            listener.accept(packet);
        }
    }
}
