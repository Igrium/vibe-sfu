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
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorHeaderExtension;
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorReader;
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyException;
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure;
import org.jitsi.rtp.rtp.header_extensions.FrameInfo;
import org.jitsi.rtp.util.RtpUtils;
import org.jitsi.utils.logging2.Logger;

class Av1DDFrame
{
    /**
     * The RTP SSRC of the incoming frame that this instance refers to
     * (RFC3550).
     */
    private final long ssrc;

    /**
     * The RTP timestamp of the incoming frame that this instance refers to
     * (RFC3550).
     */
    private final long timestamp;

    /**
     * The earliest RTP sequence number seen of the incoming frame that this instance
     * refers to (RFC3550).
     */
    private int earliestKnownSequenceNumber;

    /**
     * The latest RTP sequence number seen of the incoming frame that this instance
     * refers to (RFC3550).
     */
    private int latestKnownSequenceNumber;

    /**
     * A boolean that indicates whether we've seen the first packet of the frame.
     * If so, its sequence is earliestKnownSequenceNumber.
     */
    private boolean seenStartOfFrame;

    /**
     * A boolean that indicates whether we've seen the last packet of the frame.
     * If so, its sequence is latestKnownSequenceNumber.
     */
    private boolean seenEndOfFrame;

    /**
     * A boolean that indicates whether we've seen a packet with the marker bit set.
     */
    private boolean seenMarker;

    /**
     * AV1 FrameInfo for the frame.
     */
    private FrameInfo frameInfo;

    /**
     * The AV1 DD Frame Number of this frame.
     */
    private final int frameNumber;

    /**
     * The FrameID index (FrameID plus cycles) of this frame.
     */
    private final long index;

    /**
     * The template ID of this frame.
     */
    private final int templateId;

    /**
     * The AV1 Template Dependency Structure in effect for this frame, if known.
     */
    private Av1TemplateDependencyStructure structure;

    /**
     * A new activeDecodeTargets specified for this frame, if any.
     * TODO: is this always specified in all packets of the frame?
     */
    private final Integer activeDecodeTargets;

    /**
     * A boolean that indicates whether the incoming AV1 frame that this
     * instance refers to is a keyframe.
     */
    private boolean isKeyframe;

    /**
     * The raw dependency descriptor included in the packet. Stored if it could not be parsed initially.
     */
    private final RtpPacket.HeaderExtension rawDependencyDescriptor;

    /**
     * A record of how this frame was projected, or null if not.
     */
    private Av1DDFrameProjection projection;

    /**
     * A boolean that records whether this frame was accepted, i.e. should be forwarded to the receiver
     * given the decoding target currently being forwarded.
     */
    private boolean accepted = false;

    Av1DDFrame(
        long ssrc,
        long timestamp,
        int earliestKnownSequenceNumber,
        int latestKnownSequenceNumber,
        boolean seenStartOfFrame,
        boolean seenEndOfFrame,
        boolean seenMarker,
        @Nullable FrameInfo frameInfo,
        int frameNumber,
        long index,
        int templateId,
        @Nullable Av1TemplateDependencyStructure structure,
        @Nullable Integer activeDecodeTargets,
        boolean isKeyframe,
        @Nullable RtpPacket.HeaderExtension rawDependencyDescriptor)
    {
        this.ssrc = ssrc;
        this.timestamp = timestamp;
        this.earliestKnownSequenceNumber = earliestKnownSequenceNumber;
        this.latestKnownSequenceNumber = latestKnownSequenceNumber;
        this.seenStartOfFrame = seenStartOfFrame;
        this.seenEndOfFrame = seenEndOfFrame;
        this.seenMarker = seenMarker;
        this.frameInfo = frameInfo;
        this.frameNumber = frameNumber;
        this.index = index;
        this.templateId = templateId;
        this.structure = structure;
        this.activeDecodeTargets = activeDecodeTargets;
        this.isKeyframe = isKeyframe;
        this.rawDependencyDescriptor = rawDependencyDescriptor;

        // Validate that the index matches the frame number
        assert (int) (index & 0xffff) == frameNumber;
    }

    Av1DDFrame(@NotNull Av1DDPacket packet, long index)
    {
        this(
            packet.getSsrc(),
            packet.getTimestamp(),
            packet.getSequenceNumber(),
            packet.getSequenceNumber(),
            packet.isStartOfFrame(),
            packet.isEndOfFrame(),
            packet.isMarked(),
            packet.getFrameInfo(),
            packet.getStatelessDescriptor().getFrameNumber(),
            index,
            packet.getStatelessDescriptor().getFrameDependencyTemplateId(),
            packet.getDescriptor() != null ? packet.getDescriptor().getStructure() : null,
            packet.getActiveDecodeTargets(),
            packet.isKeyframe(),
            packet.getFrameInfo() == null
                ? cloneHeaderExtension(packet.getHeaderExtension(packet.getAv1DDHeaderExtensionId()))
                : null
        );
    }

    @Nullable
    private static RtpPacket.HeaderExtension cloneHeaderExtension(@Nullable RtpPacket.HeaderExtension ext)
    {
        return ext == null ? null : ext.cloneExtension();
    }

