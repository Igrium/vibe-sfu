/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.rtp.codec.vp9;

import org.jitsi.nlj.RtpEncodingDesc;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.rtp.ParsedVideoPacket;
import org.jitsi.nlj.rtp.codec.vpx.VpxRtpLayerDesc;
import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;
import org.jitsi_modified.impl.neomedia.codec.video.vp9.DePacketizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * If this {@link Vp9Packet} instance is being created via a clone,
 * we've already parsed the packet itself and determined whether
 * or not its a keyframe and what its spatial layer index is,
 * so the constructor allows passing in those values if
 * they're already known.  If they're null, this instance
 * will do the parsing itself.
 */
public class Vp9Packet extends ParsedVideoPacket
{
    private static final Logger logger = new LoggerImpl(Vp9Packet.class.getName());

    private static final int Y_BIT = 1 << 4;
    private static final int G_BIT = 1 << 3;

    private static final int MAX_NUM_TLAYERS = 8;

    private final boolean isKeyframe;
    private final boolean isStartOfFrame;
    private final boolean isEndOfFrame;
    private final boolean hasLayerIndices;
    private final boolean hasPictureId;
    private final boolean hasExtendedPictureId;
    private final boolean isFlexibleMode;
    private final boolean hasScalabilityStructure;
    private final boolean isUpperLevelReference;
    private final boolean isInterPicturePredicted;
    private int tl0PicIdx;
    private int pictureId;
    private final int temporalLayerIndex;
    private final int spatialLayerIndex;
    private final boolean isSwitchingUpPoint;
    private final boolean usesInterLayerDependency;

    private Vp9Packet(
        byte[] buffer,
        int offset,
        int length,
        Boolean isKeyframe,
        Boolean isStartOfFrame,
        Boolean isEndOfFrame,
        int encodingId,
        Integer pictureId,
        Integer tl0PicIdx
    )
    {
        super(buffer, offset, length, encodingId);

        this.isKeyframe = isKeyframe != null ? isKeyframe :
            DePacketizer.VP9PayloadDescriptor.isKeyFrame(this.buffer, getPayloadOffset(), getPayloadLength());

        this.isStartOfFrame = isStartOfFrame != null ? isStartOfFrame :
            DePacketizer.VP9PayloadDescriptor.isStartOfFrame(buffer, getPayloadOffset(), getPayloadLength());

        this.isEndOfFrame = isEndOfFrame != null ? isEndOfFrame :
            DePacketizer.VP9PayloadDescriptor.isEndOfFrame(buffer, getPayloadOffset(), getPayloadLength());

        this.hasLayerIndices =
            DePacketizer.VP9PayloadDescriptor.hasLayerIndices(buffer, getPayloadOffset(), getPayloadLength());

        this.hasPictureId =
            DePacketizer.VP9PayloadDescriptor.hasPictureId(buffer, getPayloadOffset(), getPayloadLength());

        this.hasExtendedPictureId =
            DePacketizer.VP9PayloadDescriptor.hasExtendedPictureId(buffer, getPayloadOffset(), getPayloadLength());

        this.isFlexibleMode =
            DePacketizer.VP9PayloadDescriptor.isFlexibleMode(buffer, getPayloadOffset(), getPayloadLength());

        this.hasScalabilityStructure =
            DePacketizer.VP9PayloadDescriptor.hasScalabilityStructure(buffer, getPayloadOffset(), getPayloadLength());

        this.isUpperLevelReference =
            DePacketizer.VP9PayloadDescriptor.isUpperLevelReference(buffer, getPayloadOffset(), getPayloadLength());

        this.isInterPicturePredicted =
            DePacketizer.VP9PayloadDescriptor.isInterPicturePredicted(buffer, getPayloadOffset(), getPayloadLength());

        this.tl0PicIdx = tl0PicIdx != null ? tl0PicIdx :
            DePacketizer.VP9PayloadDescriptor.getTL0PICIDX(buffer, getPayloadOffset(), getPayloadLength());

        this.pictureId = pictureId != null ? pictureId :
            DePacketizer.VP9PayloadDescriptor.getPictureId(buffer, getPayloadOffset(), getPayloadLength());

        /* TODO: avoid recomputing these on clone */

        this.temporalLayerIndex =
            DePacketizer.VP9PayloadDescriptor.getTemporalLayerIndex(buffer, getPayloadOffset(), getPayloadLength());

        this.spatialLayerIndex =
            DePacketizer.VP9PayloadDescriptor.getSpatialLayerIndex(buffer, getPayloadOffset(), getPayloadLength());

        this.isSwitchingUpPoint =
            DePacketizer.VP9PayloadDescriptor.isSwitchingUpPoint(buffer, getPayloadOffset(), getPayloadLength());

        this.usesInterLayerDependency =
            DePacketizer.VP9PayloadDescriptor.usesInterLayerDependency(buffer, getPayloadOffset(), getPayloadLength());
    }

