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
import org.jitsi.nlj.rtp.RtpExtensionType;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.ModifierNode;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.rtp.header_extensions.AbsSendTimeHeaderExtension;

public class AbsSendTime extends ModifierNode
{
    private final ReadOnlyStreamInformationStore streamInformationStore;
    private Integer extensionId = null;

    public AbsSendTime(ReadOnlyStreamInformationStore streamInformationStore)
    {
        super("Absolute send time");
        this.streamInformationStore = streamInformationStore;

        streamInformationStore.onRtpExtensionMapping(RtpExtensionType.ABS_SEND_TIME, id -> extensionId = id);
    }

    public ReadOnlyStreamInformationStore getStreamInformationStore()
    {
        return streamInformationStore;
    }

    @Override
    protected PacketInfo modify(PacketInfo packetInfo)
    {
        if (streamInformationStore.getSupportsTcc())
        {
            return packetInfo;
        }

        Integer absSendTimeExtId = extensionId;
        if (absSendTimeExtId != null)
        {
            RtpPacket rtpPacket = packetInfo.packetAs();
            RtpPacket.HeaderExtension ext = rtpPacket.getHeaderExtension(absSendTimeExtId);
            if (ext == null)
            {
                ext = rtpPacket.addHeaderExtension(absSendTimeExtId, AbsSendTimeHeaderExtension.DATA_SIZE_BYTES);
            }
            AbsSendTimeHeaderExtension.setTime(ext, System.nanoTime());
        }

        return packetInfo;
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        block.addString("abs_send_time_ext_id", String.valueOf(extensionId));
        return block;
    }

    @Override
    protected void trace(Runnable f)
    {
        f.run();
    }
}
