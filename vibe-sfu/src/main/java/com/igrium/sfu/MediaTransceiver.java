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

import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.rtp.RtpExtension;

import java.util.List;

/**
 * A bidirectional media "slot" on a {@link SfuPeerConnection} — the library's analogue of the
 * browser's {@code RTCRtpTransceiver}, and the unit an SDP {@code m=} section maps to.
 *
 * <p>A transceiver pairs at most one send {@link MediaTrack} with at most one receive
 * {@link MediaTrack} under a stable media-line id ({@link #getMid() mid}), and owns everything
 * needed to describe that section in signalling: the codecs and header extensions to offer, the
 * SSRC we send with, and the {@code msid} stream/track identifiers that tell the remote peer how
 * to group our streams.
 *
 * <p>The point of the abstraction is that a transceiver can exist <em>before</em> the remote peer
 * has said anything. A host can call {@link SfuPeerConnection#addTransceiver} up front, describe
 * the resulting transceivers in its <em>own</em> offer, and only bind the remote SSRC — via
 * {@link #setRemoteSsrc} — once the answer names it. That is what lets an SFU publish a new
 * participant's stream to everyone else without waiting for each browser to offer first.
 *
 * <p>Lifecycle mirrors the browser's: {@link #stop()} retires a transceiver but keeps it in
 * {@link SfuPeerConnection#getTransceivers()}, so subsequent offers can still emit its {@code m=}
 * section (as rejected, port 0) and preserve the section order JSEP requires.
 *
 * <p>Instances are created by {@link SfuPeerConnection#addTransceiver}; they are not constructed
 * directly.
 */
public class MediaTransceiver
{
    private final SfuPeerConnection connection;
    private final String mid;
    private final MediaKind kind;
    private final List<PayloadType> payloadTypes;
    private final List<RtpExtension> rtpExtensions;
    private final long sendSsrc;
    private final String streamId;
    private final String trackId;

    private volatile String cname;
    private volatile MediaDirection direction;
    private volatile MediaTrack sender;
    private volatile MediaTrack receiver;
    private volatile long remoteSsrc = -1;
    private volatile boolean stopped = false;

    MediaTransceiver(
        SfuPeerConnection connection,
        String mid,
        MediaKind kind,
        MediaDirection direction,
        List<PayloadType> payloadTypes,
        List<RtpExtension> rtpExtensions,
        long sendSsrc,
        String streamId,
        String trackId,
        String cname)
    {
        this.connection = connection;
        this.mid = mid;
        this.kind = kind;
        this.direction = direction;
        this.payloadTypes = List.copyOf(payloadTypes);
        this.rtpExtensions = List.copyOf(rtpExtensions);
        this.sendSsrc = sendSsrc;
        this.streamId = streamId;
        this.trackId = trackId;
        this.cname = cname;
    }

    /** The media-line id ({@code a=mid:}) identifying this transceiver's {@code m=} section. */
    public String getMid()
    {
        return mid;
    }

    /** Whether this transceiver carries audio or video. */
    public MediaKind getKind()
    {
        return kind;
    }

    /** The connection this transceiver belongs to. */
    public SfuPeerConnection getConnection()
    {
        return connection;
    }

    /** The current direction, from this side's point of view. */
    public MediaDirection getDirection()
    {
        return direction;
    }

    /** The payload types to signal (and feed to the media pipeline) for this section. */
    public List<PayloadType> getPayloadTypes()
    {
        return payloadTypes;
    }

    /** The RTP header extensions to signal (and feed to the media pipeline) for this section. */
    public List<RtpExtension> getRtpExtensions()
    {
        return rtpExtensions;
    }

    /**
     * The local SSRC this transceiver sends with. Allocated when the transceiver is created —
     * including for a {@link MediaDirection#RECVONLY} one — so it stays stable if the direction
     * later becomes sending, and so it can be signalled up front.
     */
    public long getSendSsrc()
    {
        return sendSsrc;
    }

    /**
     * The {@code msid} media-stream id for this section: the identifier the remote peer groups
     * tracks by (in a browser, tracks sharing a stream id arrive in the same {@code MediaStream}
     * on the {@code track} event). An SFU typically uses one stream id per forwarded participant,
     * so the receiver's audio and video for that participant pair up automatically.
     */
    public String getStreamId()
    {
        return streamId;
    }

    /** The {@code msid} track id for this section (unique within the stream). */
    public String getTrackId()
    {
        return trackId;
    }

    /** The RTCP {@code cname} to signal for our sending SSRC. */
    public String getCname()
    {
        return cname;
    }

    /** Sets the RTCP {@code cname} to signal for our sending SSRC. */
    public void setCname(String cname)
    {
        this.cname = cname;
    }

    /**
     * The send track, or null when this transceiver is not currently sending
     * ({@link MediaDirection#RECVONLY}/{@link MediaDirection#INACTIVE}, or stopped).
     */
    public MediaTrack getSender()
    {
        return sender;
    }

    /**
     * The receive track, or null until the remote SSRC is bound with {@link #setRemoteSsrc}
     * (or after the transceiver is stopped).
     */
    public MediaTrack getReceiver()
    {
        return receiver;
    }

