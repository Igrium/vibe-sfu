/*
 * Copyright @ 2019 - present 8x8, Inc.
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

import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.rtp.ResumableStreamRewriter;
import org.jitsi.nlj.transform.node.TransformerNode;
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.rtp.RtpPacket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discards RTP packets which have shouldDiscard set to true, masking their loss
 * in the RTP sequence numbers of RTP packets.
 */
public class DiscardableDiscarder extends TransformerNode
{
    private final boolean keepHistory;
    private final Map<Long, ResumableStreamRewriter> rewriters = new ConcurrentHashMap<>();

    public DiscardableDiscarder(String name, boolean keepHistory)
    {
        super(name);
        this.keepHistory = keepHistory;
    }

    public Map<Long, ResumableStreamRewriter> getRewriters()
    {
        return rewriters;
    }

    @Override
    protected PacketInfo transform(PacketInfo packetInfo)
    {
        Packet packet = packetInfo.getPacket();
        if (!(packet instanceof RtpPacket))
        {
            return packetInfo;
        }
        RtpPacket rtpPacket = (RtpPacket) packet;
        rewriters.computeIfAbsent(rtpPacket.getSsrc(), k -> new ResumableStreamRewriter(keepHistory))
            .rewriteRtp(!packetInfo.isShouldDiscard(), rtpPacket);

        return packetInfo.isShouldDiscard() ? null : packetInfo;
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }
}
