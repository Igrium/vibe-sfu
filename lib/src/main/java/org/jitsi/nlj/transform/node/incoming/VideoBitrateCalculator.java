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

package org.jitsi.nlj.transform.node.incoming;

import org.jitsi.nlj.Event;
import org.jitsi.nlj.MediaSourceDesc;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.SetMediaSourcesEvent;
import org.jitsi.nlj.rtp.VideoRtpPacket;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.utils.logging2.Logger;

import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * When deciding what can be forwarded, we want to know the bitrate of a stream so we can fill the receiver's
 * available bandwidth as much as possible without going over.  This node tracks the incoming bitrate per each
 * individual layer (that is, each forwardable stream taking into account spatial and temporal scalability) and
 * tags the {@link VideoRtpPacket} with a snapshot of the current estimated bitrate for the encoding to which it
 * belongs
 */
public class VideoBitrateCalculator extends BitrateCalculator
{
    private final Logger logger;
    private MediaSourceDesc[] mediaSourceDescs = new MediaSourceDesc[0];

    public VideoBitrateCalculator(Logger parentLogger)
    {
        // Screen sharing static content can result in very low packet/bit rates, hence the low threshold.
        this(parentLogger, 1);
    }

    public VideoBitrateCalculator(Logger parentLogger, int activePacketRateThreshold)
    {
        super("Video bitrate calculator", activePacketRateThreshold);
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    @Override
    protected void observe(PacketInfo packetInfo)
    {
        super.observe(packetInfo);

        VideoRtpPacket videoRtpPacket = (VideoRtpPacket) packetInfo.getPacket();
        long now = clock.millis();
        Collection<RtpLayerDesc> layerDescs = MediaSourceDesc.findRtpLayerDescs(mediaSourceDescs, videoRtpPacket);

        if (layerDescs.isEmpty())
        {
            logger.warn("No layer found for packet " + videoRtpPacket);
        }

        for (RtpLayerDesc layerDesc : layerDescs)
        {
            if (layerDesc.updateBitrate(DataSize.ofBytes(videoRtpPacket.getLength()), now))
            {
                /* When a layer is started when it was previously inactive,
                 * we want to recalculate bandwidth allocation.
                 */
                packetInfo.setLayeringChanged(true);
            }
        }
    }

    @Override
    public void handleEvent(Event event)
    {
        if (event instanceof SetMediaSourcesEvent)
        {
            mediaSourceDescs = ((SetMediaSourcesEvent) event).getMediaSourceDescs().clone();
            logger.debug(() -> "Video bitrate calculator got media sources:\n" +
                Arrays.stream(mediaSourceDescs).map(String::valueOf).collect(Collectors.joining(", ")));
        }
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }
}
