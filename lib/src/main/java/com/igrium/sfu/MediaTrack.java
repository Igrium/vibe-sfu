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
     */
    public void sendRtp(RtpPacket packet)
    {
        if (!local)
        {
            throw new IllegalStateException("sendRtp is only valid on a send track");
        }
        connection.sendRtp(packet);
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
