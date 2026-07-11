/*
 * Copyright @ 2024 - Present, 8x8 Inc
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
import org.jitsi.rtp.rtp.header_extensions.SdesHeaderExtension;

import java.util.function.Function;

/**
 * Stamps the sdes:mid (media identification) RTP header extension on outgoing packets, using a per-SSRC mid supplied
 * by {@code getMidBySsrc}. This is used under SSRC rewriting so that the receiving client can demux forwarded media
 * by mid (which disables Chrome's payload-type demuxing fallback, the trigger for the audio-demux wedge).
 *
 * This node sits in the shared portion of the outgoing pipeline (after RTX encapsulation) so that both media packets
 * and their retransmissions are stamped with the same mid. The mid is resolved from the packet's (already rewritten)
 * SSRC, which is stable per slot, so primary and RTX SSRCs of a slot resolve to the same mid.
 *
 * Does nothing unless the mid extension has been negotiated for this endpoint and {@code getMidBySsrc} returns a mid
 * for the packet's SSRC (i.e. the endpoint opted into mid-based demuxing and the SSRC is a rewritten slot SSRC).
 */
public class MidStamper extends ModifierNode
{
    private final ReadOnlyStreamInformationStore streamInformationStore;
    private final Function<Long, String> getMidBySsrc;
    private Integer extensionId = null;
    private int numStamped = 0;

    public MidStamper(ReadOnlyStreamInformationStore streamInformationStore, Function<Long, String> getMidBySsrc)
    {
        super("Mid stamper");
        this.streamInformationStore = streamInformationStore;
        this.getMidBySsrc = getMidBySsrc;

        streamInformationStore.onRtpExtensionMapping(RtpExtensionType.MID, id -> extensionId = id);
    }

    @Override
    protected PacketInfo modify(PacketInfo packetInfo)
    {
        Integer midExtId = extensionId;
        if (midExtId == null)
        {
            return packetInfo;
        }
        RtpPacket rtpPacket = packetInfo.packetAs();
        String mid = getMidBySsrc.apply(rtpPacket.getSsrc());
        if (mid == null)
        {
            return packetInfo;
        }

        // The header extensions of outgoing packets have been stripped by this point, so a mid extension is only
        // present if we (or RTX, re-using the same packet) added it. Avoid adding it twice.
        if (rtpPacket.getHeaderExtension(midExtId) == null)
        {
            RtpPacket.HeaderExtension ext = rtpPacket.addHeaderExtension(midExtId, mid.length());
            SdesHeaderExtension.setTextValue(ext, mid);
            numStamped++;
        }

        return packetInfo;
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        block.addString("mid_ext_id", String.valueOf(extensionId));
        block.addNumber("num_stamped", numStamped);
        return block;
    }

    @Override
    protected void trace(Runnable f)
    {
        f.run();
    }
}
