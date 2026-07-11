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
package org.jitsi.videobridge.cc.av1;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket;
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorHeaderExtension;
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure;
import org.jitsi.rtp.rtp.header_extensions.DTI;
import org.jitsi.rtp.rtp.header_extensions.FrameInfo;
import org.jitsi.rtp.util.RtpUtils;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;

import java.time.Instant;

/**
 * Represents an AV1 DD frame projection. It puts together all the necessary bits
 * and pieces that are useful when projecting an accepted AV1 DD frame. A
 * projection is responsible for rewriting a AV1 DD packet. Instances of this class
 * are thread-safe.
 */
class Av1DDFrameProjection
{
    /**
     * The time series logger for this class.
     */
    private static final TimeSeriesLogger timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(
        Av1DDFrameProjection.class);

    /**
     * The diagnostic context for this instance.
     */
    private final DiagnosticContext diagnosticContext;

    /**
     * The projected {@link Av1DDFrame}.
     */
    private final Av1DDFrame av1Frame;

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
     * The AV1 frame number of the projection.
     */
    private final int frameNumber;

    /**
     * The template ID delta for this frame. This applies both to the frame's own template ID, and to
     * the template ID offset in the dependency structure, if present.
     */
    private final int templateIdDelta;

    /**
     * The decode target indication to set on this frame, if any.
     */
    private final Integer dti;

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
     * @param ssrc the SSRC of the destination AV1 frame.
     * @param timestamp The RTP timestamp of the projected frame that this
     * instance refers to (RFC3550).
     * @param sequenceNumberDelta The starting RTP sequence number of the
     * projected frame that this instance refers to (RFC3550).
     */
    Av1DDFrameProjection(
        @NotNull DiagnosticContext diagnosticContext,
        long ssrc, int sequenceNumberDelta, long timestamp,
        @Nullable Integer frameNumber, @Nullable Integer templateId)
    {
        this(diagnosticContext, null /* av1Frame */, ssrc, timestamp,
            sequenceNumberDelta, frameNumber != null ? frameNumber : 0,
            templateId != null ? templateId : -1, null /* dti */, false /* mark */, null /* created */);
    }

    /**
     * Ctor.
     *
     * @param av1Frame The {@link Av1DDFrame} that's projected.
     * @param ssrc The RTP SSRC of the projected frame that this instance refers
     * to (RFC3550).
     * @param timestamp The RTP timestamp of the projected frame that this
     * instance refers to (RFC3550).
     * @param sequenceNumberDelta The starting RTP sequence number of the
     * projected frame that this instance refers to (RFC3550).
     * @param frameNumber The AV1 frame number of the projection.
     * @param templateIdDelta The template ID delta for this frame.
     * @param dti The decode target indication to set on this frame, if any.
     */
    Av1DDFrameProjection(
        @NotNull DiagnosticContext diagnosticContext,
        @Nullable Av1DDFrame av1Frame,
        long ssrc, long timestamp, int sequenceNumberDelta,
        int frameNumber, int templateIdDelta, @Nullable Integer dti, boolean mark, @Nullable Instant created)
    {
        this.diagnosticContext = diagnosticContext;
        this.av1Frame = av1Frame;
        this.ssrc = ssrc;
        this.timestamp = timestamp;
        this.sequenceNumberDelta = sequenceNumberDelta;
        this.frameNumber = frameNumber;
        this.templateIdDelta = templateIdDelta;
        this.dti = dti;
        this.mark = mark;
        this.created = created;
    }

    int rewriteSeqNo(int seq)
    {
        return RtpUtils.applySequenceNumberDelta(seq, sequenceNumberDelta);
    }

    int rewriteTemplateId(int id)
    {
        return Av1DDPacket.applyTemplateIdDelta(id, templateIdDelta);
    }

