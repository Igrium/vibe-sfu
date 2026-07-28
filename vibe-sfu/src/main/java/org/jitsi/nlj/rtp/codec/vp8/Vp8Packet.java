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

package org.jitsi.nlj.rtp.codec.vp8;

import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.codec.vp8.Vp8Utils;
import org.jitsi.nlj.rtp.ParsedVideoPacket;
import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;
import org.jitsi_modified.impl.neomedia.codec.video.vp8.DePacketizer;

import java.util.Collection;
import java.util.Collections;

/**
 * If this {@link Vp8Packet} instance is being created via a clone,
 * we've already parsed the packet itself and determined whether
 * or not its a keyframe and what its spatial layer index is,
 * so the constructor allows passing in those values if
 * they're already known.  If they're null, this instance
 * will do the parsing itself.
 */
public class Vp8Packet extends ParsedVideoPacket
{
    private static final Logger logger = new LoggerImpl(Vp8Packet.class.getName());

    private final boolean isKeyframe;
    private final boolean isStartOfFrame;
    private final boolean hasTemporalLayerIndex;
    private final boolean hasPictureId;
    private final boolean hasExtendedPictureId;
    private final boolean hasTL0PICIDX;
    private int tl0PicIdx;
    private int pictureId;
    private final int temporalLayerIndex;
    private final int height;

    private Vp8Packet(
        byte[] buffer,
        int offset,
        int length,
        Boolean isKeyframe,
        Boolean isStartOfFrame,
        int encodingId,
        Integer height,
        Integer pictureId,
        Integer tl0PicIdx
    )
    {
        super(buffer, offset, length, encodingId);

        /* Due to the format of the VP8 payload, this value is only reliable for packets where isStartOfFrame is
           true. */
        this.isKeyframe = isKeyframe != null ? isKeyframe :
            DePacketizer.isKeyFrame(this.buffer, getPayloadOffset(), getPayloadLength());

        this.isStartOfFrame = isStartOfFrame != null ? isStartOfFrame :
            DePacketizer.VP8PayloadDescriptor.isStartOfFrame(buffer, getPayloadOffset());

        this.hasTemporalLayerIndex =
            DePacketizer.VP8PayloadDescriptor.hasTemporalLayerIndex(buffer, getPayloadOffset(), getPayloadLength());

        this.hasPictureId =
            DePacketizer.VP8PayloadDescriptor.hasPictureId(buffer, getPayloadOffset(), getPayloadLength());

        this.hasExtendedPictureId =
            DePacketizer.VP8PayloadDescriptor.hasExtendedPictureId(buffer, getPayloadOffset(), getPayloadLength());

        this.hasTL0PICIDX =
            DePacketizer.VP8PayloadDescriptor.hasTL0PICIDX(buffer, getPayloadOffset(), getPayloadLength());

        this.tl0PicIdx = tl0PicIdx != null ? tl0PicIdx :
            DePacketizer.VP8PayloadDescriptor.getTL0PICIDX(buffer, getPayloadOffset(), getPayloadLength());

        this.pictureId = pictureId != null ? pictureId :
            DePacketizer.VP8PayloadDescriptor.getPictureId(buffer, getPayloadOffset());

        this.temporalLayerIndex = Vp8Utils.getTemporalLayerIdOfFrame(this);

        /*
         * This is currently used as an overall spatial index, not an in-band spatial quality index a la vp9.  That
         * is, this index will correspond to an overall simulcast layer index across multiple simulcast stream.
         * e.g. 180p stream packets will have 0, 360p -&gt; 1, 720p -&gt; 2
         */
        this.height = height != null ? height : (this.isKeyframe ? Vp8Utils.getHeightFromKeyFrame(this) : -1);
    }

    public Vp8Packet(byte[] buffer, int offset, int length)
    {
        this(
            buffer, offset, length,
            /* isKeyframe */ null,
            /* isStartOfFrame */ null,
            RtpLayerDesc.SUSPENDED_ENCODING_ID,
            /* height */ null,
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

    /** End of VP8 frame is the marker bit. */
    @Override
    public boolean isEndOfFrame()
    {
        return isMarked();
    }

    @Override
    public boolean meetsRoutingNeeds()
    {
        return hasPictureId && hasTemporalLayerIndex;
    }

    public boolean hasTemporalLayerIndex()
    {
        return hasTemporalLayerIndex;
    }

    public boolean hasPictureId()
    {
        return hasPictureId;
    }

    public boolean hasExtendedPictureId()
    {
        return hasExtendedPictureId;
    }

    public boolean hasTL0PICIDX()
    {
        return hasTL0PICIDX;
    }

    public int getTL0PICIDX()
    {
        return tl0PicIdx;
    }

    public void setTL0PICIDX(int newValue)
    {
        tl0PicIdx = newValue;
        if (newValue != -1 && !DePacketizer.VP8PayloadDescriptor.setTL0PICIDX(
            buffer,
            getPayloadOffset(),
            getPayloadLength(),
            newValue
        ))
        {
            logger.warn(() -> "Failed to set the TL0PICIDX of a VP8 packet.");
        }
    }

    public int getPictureId()
    {
        return pictureId;
    }

    public void setPictureId(int newValue)
    {
        pictureId = newValue;
        if (!DePacketizer.VP8PayloadDescriptor.setExtendedPictureId(
            buffer,
            getPayloadOffset(),
            getPayloadLength(),
            newValue
        ))
        {
            logger.warn(() -> "Failed to set the picture id of a VP8 packet.");
        }
    }

    public int getTemporalLayerIndex()
    {
        return temporalLayerIndex;
    }

    @Override
    public Collection<Integer> getLayerIds()
    {
        if (hasTemporalLayerIndex)
        {
            return Collections.singletonList(RtpLayerDesc.getIndex(0, 0, temporalLayerIndex));
        }
        else
        {
            return super.getLayerIds();
        }
    }

    /**
     * This is currently used as an overall spatial index, not an in-band spatial quality index a la vp9.  That is,
     * this index will correspond to an overall simulcast layer index across multiple simulcast stream.  e.g.
     * 180p stream packets will have 0, 360p -&gt; 1, 720p -&gt; 2
     */
    public int getHeight()
    {
        return height;
    }

    /**
     * For {@link Vp8Packet} the payload excludes the VP8 Payload Descriptor.
     */
    @Override
    public String getPayloadVerification()
    {
        int rtpPayloadLength = getPayloadLength();
        int rtpPayloadOffset = getPayloadOffset();
        int vp8pdSize = DePacketizer.VP8PayloadDescriptor.getSize(buffer, rtpPayloadOffset, rtpPayloadLength);
        int vp8PayloadLength = rtpPayloadLength - vp8pdSize;
        int hashCode = ByteArrayExtensions.hashCodeOfSegment(
            buffer, getPayloadOffset() + vp8pdSize, rtpPayloadOffset + rtpPayloadLength);
        return "type=Vp8Packet len=" + vp8PayloadLength + " hashCode=" + hashCode;
    }

    @Override
    public String toString()
    {
        return super.toString() + ", TID=" + temporalLayerIndex;
    }

    @Override
    public Vp8Packet clone()
    {
        Vp8Packet clone = new Vp8Packet(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length,
            isKeyframe,
            isStartOfFrame,
            getEncodingId(),
            height,
            pictureId,
            tl0PicIdx
        );
        postClone(clone);
        return clone;
    }
}