    /** The remote SSRC bound with {@link #setRemoteSsrc}, or -1 if none. */
    public long getRemoteSsrc()
    {
        return remoteSsrc;
    }

    /** Whether {@link #stop()} has been called. */
    public boolean isStopped()
    {
        return stopped;
    }

    /**
     * Changes the direction, creating or removing the send track to match. Growing into a sending
     * direction after the connection is negotiated fires
     * {@link SfuPeerConnectionObserver#onRenegotiationNeeded()}, exactly as
     * {@link SfuPeerConnection#createSendTrack} does; so does dropping out of one.
     *
     * <p>Dropping out of a receiving direction also releases the receive track (if one was bound);
     * re-entering one requires a fresh {@link #setRemoteSsrc}.
     *
     * @param newDirection the direction this side should take.
     * @throws IllegalStateException if the transceiver has been stopped.
     */
    public synchronized void setDirection(MediaDirection newDirection)
    {
        if (stopped)
        {
            throw new IllegalStateException("Transceiver " + mid + " is stopped");
        }
        if (newDirection == direction)
        {
            return;
        }
        MediaDirection previous = direction;
        direction = newDirection;

        if (newDirection.isSending() && sender == null)
        {
            sender = connection.createSendTrack(kind, sendSsrc, payloadTypes, rtpExtensions);
        }
        else if (!newDirection.isSending() && sender != null)
        {
            connection.removeSendTrack(sender);
            sender = null;
        }

        if (!newDirection.isReceiving() && receiver != null)
        {
            connection.removeReceiveTrack(receiver);
            receiver = null;
            remoteSsrc = -1;
        }
        else if (newDirection.isReceiving() && !previous.isReceiving() && remoteSsrc >= 0)
        {
            // Direction flipped back to receiving with a remote SSRC still on record: re-register it.
            long ssrc = remoteSsrc;
            remoteSsrc = -1;
            setRemoteSsrc(ssrc);
        }
    }

    /**
     * Binds the SSRC the remote peer sends on this section — typically read out of its SDP answer
     * (or offer) — and returns the receive track that will deliver its RTP. Registering a receive
     * track follows the remote's signalling rather than originating a new offer, so this does
     * <em>not</em> fire {@link SfuPeerConnectionObserver#onRenegotiationNeeded()}.
     *
     * <p>Calling it again with the same SSRC returns the existing track (listeners are preserved);
     * calling it with a different SSRC replaces the track, so any {@link MediaTrack#onRtpPacket}
     * listener must be registered again.
     *
     * @param ssrc the remote SSRC to receive on this section.
     * @return the receive track for {@code ssrc}.
     * @throws IllegalStateException if the transceiver is stopped or its direction doesn't receive.
     */
    public synchronized MediaTrack setRemoteSsrc(long ssrc)
    {
        if (stopped)
        {
            throw new IllegalStateException("Transceiver " + mid + " is stopped");
        }
        if (!direction.isReceiving())
        {
            throw new IllegalStateException(
                "Transceiver " + mid + " has direction " + direction + " and cannot receive");
        }
        if (receiver != null && remoteSsrc == ssrc)
        {
            return receiver;
        }
        if (receiver != null)
        {
            connection.removeReceiveTrack(receiver);
            receiver = null;
        }
        receiver = connection.addReceiveTrack(kind, ssrc, payloadTypes, rtpExtensions);
        remoteSsrc = ssrc;
        return receiver;
    }

    /**
     * Retires this transceiver: both tracks are removed, the direction becomes
     * {@link MediaDirection#INACTIVE}, and the host is asked to re-signal (via
     * {@link SfuPeerConnectionObserver#onRenegotiationNeeded()}) if the connection is already
     * negotiated. Stop calling {@link MediaTrack#sendRtp}/{@link MediaTrack#forwardRtp} on the
     * send track first.
     *
     * <p>The transceiver stays in {@link SfuPeerConnection#getTransceivers()} so later offers can
     * keep emitting its {@code m=} section as rejected, which JSEP requires (an offer may not drop
     * or reorder sections). Idempotent.
     */
    public synchronized void stop()
    {
        if (stopped)
        {
            return;
        }
        stopped = true;
        direction = MediaDirection.INACTIVE;
        if (sender != null)
        {
            connection.removeSendTrack(sender);
            sender = null;
        }
        if (receiver != null)
        {
            connection.removeReceiveTrack(receiver);
            receiver = null;
        }
        remoteSsrc = -1;
        connection.signalRenegotiationNeeded();
    }

    /** Creates the send track for a transceiver constructed in a sending direction. */
    synchronized void initSendTrack()
    {
        if (sender == null && direction.isSending())
        {
            sender = connection.createSendTrack(kind, sendSsrc, payloadTypes, rtpExtensions);
        }
    }

    @Override
    public String toString()
    {
        return "MediaTransceiver(mid=" + mid + ", kind=" + kind + ", direction=" + direction
            + ", sendSsrc=" + sendSsrc + ", remoteSsrc=" + remoteSsrc
            + (stopped ? ", stopped" : "") + ")";
    }
}