    public Vp9Packet(byte[] buffer, int offset, int length)
    {
        this(
            buffer, offset, length,
            /* isKeyframe */ null,
            /* isStartOfFrame */ null,
            /* isEndOfFrame */ null,
            RtpLayerDesc.SUSPENDED_ENCODING_ID,
            /* pictureId */ null,
            /* TL0PICIDX */ null
        );
    }

    @Override
    public boolean isKeyframe()
    {
        return isKeyframe;
    }

    @Override
    public boolean isStartOfFrame()
    {
        return isStartOfFrame;
    }

    @Override
    public boolean isEndOfFrame()
    {
        return isEndOfFrame;
    }

    @Override
    public boolean meetsRoutingNeeds()
    {
        // Question: should we include hasLayerIndices here?  I.e. if we get a VP9 packet with an AV1 DD and
        // a VP9 picture ID, but no VP9 layer indices, are we better off parsing it as VP9 or AV1?
        return hasPictureId;
    }

    @Override
    public Collection<Integer> getLayerIds()
    {
        if (hasLayerIndices)
        {
            return Collections.singletonList(RtpLayerDesc.getIndex(0, spatialLayerIndex, temporalLayerIndex));
        }
        else
        {
            return super.getLayerIds();
        }
    }

    /** End of VP9 picture is the marker bit. Note frame/picture distinction. */
    /* TODO: not sure this should be the override from ParsedVideoPacket */
    public boolean isEndOfPicture()
    {
        return isMarked();
    }

    public boolean hasLayerIndices()
    {
        return hasLayerIndices;
    }

    public boolean hasPictureId()
    {
        return hasPictureId;
    }

    public boolean hasExtendedPictureId()
    {
        return hasExtendedPictureId;
    }

    public boolean isFlexibleMode()
    {
        return isFlexibleMode;
    }

    public boolean hasScalabilityStructure()
    {
        return hasScalabilityStructure;
    }

    public boolean isUpperLevelReference()
    {
        return isUpperLevelReference;
    }

    public boolean isInterPicturePredicted()
    {
        return isInterPicturePredicted;
    }

    public int getTL0PICIDX()
    {
        return tl0PicIdx;
    }

    public void setTL0PICIDX(int newValue)
    {
        if (newValue != -1 && !DePacketizer.VP9PayloadDescriptor.setTL0PICIDX(
            buffer,
            getPayloadOffset(),
            getPayloadLength(),
            newValue
        ))
        {
            logger.warn(() -> "Failed to set the TL0PICIDX of a VP9 packet.");
        }
        tl0PicIdx = newValue;
    }

    public boolean hasTL0PICIDX()
    {
        return tl0PicIdx != -1;
    }

    public int getPictureId()
    {
        return pictureId;
    }

    public void setPictureId(int newValue)
    {
        if (!DePacketizer.VP9PayloadDescriptor.setExtendedPictureId(
            buffer,
            getPayloadOffset(),
            getPayloadLength(),
            newValue
        ))
        {
            logger.warn(() -> "Failed to set the picture id of a VP9 packet.");
        }
        pictureId = newValue;
    }

    public int getTemporalLayerIndex()
    {
        return temporalLayerIndex;
    }

