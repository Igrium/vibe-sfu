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
import org.jitsi.nlj.codec.vpx.VpxUtils;
import org.jitsi.nlj.rtp.VideoRtpPacket;
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet;
import org.jitsi.rtp.util.RtpUtils;

/**
 * Groups together some RTP/VP9 fields that refer to a specific incoming VP9
 * frame. Most of those fields are final and cannot be changed, with the
 * exception of the ending and max sequence number that may be unknown at the
 * time of the creation of this instance.
 *
 * Instances of this class are *NOT* thread safe. While most internal state of
 * this class instances is final, the sequence number ranges, haveStart/haveEnd,
 * and isKeyframe are not.
 *
 * @author Jonathan Lennox
 */
class Vp9Frame
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
     * A boolean that indicates whether or not we've seen the first packet of the frame.
     * If so, its sequence is earliestKnownSequenceNumber.
     */
    private boolean seenStartOfFrame;

    /**
     * A boolean that indicates whether or not we've seen the last packet of the frame.
     * If so, its sequence is latestKnownSequenceNumber.
     */
    private boolean seenEndOfFrame;

    /**
     * A boolean that indicates whether we've seen a packet with the marker bit set.
     */
    private boolean seenMarker;

    /**
     * The temporal layer of this frame.
     */
    private final int temporalLayer;

    /**
     * The spatial layer of this frame.
     */
    private final int spatialLayer;

    /**
     * Whether the frame is used as an upper level reference.
     */
    private final boolean isUpperLevelReference;

    /**
     * Whether the frame is a temporal switching-up point.
     */
    private final boolean isSwitchingUpPoint;

    /**
     * Whether the frame uses inter-layer dependency.
     */
    private final boolean usesInterLayerDependency;

    /**
     * Whether the frame is inter-picture predicted.
     */
    private final boolean isInterPicturePredicted;

    /**
     * The VP9 PictureID of the incoming VP9 frame that this instance refers to.
     */
    private final int pictureId;

    /**
     * The PictureID index (PictureID plus cycles) of this frame.
     */
    private final long index;

    /**
     * The VP9 TL0PICIDX of the incoming VP9 frame that this instance refers to
     * (RFC7741).
     */
    private final int tl0PICIDX;

    /**
     * A boolean that indicates whether the incoming VP9 frame that this
     * instance refers to is a keyframe.
     */
    private boolean isKeyframe;

    /**
     * The number of spatial layers reported by this frame's scalability structure,
     * if it has one, otherwise -1.
     */
    private int numSpatialLayers;

    /**
     * A record of how this frame was projected, or null if not.
     */
    private Vp9FrameProjection projection;

    /**
     * A boolean that records whether this frame was accepted, i.e. should be forwarded to the receiver
     * given the layer currently being forwarded.
     */
    private boolean accepted = false;

    Vp9Frame(
        long ssrc,
        long timestamp,
        int earliestKnownSequenceNumber,
        int latestKnownSequenceNumber,
        boolean seenStartOfFrame,
        boolean seenEndOfFrame,
        boolean seenMarker,
        int temporalLayer,
        int spatialLayer,
        boolean isUpperLevelReference,
        boolean isSwitchingUpPoint,
        boolean usesInterLayerDependency,
        boolean isInterPicturePredicted,
        int pictureId,
        long index,
        int tl0PICIDX,
        boolean isKeyframe,
        int numSpatialLayers)
    {
        this.ssrc = ssrc;
        this.timestamp = timestamp;
        this.earliestKnownSequenceNumber = earliestKnownSequenceNumber;
        this.latestKnownSequenceNumber = latestKnownSequenceNumber;
        this.seenStartOfFrame = seenStartOfFrame;
        this.seenEndOfFrame = seenEndOfFrame;
        this.seenMarker = seenMarker;
        this.temporalLayer = temporalLayer;
        this.spatialLayer = spatialLayer;
        this.isUpperLevelReference = isUpperLevelReference;
        this.isSwitchingUpPoint = isSwitchingUpPoint;
        this.usesInterLayerDependency = usesInterLayerDependency;
        this.isInterPicturePredicted = isInterPicturePredicted;
        this.pictureId = pictureId;
        this.index = index;
        this.tl0PICIDX = tl0PICIDX;
        this.isKeyframe = isKeyframe;
        this.numSpatialLayers = numSpatialLayers;

        // Validate that the index matches the pictureId
        assert (int) (index & 0x7fff) == pictureId;
    }

    Vp9Frame(@NotNull Vp9Packet packet, long index)
    {
        this(
            packet.getSsrc(),
            packet.getTimestamp(),
            packet.getSequenceNumber(),
            packet.getSequenceNumber(),
            packet.isStartOfFrame(),
            packet.isEndOfFrame(),
            packet.isMarked(),
            packet.getTemporalLayerIndex(),
            packet.getSpatialLayerIndex(),
            packet.isUpperLevelReference(),
            packet.isSwitchingUpPoint(),
            packet.usesInterLayerDependency(),
            packet.isInterPicturePredicted(),
            packet.getPictureId(),
            index,
            packet.getTL0PICIDX(),
            packet.isKeyframe(),
            packet.getScalabilityStructureNumSpatial()
        );
    }

    /**
     * Remember another packet of this frame.
     * Note: this assumes every packet is received only once, i.e. a filter
     * like org.jitsi.nlj.transform.node.incoming.PaddingTermination is in use.
     * @param packet The packet to remember. This should be a packet which
     * has tested true with {@link #matchesFrame(Vp9Packet)}.
     */
    void addPacket(@NotNull Vp9Packet packet)
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
        if (packet.hasScalabilityStructure())
        {
            numSpatialLayers = packet.getScalabilityStructureNumSpatial();
        }
    }

    /**
     * @return true if this is a base temporal layer frame, false otherwise
     * @note We treat unknown temporal layer frames as TL0.
     */
    boolean isTL0()
    {
        return temporalLayer <= 0;
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

    int getTemporalLayer()
    {
        return temporalLayer;
    }

    int getSpatialLayer()
    {
        return spatialLayer;
    }

    boolean isUpperLevelReference()
    {
        return isUpperLevelReference;
    }

    boolean isSwitchingUpPoint()
    {
        return isSwitchingUpPoint;
    }

    boolean usesInterLayerDependency()
    {
        return usesInterLayerDependency;
    }

    boolean isInterPicturePredicted()
    {
        return isInterPicturePredicted;
    }

    int getPictureId()
    {
        return pictureId;
    }

    long getIndex()
    {
        return index;
    }

    int getTl0PICIDX()
    {
        return tl0PICIDX;
    }

    boolean isKeyframe()
    {
        return isKeyframe;
    }

    void setKeyframe(boolean keyframe)
    {
        isKeyframe = keyframe;
    }

    int getNumSpatialLayers()
    {
        return numSpatialLayers;
    }

    Vp9FrameProjection getProjection()
    {
        return projection;
    }

    void setProjection(Vp9FrameProjection projection)
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

    /**
     * The "effective" spatial layer of the frame, i.e. the spatial layer, or 0 if the frame does not have layer
     * information.
     */
    int getEffectiveSpatialLayer()
    {
        return spatialLayer >= 0 ? spatialLayer : 0;
    }

    /**
     * Small utility method that checks whether the {@link Vp9Frame} that is
     * specified as a parameter belongs to the same RTP stream as the frame that
     * this instance refers to.
     *
     * @param vp9Frame the {@link Vp9Frame} to check whether it belongs to the
     * same RTP stream as the frame that this instance refers to.
     * @return true if the {@link Vp9Frame} that is specified as a parameter
     * belongs to the same RTP stream as the frame that this instance refers to,
     * false otherwise.
     */
    boolean matchesSSRC(@NotNull Vp9Frame vp9Frame)
    {
        return ssrc == vp9Frame.ssrc;
    }

    /**
     * Determines whether the {@link VideoRtpPacket} that is specified as an
     * argument is part of the VP9 picture that is represented by this
     * {@link Vp9Frame} instance.
     */
    private boolean matchesSSRC(@NotNull VideoRtpPacket pkt)
    {
        return ssrc == pkt.getSsrc();
    }

    /**
     * Checks whether the specified RTP packet is part of this frame.
     *
     * @param pkt the RTP packet to check whether it's part of this frame.
     * @return true if the specified RTP packet is part of this frame, false
     * otherwise.
     */
    boolean matchesFrame(@NotNull Vp9Packet pkt)
    {
        return matchesSSRC(pkt) && timestamp == pkt.getTimestamp() &&
            spatialLayer == pkt.getSpatialLayerIndex();
    }

    /**
     * Validates that the specified RTP packet consistently matches all the
     * parameters of this frame.
     *
     * This can be useful for diagnosing invalid streams if this fails when
     * {@link #matchesFrame(Vp9Packet)} is true.
     *
     * @param pkt the RTP packet to check whether its parameters match this frame.
     * @throws RuntimeException if the specified RTP packet is inconsistent with this frame
     */
    void validateConsistency(@NotNull Vp9Packet pkt)
    {
        if (temporalLayer == pkt.getTemporalLayerIndex() &&
            tl0PICIDX == pkt.getTL0PICIDX() &&
            pictureId == pkt.getPictureId() &&
            isSwitchingUpPoint == pkt.isSwitchingUpPoint() &&
            isUpperLevelReference == pkt.isUpperLevelReference() &&
            usesInterLayerDependency == pkt.usesInterLayerDependency() &&
            isInterPicturePredicted == pkt.isInterPicturePredicted()
            /* TODO: also check start, end, seq nums? */
        )
        {
            return;
        }

        StringBuilder s = new StringBuilder().append("Packet")
            .append(" ssrc ").append(pkt.getSsrc())
            .append(", seq ").append(pkt.getSequenceNumber())
            .append(", picture id ").append(pkt.getPictureId())
            .append(", timestamp ").append(pkt.getTimestamp())
            .append(" is not consistent with frame")
            .append(" ssrc ").append(ssrc)
            .append(", seq ").append(earliestKnownSequenceNumber).append("-").append(latestKnownSequenceNumber)
            .append(", picture id ").append(pictureId)
            .append(", timestamp ").append(timestamp)
            .append(": ");

        boolean complained = false;

        if (temporalLayer != pkt.getTemporalLayerIndex())
        {
            s.append("packet temporal layer ")
                .append(pkt.getTemporalLayerIndex())
                .append(" != frame temporal layer ")
                .append(temporalLayer);
            complained = true;
        }
        if (tl0PICIDX != pkt.getTL0PICIDX())
        {
            if (complained)
            {
                s.append("; ");
            }
            s.append("packet TL0PICIDX ")
                .append(pkt.getTL0PICIDX())
                .append(" != frame TL0PICIDX ")
                .append(tl0PICIDX);
            complained = true;
        }
        if (pictureId != pkt.getPictureId())
        {
            if (complained)
            {
                s.append("; ");
            }
            s.append("packet PictureID ")
                .append(pkt.getPictureId())
                .append(" != frame PictureID ")
                .append(pictureId);
            complained = true;
        }
        if (isSwitchingUpPoint != pkt.isSwitchingUpPoint())
        {
            if (complained)
            {
                s.append("; ");
            }
            s.append("packet switchingUpPoint ")
                .append(pkt.isSwitchingUpPoint())
                .append(" != frame switchingUpPoint ")
                .append(isSwitchingUpPoint);
            complained = true;
        }
        if (isUpperLevelReference != pkt.isUpperLevelReference())
        {
            if (complained)
            {
                s.append("; ");
            }
            s.append("packet upperLevelReference ")
                .append(pkt.isUpperLevelReference())
                .append(" != frame upperLevelReference ")
                .append(isUpperLevelReference);
            complained = true;
        }
        if (usesInterLayerDependency != pkt.usesInterLayerDependency())
        {
            if (complained)
            {
                s.append("; ");
            }
            s.append("packet usesInterLayerDepencency ")
                .append(pkt.usesInterLayerDependency())
                .append(" != frame usesInterLayerDepencency ")
                .append(usesInterLayerDependency);
            complained = true;
        }
        if (isInterPicturePredicted != pkt.isInterPicturePredicted())
        {
            if (complained)
            {
                s.append("; ");
            }
            s.append("packet isInterPicturePredicted ")
                .append(pkt.isInterPicturePredicted())
                .append(" != frame isInterPicturePredicted ")
                .append(isInterPicturePredicted);
        }
        throw new RuntimeException(s.toString());
    }

    /**
     * Check whether this frame is immediately after another one, according
     * to their extended picture IDs and spatial layers.
     */
    boolean isImmediatelyAfter(@NotNull Vp9Frame otherFrame)
    {
        int delta = VpxUtils.getExtendedPictureIdDelta(otherFrame.pictureId, pictureId);

        if (delta == 0)
        {
            return spatialLayer == otherFrame.spatialLayer + 1;
        }
        else if (delta == 1)
        {
            return spatialLayer == 0 && otherFrame.spatialLayer == 2; // ???
        }
        else
        {
            return false;
        }
    }
}
