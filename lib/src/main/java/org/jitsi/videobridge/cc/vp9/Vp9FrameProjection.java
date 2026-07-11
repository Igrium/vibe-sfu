/*
 * Copyright @ 2019 8x8, Inc
 *
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
package org.jitsi.videobridge.cc.vp9;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet;
import org.jitsi.rtp.util.RtpUtils;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;

import java.time.Instant;

/**
 * Represents a VP9 frame projection. It puts together all the necessary bits
 * and pieces that are useful when projecting an accepted VP9 frame. A
 * projection is responsible for rewriting a VP9 packet. Instances of this class
 * are thread-safe.
 */
class Vp9FrameProjection
{
    /**
     * The time series logger for this class.
     */
    private static final TimeSeriesLogger timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(
        Vp9FrameProjection.class);

    /**
     * The diagnostic context for this instance.
     */
    private final DiagnosticContext diagnosticContext;

    /**
     * The projected {@link Vp9Frame}.
     */
    private final Vp9Frame vp9Frame;

    /**
     * The RTP SSRC of the projection (RFC7667, RFC3550).
     */
    private final long ssrc;

    /**
     * The RTP timestamp of the projection (RFC7667, RFC3550).
     */
    private final long timestamp;

    /**
     * The sequence number delta for packets of this frame.
     */
    private final int sequenceNumberDelta;

    /**
     * The VP9 picture ID of the projection.
     */
    private final int pictureId;

    /**
     * The VP9 TL0PICIDX of the projection.
     */
    private final int tl0PICIDX;

    /**
     * Whether to add a marker bit to the last packet of this frame.
     * (Note this will not clear already-existing marker bits.)
     */
    private final boolean mark;

    /**
     * A timestamp of when this instance was created. It's used to calculate
     * RTP timestamps when we switch encodings.
     */
    private final @Nullable Instant created;

    /**
     * -1 if this projection is still "open" for new, later packets.
     * Projections can be closed when we switch away from their encodings.
     */
    private int closedSeq = -1;

    /**
     * Ctor.
     *
     * @param ssrc the SSRC of the destination VP9 picture.
     * @param timestamp The RTP timestamp of the projected frame that this
     * instance refers to (RFC3550).
     * @param sequenceNumberDelta The starting RTP sequence number of the
     * projected frame that this instance refers to (RFC3550).
     */
    Vp9FrameProjection(
        @NotNull DiagnosticContext diagnosticContext,
        long ssrc, int sequenceNumberDelta, long timestamp)
    {
        this(diagnosticContext, null /* vp9Frame */, ssrc, timestamp,
            sequenceNumberDelta, 0 /* pictureId */,
            0 /* tl0PICIDX */, false /* mark */, null /* created */);
    }

    /**
     * Ctor.
     *
     * @param vp9Frame The {@link Vp9Frame} that's projected.
     * @param ssrc The RTP SSRC of the projected frame that this instance refers
     * to (RFC3550).
     * @param timestamp The RTP timestamp of the projected frame that this
     * instance refers to (RFC3550).
     * @param sequenceNumberDelta The starting RTP sequence number of the
     * projected frame that this instance refers to (RFC3550).
     * @param pictureId The VP9 extended picture ID of the projected VP9
     * frame that this instance refers to.
     * @param tl0PICIDX The VP9 TL0PICIDX of the projected VP9 frame that this
     * instance refers to.
     */
    Vp9FrameProjection(
        @NotNull DiagnosticContext diagnosticContext,
        @Nullable Vp9Frame vp9Frame,
        long ssrc, long timestamp, int sequenceNumberDelta,
        int pictureId, int tl0PICIDX, boolean mark, @Nullable Instant created)
    {
        this.diagnosticContext = diagnosticContext;
        this.vp9Frame = vp9Frame;
        this.ssrc = ssrc;
        this.timestamp = timestamp;
        this.sequenceNumberDelta = sequenceNumberDelta;
        this.pictureId = pictureId;
        this.tl0PICIDX = tl0PICIDX;
        this.mark = mark;
        this.created = created;
    }

    /**
     * @return the closing sequence number of this projection, or -1 if it is still "open".
     */
    int getClosedSeq()
    {
        return closedSeq;
    }

    int rewriteSeqNo(int seq)
    {
        return RtpUtils.applySequenceNumberDelta(seq, sequenceNumberDelta);
    }

