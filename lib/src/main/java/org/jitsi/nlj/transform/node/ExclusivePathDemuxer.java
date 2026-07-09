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
package org.jitsi.nlj.transform.node;

import org.jitsi.nlj.PacketInfo;

/**
 * Packets are passed only to the first path which accepts them
 */
public class ExclusivePathDemuxer extends DemuxerNode
{
    public ExclusivePathDemuxer(String name)
    {
        super(name);
        // Note: DemuxerNode's constructor appends " demuxer" to the name it passes up to Node, so
        // this.name here is "<name> demuxer", matching upstream's `override val aggregationKey = this.name`.
        this.aggregationKey = this.name;
    }

    @Override
    protected void doProcessPacket(PacketInfo packetInfo)
    {
        for (ConditionalPacketPath conditionalPath : transformPaths)
        {
            if (conditionalPath.getPredicate().test(packetInfo.getPacket()))
            {
                doneProcessing(packetInfo);
                conditionalPath.packetsAccepted++;
                conditionalPath.getPath().processPacket(packetInfo);
                return;
            }
        }
        packetDiscarded(packetInfo);
    }

    @Override
    protected void trace(Runnable f)
    {
        f.run();
    }
}
