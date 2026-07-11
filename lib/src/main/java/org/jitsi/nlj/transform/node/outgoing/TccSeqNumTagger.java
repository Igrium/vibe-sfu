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
import org.jitsi.nlj.rtp.TransportCcEngine;
import org.jitsi.nlj.rtp.VideoRtpPacket;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.ModifierNode;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.rtp.header_extensions.TccHeaderExtension;

import java.lang.ref.WeakReference;

public class TccSeqNumTagger extends ModifierNode
{
    private long currTccSeqNum = 1;
    private Integer tccExtensionId = null;

    private final WeakReference<TransportCcEngine> weakTcc;

    public TccSeqNumTagger(TransportCcEngine transportCcEngine, ReadOnlyStreamInformationStore streamInformationStore)
    {
        super("TCC sequence number tagger");

        streamInformationStore.onRtpExtensionMapping(RtpExtensionType.TRANSPORT_CC, id -> tccExtensionId = id);

        this.weakTcc = new WeakReference<>(transportCcEngine);
    }

    @Override
    protected PacketInfo modify(PacketInfo packetInfo)
    {
        Integer tccExtId = tccExtensionId;
        if (tccExtId != null)
        {
            RtpPacket rtpPacket = packetInfo.packetAs();
            if (rtpPacket instanceof VideoRtpPacket)
            {
                RtpPacket.HeaderExtension ext = rtpPacket.getHeaderExtension(tccExtId);
                if (ext == null)
                {
                    ext = rtpPacket.addHeaderExtension(tccExtId, TccHeaderExtension.DATA_SIZE_BYTES);
                }

                long curSeq = currTccSeqNum;

                TccHeaderExtension.setSequenceNumber(ext, (int) curSeq);

                TransportCcEngine tcc = weakTcc.get();
                if (tcc != null)
                {
                    tcc.mediaPacketTagged(packetInfo, curSeq);
                }

                packetInfo.onSent(pkt -> {
                    TransportCcEngine t = weakTcc.get();
                    if (t != null)
                    {
                        t.mediaPacketSent(pkt, curSeq);
                    }
                });

                currTccSeqNum++;
            }
        }

        return packetInfo;
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        block.addString("tcc_ext_id", String.valueOf(tccExtensionId));
        return block;
    }

    @Override
    public void stop()
    {
        super.stop();
        TransportCcEngine tcc = weakTcc.get();
        if (tcc != null)
        {
            tcc.stop();
        }
    }

    @Override
    protected void trace(Runnable f)
    {
        f.run();
    }
}