    public int getSpatialLayerIndex()
    {
        return spatialLayerIndex;
    }

    public int getEffectiveTemporalLayerIndex()
    {
        return hasLayerIndices ? temporalLayerIndex : 0;
    }

    public int getEffectiveSpatialLayerIndex()
    {
        return hasLayerIndices ? spatialLayerIndex : 0;
    }

    public boolean isSwitchingUpPoint()
    {
        return isSwitchingUpPoint;
    }

    public boolean usesInterLayerDependency()
    {
        return usesInterLayerDependency;
    }

    public RtpEncodingDesc getScalabilityStructure()
    {
        return getScalabilityStructure(0, 30.0);
    }

    public RtpEncodingDesc getScalabilityStructure(int eid)
    {
        return getScalabilityStructure(eid, 30.0);
    }

    public RtpEncodingDesc getScalabilityStructure(int eid, double baseFrameRate)
    {
        return getScalabilityStructure(buffer, getPayloadOffset(), getPayloadLength(), getSsrc(), eid, baseFrameRate);
    }

    public int getScalabilityStructureNumSpatial()
    {
        int off =
            DePacketizer.VP9PayloadDescriptor.getScalabilityStructureOffset(buffer, getPayloadOffset(), getPayloadLength());
        if (off == -1)
        {
            return -1;
        }
        int ssHeader = buffer[off];

        return ((ssHeader & 0xE0) >> 5) + 1;
    }

    /**
     * For {@link Vp9Packet} the payload excludes the VP9 Payload Descriptor.
     */
    @Override
    public String getPayloadVerification()
    {
        int rtpPayloadLength = getPayloadLength();
        int rtpPayloadOffset = getPayloadOffset();
        int vp9pdSize = DePacketizer.VP9PayloadDescriptor.getSize(buffer, rtpPayloadOffset, rtpPayloadLength);
        int vp9PayloadLength = rtpPayloadLength - vp9pdSize;
        int hashCode = ByteArrayExtensions.hashCodeOfSegment(
            buffer, rtpPayloadOffset + vp9pdSize, rtpPayloadOffset + rtpPayloadLength);
        return "type=VP9Packet len=" + vp9PayloadLength + " hashCode=" + hashCode;
    }

    @Override
    public String toString()
    {
        return super.toString() + ", SID=" + spatialLayerIndex + ", TID=" + temporalLayerIndex;
    }

    @Override
    public Vp9Packet clone()
    {
        Vp9Packet clone = new Vp9Packet(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length,
            isKeyframe,
            isStartOfFrame,
            isEndOfFrame,
            getEncodingId(),
            pictureId,
            tl0PicIdx
        );
        postClone(clone);
        return clone;
    }

