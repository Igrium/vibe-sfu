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

import org.jitsi.nlj.Event;
import org.jitsi.nlj.MediaSourceDesc;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.SetMediaSourcesEvent;
import org.jitsi.nlj.rtp.VideoRtpPacket;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.TransformerNode;
import org.jitsi.utils.logging2.Logger;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Set video packets' quality layer info
 */
public class VideoQualityLayerLookup extends TransformerNode
{
    private final Logger logger;
    private MediaSourceDesc[] sources = new MediaSourceDesc[0];
    private final AtomicInteger numPacketsDroppedNoEncoding = new AtomicInteger();

    public VideoQualityLayerLookup(Logger parentLogger)
    {
        super("Video quality layer lookup");
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    @Override
    protected PacketInfo transform(PacketInfo packetInfo)
    {
        VideoRtpPacket videoPacket = packetInfo.packetAs();
        Integer encodingId = MediaSourceDesc.findRtpEncodingId(sources, videoPacket);
        if (encodingId == null)
        {
            logger.warn(
                "Unable to find encoding matching packet! packet=" + videoPacket + "; sources="
                    + Arrays.stream(sources).map(String::valueOf).collect(Collectors.joining("\n"))
            );
            numPacketsDroppedNoEncoding.incrementAndGet();
            return null;
        }
        videoPacket.setEncodingId(encodingId);

        return packetInfo;
    }

    @Override
    public void handleEvent(Event event)
    {
        if (event instanceof SetMediaSourcesEvent)
        {
            sources = ((SetMediaSourcesEvent) event).getMediaSourceDescs();
        }
        super.handleEvent(event);
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        block.addNumber("num_packets_dropped_no_encoding", numPacketsDroppedNoEncoding.get());
        return block;
    }
}