    /**
     * Rewrites an RTP packet.
     *
     * @param pkt the RTP packet to rewrite.
     */
    void rewriteRtp(@NotNull Av1DDPacket pkt)
    {
        int sequenceNumber = rewriteSeqNo(pkt.getSequenceNumber());
        int templateId = rewriteTemplateId(pkt.getStatelessDescriptor().getFrameDependencyTemplateId());

        if (timeSeriesLogger.isTraceEnabled())
        {
            FrameInfo pktFrameInfo = pkt.getFrameInfo();
            timeSeriesLogger.trace(diagnosticContext
                .makeTimeSeriesPoint("rtp_av1_rewrite")
                .addField("orig.rtp.ssrc", pkt.getSsrc())
                .addField("orig.rtp.timestamp", pkt.getTimestamp())
                .addField("orig.rtp.seq", pkt.getSequenceNumber())
                .addField("orig.av1.framenum", pkt.getFrameNumber())
                .addField("orig.av1.dti", pktFrameInfo != null ? DTI.toShortString(pktFrameInfo.getDti()) : "-")
                .addField("orig.av1.templateid", pkt.getStatelessDescriptor().getFrameDependencyTemplateId())
                .addField("proj.rtp.ssrc", ssrc)
                .addField("proj.rtp.timestamp", timestamp)
                .addField("proj.rtp.seq", sequenceNumber)
                .addField("proj.av1.framenum", frameNumber)
                .addField("proj.av1.dti", dti != null ? dti : -1)
                .addField("proj.av1.templateid", templateId)
                .addField("proj.rtp.mark", mark));
        }

        // update ssrc, sequence number, timestamp, frameNumber, and templateID
        pkt.setSsrc(ssrc);
        pkt.setTimestamp(timestamp);
        pkt.setSequenceNumber(sequenceNumber);
        if (mark && pkt.isEndOfFrame())
        {
            pkt.setMarked(true);
        }

        Av1DependencyDescriptorHeaderExtension descriptor = pkt.getDescriptor();
        if (descriptor != null && (
            frameNumber != pkt.getFrameNumber() || templateId != descriptor.getFrameDependencyTemplateId()
                || dti != null))
        {
            descriptor.setFrameNumber(frameNumber);
            descriptor.setFrameDependencyTemplateId(templateId);
            Av1TemplateDependencyStructure structure = descriptor.getStructure();
            if (!(descriptor.getNewTemplateDependencyStructure() == null
                || descriptor.getNewTemplateDependencyStructure() == descriptor.getStructure()))
            {
                throw new IllegalStateException(
                    "newTemplateDependencyStructure must be null or equal to structure");
            }

            structure.setTemplateIdOffset(rewriteTemplateId(structure.getTemplateIdOffset()));
            if (dti != null && (
                descriptor.getNewTemplateDependencyStructure() == null
                    || dti != (1 << structure.getDecodeTargetCount()) - 1))
            {
                descriptor.setActiveDecodeTargetsBitmask(dti);
            }

            pkt.setDescriptor(descriptor);
            pkt.reencodeDdExt();
        }
    }

    /**
     * Determines whether a packet can be forwarded as part of this
     * {@link Av1DDFrameProjection} instance. The check is based on the sequence
     * of the incoming packet and whether or not the {@link Av1DDFrameProjection}
     * has been "closed" or not.
     *
     * @param rtpPacket the {@link Av1DDPacket} that will be examined.
     * @return true if the packet can be forwarded as part of this
     * {@link Av1DDFrameProjection}, false otherwise.
     */
    boolean accept(@NotNull Av1DDPacket rtpPacket)
    {
        if (av1Frame == null || !av1Frame.matchesFrame(rtpPacket))
        {
            // The packet does not belong to this AV1 picture.
            return false;
        }
        synchronized (av1Frame)
        {
            if (closedSeq < 0)
            {
                return true;
            }
            return RtpUtils.isOlderThan(rtpPacket.getSequenceNumber(), closedSeq);
        }
    }

    @Nullable
    Av1DDFrame getAv1Frame()
    {
        return av1Frame;
    }

    long getSSRC()
    {
        return ssrc;
    }

    long getTimestamp()
    {
        return timestamp;
    }

    int getFrameNumber()
    {
        return frameNumber;
    }

    int getTemplateIdDelta()
    {
        return templateIdDelta;
    }

    @Nullable
    Integer getDti()
    {
        return dti;
    }

    boolean isMark()
    {
        return mark;
    }

    @Nullable
    Instant getCreated()
    {
        return created;
    }

    int getClosedSeq()
    {
        return closedSeq;
    }

    int getEarliestProjectedSeqNum()
    {
        if (av1Frame == null)
        {
            return sequenceNumberDelta;
        }
        synchronized (av1Frame)
        {
            return rewriteSeqNo(av1Frame.getEarliestKnownSequenceNumber());
        }
    }

    int getLatestProjectedSeqNum()
    {
        if (av1Frame == null)
        {
            return sequenceNumberDelta;
        }
        synchronized (av1Frame)
        {
            return rewriteSeqNo(av1Frame.getLatestKnownSequenceNumber());
        }
    }

    /**
     * Prevents the max sequence number of this frame to grow any further.
     */
    void close()
    {
        if (av1Frame != null)
        {
            synchronized (av1Frame)
            {
                closedSeq = av1Frame.getLatestKnownSequenceNumber();
            }
        }
    }

    /**
     * Get the next template ID that would come after the template IDs in this projection's structure.
     */
    @Nullable
    Integer getNextTemplateId()
    {
        if (av1Frame == null && templateIdDelta != -1)
        {
            return templateIdDelta;
        }
        if (av1Frame == null)
        {
            return null;
        }
        Av1TemplateDependencyStructure structure = av1Frame.getStructure();
        if (structure == null)
        {
            return null;
        }
        return rewriteTemplateId(structure.getTemplateIdOffset() + structure.getTemplateCount());
    }
}
