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

package org.jitsi.nlj.codec.vp8;

import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi_modified.impl.neomedia.codec.video.vp8.DePacketizer;

import java.nio.ByteBuffer;

public class Vp8Utils
{
    private static final int VP8_PAYLOAD_HEADER_LEN = 3;

    // TODO(brian): should move these elsewhere probably
    private static final int MIN_HD_HEIGHT = 540;
    private static final int MIN_SD_HEIGHT = 360;
    private static final int HD_LAYER_ID = 2;
    private static final int SD_LAYER_ID = 1;
    private static final int LD_LAYER_ID = 0;
    private static final int SUSPENDED_LAYER_ID = -1;

    public static boolean isKeyFrame(ByteBuffer vp8Payload)
    {
        return DePacketizer.isKeyFrame(vp8Payload.array(), vp8Payload.arrayOffset(), vp8Payload.limit());
    }

    public static int getSpatialLayerIndexFromKeyFrame(ByteBuffer vp8Payload)
    {
        // Copied from VP8QualityFilter#getSpatialLayerIndexFromKeyframe
        int payloadDescriptorLen = DePacketizer.VP8PayloadDescriptor.getSize(
            vp8Payload.array(),
            vp8Payload.arrayOffset(),
            vp8Payload.limit()
        );
        int height = DePacketizer.VP8KeyframeHeader.getHeight(
            vp8Payload.array(),
            vp8Payload.arrayOffset() + payloadDescriptorLen + VP8_PAYLOAD_HEADER_LEN
        );
        if (height >= MIN_HD_HEIGHT)
        {
            return HD_LAYER_ID;
        }
        else if (height >= MIN_SD_HEIGHT)
        {
            return SD_LAYER_ID;
        }
        else if (height > -1)
        {
            return LD_LAYER_ID;
        }
        else
        {
            return -1;
        }
    }

    public static int getHeightFromKeyFrame(RtpPacket vp8Packet)
    {
        int payloadDescriptorLen = DePacketizer.VP8PayloadDescriptor.getSize(
            vp8Packet.getBuffer(),
            vp8Packet.getPayloadOffset(),
            vp8Packet.getPayloadLength()
        );
        return DePacketizer.VP8KeyframeHeader.getHeight(
            vp8Packet.getBuffer(),
            vp8Packet.getPayloadOffset() + payloadDescriptorLen + VP8_PAYLOAD_HEADER_LEN
        );
    }

    public static int getSpatialLayerIndexFromKeyFrame(RtpPacket vp8Packet)
    {
        // Copied from VP8QUalityFilter#getSpatialLayerIndexFromKeyframe
        int height = getHeightFromKeyFrame(vp8Packet);
        if (height >= MIN_HD_HEIGHT)
        {
            return HD_LAYER_ID;
        }
        else if (height >= MIN_SD_HEIGHT)
        {
            return SD_LAYER_ID;
        }
        else if (height > -1)
        {
            return LD_LAYER_ID;
        }
        else
        {
            return -1;
        }
    }

    public static int getTemporalLayerIdOfFrame(ByteBuffer vp8Payload)
    {
        return DePacketizer.VP8PayloadDescriptor.getTemporalLayerIndex(
            vp8Payload.array(),
            vp8Payload.arrayOffset(),
            vp8Payload.limit()
        );
    }

    public static int getTemporalLayerIdOfFrame(RtpPacket vp8Packet)
    {
        return DePacketizer.VP8PayloadDescriptor.getTemporalLayerIndex(
            vp8Packet.getBuffer(),
            vp8Packet.getPayloadOffset(),
            vp8Packet.getPayloadLength()
        );
    }
}
