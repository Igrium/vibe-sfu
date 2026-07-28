/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.nlj.transform.node.outgoing;

import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.transform.node.ModifierNode;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.rtp.header_extensions.OneByteHeaderExtensionParser;
import org.jitsi.utils.logging2.Logger;

public class HeaderExtEncoder extends ModifierNode
{
    private final Logger logger;
    private boolean extmapAllowMixed = false;

    public HeaderExtEncoder(ReadOnlyStreamInformationStore streamInformationStore, Logger parentLogger)
    {
        super("Header extension encoder");
        this.logger = parentLogger.createChildLogger(HeaderExtEncoder.class.getName());

        streamInformationStore.onExtmapAllowMixedChanged(value -> extmapAllowMixed = value);
    }

    @Override
    protected PacketInfo modify(PacketInfo packetInfo)
    {
        RtpPacket rtpPacket = packetInfo.packetAs();

        rtpPacket.encodeHeaderExtensions();

        if (rtpPacket.getHasExtensions())
        {
            int profileType = rtpPacket.getExtensionsProfileType();

            if (!extmapAllowMixed && profileType != OneByteHeaderExtensionParser.INSTANCE.getHeaderExtensionLabel())
            {
                logger.warn(
                    "Sending header extension profile type " + Integer.toHexString(profileType) +
                        " when extmap-allow-mixed is not enabled"
                );
            }
        }
        return packetInfo;
    }

    @Override
    protected void trace(Runnable f)
    {
        f.run();
    }
}