    /**
     * Remember another packet of this frame.
     * Note: this assumes every packet is received only once, i.e. a filter
     * like org.jitsi.nlj.transform.node.incoming.PaddingTermination is in use.
     * @param packet The packet to remember. This should be a packet which
     * has tested true with {@link #matchesFrame(Av1DDPacket)}.
     */
    void addPacket(@NotNull Av1DDPacket packet)
    {
        if (!matchesFrame(packet))
        {
            throw new IllegalArgumentException("Non-matching packet added to frame");
        }
        int seq = packet.getSequenceNumber();
        if (RtpUtils.isOlderThan(seq, earliestKnownSequenceNumber))
        {
            earliestKnownSequenceNumber = seq;
        }
        if (RtpUtils.isNewerThan(seq, latestKnownSequenceNumber))
        {
            latestKnownSequenceNumber = seq;
        }
        if (packet.isStartOfFrame())
        {
            seenStartOfFrame = true;
        }
        if (packet.isEndOfFrame())
        {
            seenEndOfFrame = true;
        }
        if (packet.isMarked())
        {
            seenMarker = true;
        }

        if (structure == null && packet.getDescriptor() != null && packet.getDescriptor().getStructure() != null)
        {
            structure = packet.getDescriptor().getStructure();
        }

        if (frameInfo == null && packet.getFrameInfo() != null)
        {
            frameInfo = packet.getFrameInfo();
        }
    }

    void updateParse(@NotNull Av1TemplateDependencyStructure templateDependencyStructure, @NotNull Logger logger)
    {
        if (rawDependencyDescriptor == null)
        {
            return;
        }
        Av1DependencyDescriptorReader parser = new Av1DependencyDescriptorReader(rawDependencyDescriptor);
        Av1DependencyDescriptorHeaderExtension descriptor;
        try
        {
            descriptor = parser.parse(templateDependencyStructure);
        }
        catch (Av1DependencyException e)
        {
            logger.warn("Could not parse updated AV1 Dependency Descriptor: " + e.getMessage());
            return;
        }
        structure = descriptor.getStructure();
        try
        {
            frameInfo = descriptor.getFrameInfo();
        }
        catch (Av1DependencyException e)
        {
            logger.warn("Could not extract frame info from updated AV1 Dependency Descriptor: " + e.getMessage());
            frameInfo = null;
        }
    }

    /**
     * Small utility method that checks whether the {@link Av1DDFrame} that is
     * specified as a parameter belongs to the same RTP stream as the frame that
     * this instance refers to.
     *
     * @param av1Frame the {@link Av1DDFrame} to check whether it belongs to the
     * same RTP stream as the frame that this instance refers to.
     * @return true if the {@link Av1DDFrame} that is specified as a parameter
     * belongs to the same RTP stream as the frame that this instance refers to,
     * false otherwise.
     */
    boolean matchesSSRC(@NotNull Av1DDFrame av1Frame)
    {
        return ssrc == av1Frame.ssrc;
    }

    /**
     * Checks whether the specified RTP packet is part of this frame.
     *
     * @param pkt the RTP packet to check whether it's part of this frame.
     * @return true if the specified RTP packet is part of this frame, false
     * otherwise.
     */
    boolean matchesFrame(@NotNull Av1DDPacket pkt)
    {
        return ssrc == pkt.getSsrc() && timestamp == pkt.getTimestamp() && frameNumber == pkt.getFrameNumber();
    }

    void validateConsistency(@NotNull Av1DDPacket pkt)
    {
        if (frameInfo == null)
        {
            return;
        }
        if (frameInfo.equals(pkt.getFrameInfo()))
        {
            return;
        }

        throw new RuntimeException("Packet ssrc " + pkt.getSsrc()
            + ", seq " + pkt.getSequenceNumber()
            + ", frame number " + pkt.getFrameNumber()
            + ", timestamp " + pkt.getTimestamp()
            + " packet template " + pkt.getStatelessDescriptor().getFrameDependencyTemplateId()
            + " frame info " + pkt.getFrameInfo()
            + " is not consistent with frame " + this);
    }

    boolean isImmediatelyAfter(@NotNull Av1DDFrame otherFrame)
    {
        return frameNumber == RtpUtils.applySequenceNumberDelta(otherFrame.frameNumber, 1);
    }

    long getSsrc()
    {
        return ssrc;
    }

    long getTimestamp()
    {
        return timestamp;
    }

    int getEarliestKnownSequenceNumber()
    {
        return earliestKnownSequenceNumber;
    }

    int getLatestKnownSequenceNumber()
    {
        return latestKnownSequenceNumber;
    }

    boolean hasSeenStartOfFrame()
    {
        return seenStartOfFrame;
    }

    boolean hasSeenEndOfFrame()
    {
        return seenEndOfFrame;
    }

    boolean hasSeenMarker()
    {
        return seenMarker;
    }

    @Nullable
    FrameInfo getFrameInfo()
    {
        return frameInfo;
    }

    int getFrameNumber()
    {
        return frameNumber;
    }

    long getIndex()
    {
        return index;
    }

    int getTemplateId()
    {
        return templateId;
    }

    @Nullable
    Av1TemplateDependencyStructure getStructure()
    {
        return structure;
    }

    @Nullable
    Integer getActiveDecodeTargets()
    {
        return activeDecodeTargets;
    }

    boolean isKeyframe()
    {
        return isKeyframe;
    }

    void setKeyframe(boolean keyframe)
    {
        isKeyframe = keyframe;
    }

    Av1DDFrameProjection getProjection()
    {
        return projection;
    }

    void setProjection(Av1DDFrameProjection projection)
    {
        this.projection = projection;
    }

    boolean isAccepted()
    {
        return accepted;
    }

    void setAccepted(boolean accepted)
    {
        this.accepted = accepted;
    }

    @Override
    public String toString()
    {
        return ssrc + ", "
            + "seq " + earliestKnownSequenceNumber + "-" + latestKnownSequenceNumber + " "
            + "frame number " + frameNumber + ", timestamp " + timestamp + ": "
            + "frame template " + templateId + " info " + frameInfo;
    }
}
