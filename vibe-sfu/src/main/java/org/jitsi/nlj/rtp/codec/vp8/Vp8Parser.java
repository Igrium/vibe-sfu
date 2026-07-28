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

import org.jitsi.nlj.MediaSourceDesc;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.RtpEncodingDesc;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.rtp.codec.VideoCodecParser;
import org.jitsi.nlj.util.StateChangeLogger;
import org.jitsi.rtp.extensions.ByteArrayBufferExtensions;
import org.jitsi.utils.logging2.Logger;

/**
 * Some {@link Vp8Packet} fields are not able to be determined by looking at a single VP8 packet (for example the
 * frame height can only be acquired from keyframes).  This class updates the layer descriptions with information
 * from frames, and also diagnoses packet format variants that the Jitsi videobridge won't be able to route.
 */
public class Vp8Parser extends VideoCodecParser
{
    private final Logger logger;

    // Consistency
    private final StateChangeLogger pictureIdState;
    private final StateChangeLogger extendedPictureIdState;
    private final StateChangeLogger tidWithoutTl0PicIdxState;

    public Vp8Parser(MediaSourceDesc source, Logger parentLogger)
    {
        super(source);
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.pictureIdState = new StateChangeLogger("missing picture id", logger);
        this.extendedPictureIdState = new StateChangeLogger("missing extended picture ID", logger);
        this.tidWithoutTl0PicIdxState = new StateChangeLogger("TID with missing TL0PICIDX", logger);
    }

    @Override
    public void parse(PacketInfo packetInfo)
    {
        Vp8Packet vp8Packet = packetInfo.<Vp8Packet>packetAs();
        if (vp8Packet.getHeight() > -1)
        {
            // TODO: handle case where new height is from a packet older than the
            //  latest height we've seen.
            RtpEncodingDesc enc = findRtpEncodingDesc(vp8Packet);
            if (enc != null)
            {
                RtpLayerDesc[] oldLayers = enc.getLayers();
                RtpLayerDesc[] newLayers = new RtpLayerDesc[oldLayers.length];
                for (int i = 0; i < oldLayers.length; i++)
                {
                    newLayers[i] = oldLayers[i].copy(vp8Packet.getHeight(), oldLayers[i].getTid(), true);
                }
                enc.setLayersDirect(newLayers);
            }
        }

        pictureIdState.setState(
            vp8Packet.hasPictureId(),
            vp8Packet,
            () -> "Packet Data: " + ByteArrayBufferExtensions.toHex(vp8Packet, 80)
        );
        extendedPictureIdState.setState(
            vp8Packet.hasExtendedPictureId(),
            vp8Packet,
            () -> "Packet Data: " + ByteArrayBufferExtensions.toHex(vp8Packet, 80)
        );
        tidWithoutTl0PicIdxState.setState(
            vp8Packet.hasTL0PICIDX() || !vp8Packet.hasTemporalLayerIndex(),
            vp8Packet,
            () -> "Packet Data: " + ByteArrayBufferExtensions.toHex(vp8Packet, 80)
        );
    }
}