    /* In theory this would fit better in vp9.DePacketizer, but I don't feel like translating this code
     * to Java, or translating that file to Kotlin.
     */
    public static RtpEncodingDesc getScalabilityStructure(
        byte[] buffer,
        int payloadOffset,
        int payloadLength,
        long ssrc,
        int eid,
        double baseFrameRate
    )
    {
        /*
         * VP9 Scalability structure:
         *
         *      +-+-+-+-+-+-+-+-+
         * V:   | N_S |Y|G|-|-|-|
         *      +-+-+-+-+-+-+-+-+              -\
         * Y:   |     WIDTH     | (OPTIONAL)    .
         *      +               +               .
         *      |               | (OPTIONAL)    .
         *      +-+-+-+-+-+-+-+-+               . - N_S + 1 times
         *      |     HEIGHT    | (OPTIONAL)    .
         *      +               +               .
         *      |               | (OPTIONAL)    .
         *      +-+-+-+-+-+-+-+-+              -/
         * G:   |      N_G      | (OPTIONAL)
         *      +-+-+-+-+-+-+-+-+                            -\
         * N_G: | TID |U| R |-|-| (OPTIONAL)                 .
         *      +-+-+-+-+-+-+-+-+              -\            . - N_G times
         *      |    P_DIFF     | (OPTIONAL)    . - R times  .
         *      +-+-+-+-+-+-+-+-+              -/            -/
         */

        int off = DePacketizer.VP9PayloadDescriptor.getScalabilityStructureOffset(buffer, payloadOffset, payloadLength);
        if (off == -1)
        {
            return null;
        }
        int ssHeader = buffer[off];

        int numSpatial = ((ssHeader & 0xE0) >> 5) + 1;
        boolean hasResolution = (ssHeader & Y_BIT) != 0;
        boolean hasPictureGroup = (ssHeader & G_BIT) != 0;

        off++;

        int[] heights = new int[numSpatial];
        if (hasResolution)
        {
            for (int s = 0; s < numSpatial; s++)
            {
                int width = ((buffer[off] & 0xff) << 8) | (buffer[off + 1] & 0xff);
                off += 2;

                int height = ((buffer[off] & 0xff) << 8) | (buffer[off + 1] & 0xff);
                off += 2;

                // Treat the lesser of width or height as the height in order to handle
                // portrait-mode video correctly
                heights[s] = Math.min(width, height);
            }
        }
        else
        {
            for (int s = 0; s < numSpatial; s++)
            {
                heights[s] = RtpLayerDesc.NO_HEIGHT;
            }
        }

        int[] tlCounts = new int[MAX_NUM_TLAYERS];
        int groupSize;
        if (hasPictureGroup)
        {
            groupSize = buffer[off];
            off++;

            for (int i = 0; i < groupSize; i++)
            {
                int descByte = buffer[off];
                int tid = (descByte & 0xE0) >> 5;
                int numRefs = (descByte & 0x0C) >> 2;
                off++;

                tlCounts[tid]++;

                /* TODO: do something with U, R, P_DIFF? */
                for (int j = 0; j < numRefs; j++)
                {
                    off++;
                }
            }
        }
        else
        {
            tlCounts[0] = 1;
            groupSize = 1;
        }

        int numTemporal = -1;
        for (int t = tlCounts.length - 1; t >= 0; t--)
        {
            if (tlCounts[t] > 0)
            {
                numTemporal = t;
                break;
            }
        }
        numTemporal += 1;

        for (int t = 1; t < numTemporal; t++)
        {
            /* Sum up frames per picture group */
            tlCounts[t] += tlCounts[t - 1];
        }

        List<VpxRtpLayerDesc> layers = new ArrayList<>();

        for (int s = 0; s < numSpatial; s++)
        {
            for (int t = 0; t < numTemporal; t++)
            {
                List<VpxRtpLayerDesc> dependencies = new ArrayList<>();
                List<VpxRtpLayerDesc> softDependencies = new ArrayList<>();
                if (s > 0)
                {
                    /* Because of K-SVC, spatial layer dependencies are soft */
                    VpxRtpLayerDesc dep = findLayer(layers, s - 1, t);
                    if (dep != null)
                    {
                        softDependencies.add(dep);
                    }
                }
                if (t > 0)
                {
                    VpxRtpLayerDesc dep = findLayer(layers, s, t - 1);
                    if (dep != null)
                    {
                        dependencies.add(dep);
                    }
                }
                VpxRtpLayerDesc layerDesc = new VpxRtpLayerDesc(
                    eid,
                    t,
                    s,
                    heights[s],
                    hasPictureGroup ? baseFrameRate * tlCounts[t] / groupSize : RtpLayerDesc.NO_FRAME_RATE,
                    dependencies.toArray(new VpxRtpLayerDesc[0]),
                    softDependencies.toArray(new VpxRtpLayerDesc[0])
                );
                layers.add(layerDesc);
            }
        }

        return new RtpEncodingDesc(ssrc, layers.toArray(new VpxRtpLayerDesc[0]), eid);
    }

    private static VpxRtpLayerDesc findLayer(List<VpxRtpLayerDesc> layers, int sid, int tid)
    {
        for (VpxRtpLayerDesc layer : layers)
        {
            if (layer.getSid() == sid && layer.getTid() == tid)
            {
                return layer;
            }
        }
        return null;
    }
}