    /**
     * Rewrites an RTP packet.
     *
     * @param pkt the RTP packet to rewrite.
     */
    void rewriteRtp(@NotNull Vp9Packet pkt)
    {
        int sequenceNumber = rewriteSeqNo(pkt.getSequenceNumber());

        if (timeSeriesLogger.isTraceEnabled())
        {
            timeSeriesLogger.trace(diagnosticContext
                .makeTimeSeriesPoint("rtp_vp9_rewrite")
                .addField("orig.rtp.ssrc", pkt.getSsrc())
                .addField("orig.rtp.timestamp", pkt.getTimestamp())
                .addField("orig.rtp.seq", pkt.getSequenceNumber())
                .addField("orig.vp9.pictureid", pkt.getPictureId())
                .addField("orig.vp9.tl0picidx", pkt.getTL0PICIDX())
                .addField("proj.rtp.ssrc", ssrc)
                .addField("proj.rtp.timestamp", timestamp)
                .addField("proj.rtp.seq", sequenceNumber)
                .addField("proj.vp9.pictureid", pictureId)
                .addField("proj.vp9.tl0picidx", tl0PICIDX)
                .addField("proj.rtp.mark", mark));
        }

        // update ssrc, sequence number, timestamp, pictureId and tl0picidx
        pkt.setSsrc(ssrc);
        pkt.setTimestamp(timestamp);
        pkt.setSequenceNumber(sequenceNumber);
        if (pkt.hasTL0PICIDX())
        {
            pkt.setTL0PICIDX(tl0PICIDX);
        }
        pkt.setPictureId(pictureId);
        if (mark && pkt.isEndOfFrame())
        {
            pkt.setMarked(true);
        }
    }

    /**
     * Determines whether a packet can be forwarded as part of this
     * {@link Vp9FrameProjection} instance. The check is based on the sequence
     * of the incoming packet and whether or not the {@link Vp9FrameProjection}
     * has been "closed" or not.
     *
     * @param rtpPacket the {@link Vp9Packet} that will be examined.
     * @return true if the packet can be forwarded as part of this
     * {@link Vp9FrameProjection}, false otherwise.
     */
    boolean accept(@NotNull Vp9Packet rtpPacket)
    {
        if (vp9Frame == null || !vp9Frame.matchesFrame(rtpPacket))
        {
            // The packet does not belong to this VP9 picture.
            return false;
        }
        synchronized (vp9Frame)
        {
            if (closedSeq < 0)
            {
                return true;
            }
            return RtpUtils.isOlderThan(rtpPacket.getSequenceNumber(), closedSeq);
        }
    }

    /**
     * @return The projected {@link Vp9Frame}.
     */
    @Nullable
    Vp9Frame getVp9Frame()
    {
        return vp9Frame;
    }

    /**
     * @return The RTP SSRC of this projection.
     */
    long getSSRC()
    {
        return ssrc;
    }

    /**
     * @return The RTP timestamp of this projection.
     */
    long getTimestamp()
    {
        return timestamp;
    }

    /**
     * @return The picture ID of this projection.
     */
    int getPictureId()
    {
        return pictureId;
    }

    /**
     * @return The TL0PICIDX of this projection.
     */
    int getTl0PICIDX()
    {
        return tl0PICIDX;
    }

    /**
     * @return whether a marker bit should be added to the last packet of this frame.
     */
    boolean isMark()
    {
        return mark;
    }

    /**
     * @return The system time this projection was created.
     */
    @Nullable
    Instant getCreated()
    {
        return created;
    }

    int getEarliestProjectedSeqNum()
    {
        if (vp9Frame == null)
        {
            return sequenceNumberDelta;
        }
        synchronized (vp9Frame)
        {
            return rewriteSeqNo(vp9Frame.getEarliestKnownSequenceNumber());
        }
    }

    int getLatestProjectedSeqNum()
    {
        if (vp9Frame == null)
        {
            return sequenceNumberDelta;
        }
        synchronized (vp9Frame)
        {
            return rewriteSeqNo(vp9Frame.getLatestKnownSequenceNumber());
        }
    }

    /**
     * Prevents the max sequence number of this frame to grow any further.
     */
    void close()
    {
        if (vp9Frame != null)
        {
            synchronized (vp9Frame)
            {
                closedSeq = vp9Frame.getLatestKnownSequenceNumber();
            }
        }
    }
}
