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
package org.jitsi.nlj.rtcp;

import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.rtcp.RtcpPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket;
import org.jitsi.utils.logging2.Logger;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TODO(port): upstream also maintains a {@code List<TransportCcEngine.BandwidthListener>} and forwards REMB bitrate
 * updates to it (via {@code addListener}/{@code bweUpdateListeners.forEach { ... }}). {@code TransportCcEngine} /
 * bandwidthestimation is not yet ported, so that listener plumbing is dropped here; only the spurious-REMB
 * detection/logging is kept.
 */
public class RembHandler implements RtcpListener
{
    private final ReadOnlyStreamInformationStore streamInformationStore;
    private final Logger logger;
    private boolean sawSpuriousRemb = false;

    public RembHandler(ReadOnlyStreamInformationStore streamInformationStore, Logger parentLogger)
    {
        this.streamInformationStore = streamInformationStore;
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    public ReadOnlyStreamInformationStore getStreamInformationStore()
    {
        return streamInformationStore;
    }

    @Override
    public void rtcpPacketReceived(RtcpPacket packet, Instant receivedTime)
    {
        if (packet instanceof RtcpFbRembPacket)
        {
            logger.debug(() -> "Received REMB packet");
            onRembPacket((RtcpFbRembPacket) packet);
        }
    }

    private void onRembPacket(RtcpFbRembPacket rembPacket)
    {
        if (streamInformationStore.getSupportsTcc())
        {
            if (!sawSpuriousRemb)
            {
                logger.warn(() -> "Ignoring unexpected REMB, when using TCC (for " + rembPacket.getBitrate() +
                    "). Will suppress future logs for this endpoint.");
                endpointsWithSpuriousRemb.incrementAndGet();
                sawSpuriousRemb = true;
            }
            return;
        }
        logger.debug(() -> "Updating bandwidth to " + rembPacket.getBitrate());
        // TODO(port): forward rembPacket.getBitrate() to registered BandwidthListeners once
        // TransportCcEngine/bandwidthestimation is ported.
    }

    private static final AtomicInteger endpointsWithSpuriousRemb = new AtomicInteger();

    public static int endpointsWithSpuriousRemb()
    {
        return endpointsWithSpuriousRemb.get();
    }
}
