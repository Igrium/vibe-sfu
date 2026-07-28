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

import org.jitsi.nlj.rtp.TransportCcEngine;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.rtcp.RtcpPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket;
import org.jitsi.utils.logging2.Logger;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * (Deviation: this class was originally ported (M4b) before {@code TransportCcEngine}/bandwidthestimation existed,
 * so the {@code List<TransportCcEngine.BandwidthListener>} / {@code addListener} plumbing present upstream was
 * dropped at the time. Now that {@code TransportCcEngine} is ported (M4), it is restored here to match upstream,
 * since {@code RtpReceiverImpl} (M6) needs to subscribe to REMB-derived bandwidth updates.)
 */
public class RembHandler implements RtcpListener
{
    private final ReadOnlyStreamInformationStore streamInformationStore;
    private final Logger logger;
    private boolean sawSpuriousRemb = false;

    private final List<TransportCcEngine.BandwidthListener> bweUpdateListeners = new CopyOnWriteArrayList<>();

    public RembHandler(ReadOnlyStreamInformationStore streamInformationStore, Logger parentLogger)
    {
        this.streamInformationStore = streamInformationStore;
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    public ReadOnlyStreamInformationStore getStreamInformationStore()
    {
        return streamInformationStore;
    }

    public void addListener(TransportCcEngine.BandwidthListener bweUpdateListener)
    {
        bweUpdateListeners.add(bweUpdateListener);
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
        Bandwidth newValue = Bandwidth.ofBps(rembPacket.getBitrate());
        for (TransportCcEngine.BandwidthListener listener : bweUpdateListeners)
        {
            listener.bandwidthEstimationChanged(newValue);
        }
    }

    private static final AtomicInteger endpointsWithSpuriousRemb = new AtomicInteger();

    public static int endpointsWithSpuriousRemb()
    {
        return endpointsWithSpuriousRemb.get();
    }
}
