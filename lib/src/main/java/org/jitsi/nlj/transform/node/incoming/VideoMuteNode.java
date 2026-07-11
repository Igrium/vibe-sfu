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
package org.jitsi.nlj.transform.node.incoming;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.rtp.VideoRtpPacket;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.ObserverNode;

public class VideoMuteNode extends ObserverNode
{
    private int numMutedPackets = 0;
    private boolean forceMute = false;

    public VideoMuteNode()
    {
        super("VideoMuteNode");
    }

    public boolean isForceMute()
    {
        return forceMute;
    }

    public void setForceMute(boolean forceMute)
    {
        this.forceMute = forceMute;
    }

    @Override
    protected void observe(PacketInfo packetInfo)
    {
        if (!(packetInfo.getPacket() instanceof VideoRtpPacket))
        {
            return;
        }
        if (this.forceMute)
        {
            packetInfo.setShouldDiscard(true);
            numMutedPackets++;
        }
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        block.addNumber("num_video_packets_discarded", numMutedPackets);
        block.addBoolean("force_mute", forceMute);
        return block;
    }

    @Override
    public ObjectNode statsJson()
    {
        ObjectNode json = super.statsJson();
        json.put("num_video_packets_discarded", numMutedPackets);
        json.put("force_mute", forceMute);
        return json;
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }
}
