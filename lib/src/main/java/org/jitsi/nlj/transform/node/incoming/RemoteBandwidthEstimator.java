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
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.SetLocalSsrcEvent;
import org.jitsi.nlj.rtp.RtpExtensionType;
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator;
import org.jitsi.nlj.rtp.bandwidthestimation.GoogleCcEstimator;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.ObserverNode;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacketBuilder;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.rtp.header_extensions.AbsSendTimeHeaderExtension;
import org.jitsi.utils.LRUCache;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Estimates the available bandwidth for the incoming stream using the abs-send-time extension.
 */
public class RemoteBandwidthEstimator extends ObserverNode
{
    private static final int MAX_SSRCS = 8;

    private final ReadOnlyStreamInformationStore streamInformationStore;
    private final Clock clock;
    private final Logger logger;

    /**
     * The remote bandwidth estimation is enabled when REMB support is signaled, but TCC is not signaled.
     */
    private boolean enabled = false;
    private Integer astExtId;

    /**
     * We use the full {@link GoogleCcEstimator} here, but we don't notify it of packet loss, effectively using only
     * the delay-based part.
     *
     * (Deviation: upstream initializes this lazily via Kotlin's {@code by lazy}; ported as eager initialization in
     * the constructor since there is only ever one initialization and no laziness-sensitive behavior depends on
     * deferring it.)
     */
    private final BandwidthEstimator bwe;
    private final Set<Long> ssrcs = Collections.synchronizedSet(LRUCache.lruSet(MAX_SSRCS, true));
    private int numRembsCreated = 0;
    private int numPacketsWithoutAbsSendTime = 0;
    private long localSsrc = 0L;

    public RemoteBandwidthEstimator(ReadOnlyStreamInformationStore streamInformationStore, Logger parentLogger)
    {
        this(streamInformationStore, parentLogger, new DiagnosticContext(), Clock.systemUTC());
    }

    public RemoteBandwidthEstimator(
        ReadOnlyStreamInformationStore streamInformationStore,
        Logger parentLogger,
        DiagnosticContext diagnosticContext)
    {
        this(streamInformationStore, parentLogger, diagnosticContext, Clock.systemUTC());
    }

    public RemoteBandwidthEstimator(
        ReadOnlyStreamInformationStore streamInformationStore,
        Logger parentLogger,
        DiagnosticContext diagnosticContext,
        Clock clock)
    {
        super("Remote Bandwidth Estimator");
        this.streamInformationStore = streamInformationStore;
        this.clock = clock;
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.bwe = new GoogleCcEstimator(diagnosticContext, logger);

        streamInformationStore.onRtpExtensionMapping(RtpExtensionType.ABS_SEND_TIME, id -> {
            astExtId = id;
            logger.debug(() -> "Setting abs-send-time extension ID to " + astExtId);
        });
        streamInformationStore.onRtpPayloadTypesChanged(payloadTypes ->
            setEnabled(streamInformationStore.getSupportsRemb() && !streamInformationStore.getSupportsTcc()));
    }

    private void setEnabled(boolean newValue)
    {
        if (enabled != newValue)
        {
            logger.debug(() -> "Setting enabled=" + newValue + ".");
        }
        enabled = newValue;
    }

    @Override
    public void handleEvent(Event event)
    {
        if (event instanceof SetLocalSsrcEvent)
        {
            SetLocalSsrcEvent e = (SetLocalSsrcEvent) event;
            if (e.getMediaType() == MediaType.VIDEO)
            {
                localSsrc = e.getSsrc();
            }
        }
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }

    @Override
    protected void observe(PacketInfo packetInfo)
    {
        if (!enabled)
        {
            return;
        }

        Integer extId = astExtId;
        if (extId != null)
        {
            RtpPacket rtpPacket = packetInfo.packetAs();
            RtpPacket.HeaderExtension ext = rtpPacket.getHeaderExtension(extId);
            if (ext != null)
            {
                Instant now = clock.instant();
                bwe.processPacketArrival(
                    now,
                    AbsSendTimeHeaderExtension.getTime(ext),
                    packetInfo.getReceivedTime(),
                    rtpPacket.getSequenceNumber(),
                    DataSize.ofBytes(packetInfo.getOriginalLength())
                );
                /* With receiver-side bwe we need to treat each received packet as separate feedback */
                bwe.feedbackComplete(now);
                ssrcs.add(rtpPacket.getSsrc());
            }
            else
            {
                numPacketsWithoutAbsSendTime++;
            }
        }
        else
        {
            numPacketsWithoutAbsSendTime++;
        }
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        block.addString("ast_ext_id", String.valueOf(astExtId));
        block.addBoolean("enabled", enabled);
        block.addNumber("num_rembs_created", numRembsCreated);
        block.addNumber("num_packets_without_ast", numPacketsWithoutAbsSendTime);
        return block;
    }

    public RtcpFbRembPacket createRemb()
    {
        // REMB based BWE is not configured.
        if (!enabled || astExtId == null)
        {
            return null;
        }

        Bandwidth currentBw = bwe.getCurrentBw(clock.instant());
        // The estimator does not yet have a valid value.
        if (currentBw.compareTo(Bandwidth.ZERO) < 0)
        {
            return null;
        }

        numRembsCreated++;
        return new RtcpFbRembPacketBuilder(
            new RtcpHeaderBuilder().setSenderSsrc(localSsrc),
            new ArrayList<>(ssrcs),
            currentBw.getBps()
        ).build();
    }

    public void onRttUpdate(double newRttMs)
    {
        bwe.onRttUpdate(clock.instant(), Duration.ofNanos((long) (newRttMs * 1_000_000)));
    }
}
