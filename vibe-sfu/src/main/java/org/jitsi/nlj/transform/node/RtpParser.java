/*
 * Copyright @ 2019 - present 8x8 Inc
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
package org.jitsi.nlj.transform.node;

import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.format.PayloadTypeEncoding;
import org.jitsi.nlj.rtp.AudioRtpPacket;
import org.jitsi.nlj.rtp.RedAudioRtpPacket;
import org.jitsi.nlj.rtp.VideoRtpPacket;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.rtp.RtpHeader;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.logging2.Logger;

public class RtpParser extends TransformerNode
{
    private final ReadOnlyStreamInformationStore streamInformationStore;
    private final Logger logger;

    public RtpParser(ReadOnlyStreamInformationStore streamInformationStore, Logger parentLogger)
    {
        super("RTP Parser");
        this.streamInformationStore = streamInformationStore;
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    @Override
    protected PacketInfo transform(PacketInfo packetInfo)
    {
        Packet packet = packetInfo.getPacket();
        byte payloadTypeNumber = (byte) RtpHeader.getPayloadType(packet.buffer, packet.offset);

        PayloadType payloadType = streamInformationStore.getRtpPayloadTypes().get(payloadTypeNumber);
        if (payloadType == null)
        {
            logger.debug(() -> "Unknown payload type: " + payloadTypeNumber);
            return null;
        }
        packetInfo.setPayloadType(payloadType);

        RtpPacket rtpPacket;
        if (payloadType.getMediaType() == MediaType.AUDIO)
        {
            try
            {
                rtpPacket = payloadType.getEncoding() == PayloadTypeEncoding.RED
                    ? packet.toOtherType(RedAudioRtpPacket::new)
                    : packet.toOtherType(AudioRtpPacket::new);
            }
            catch (Exception e)
            {
                logger.info("Dropping audio packet due to parse failure: " + e.getMessage());
                return null;
            }
        }
        else if (payloadType.getMediaType() == MediaType.VIDEO)
        {
            try
            {
                rtpPacket = packet.toOtherType(VideoRtpPacket::new);
            }
            catch (Exception e)
            {
                logger.info("Dropping video packet due to parse failure: " + e.getMessage());
                return null;
            }
        }
        else
        {
            logger.info("Dropping packet with unrecognized media type: '" + payloadType.getMediaType() + "'");
            return null;
        }
        packetInfo.setPacket(rtpPacket);
        if (rtpPacket.getExtensionsProfileType() == 0xC0DE || rtpPacket.getExtensionsProfileType() == 0xC2DE)
        {
            packetInfo.setOriginalHadCryptex(true);
        }

        packetInfo.resetPayloadVerification();
        return packetInfo;
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }
}
