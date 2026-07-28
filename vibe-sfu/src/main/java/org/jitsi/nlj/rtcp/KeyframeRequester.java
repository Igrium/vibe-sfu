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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.Event;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.SetLocalSsrcEvent;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.TransformerNode;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacketBuilder;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacketBuilder;
import org.jitsi.utils.DurationKt;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.RateLimit;
import org.jitsi.utils.logging2.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link KeyframeRequester} handles a few things around keyframes:
 * 1) The bridge requesting a keyframe (e.g. in order to switch) via the {@link KeyframeRequester#requestKeyframe}
 * method which will create a new keyframe request and forward it
 * 2) PLI/FIR translation.  If a PLI or FIR packet is forwarded through here, this class may translate it depending
 * on what the client supports
 * 3) Aggregation.  This class will pace outgoing requests such that we don't spam the sender
 */
public class KeyframeRequester extends TransformerNode
{
    /**
     * Replaces the upstream {@code jitsi-metaconfig}-based config. Since this port does not use
     * {@code jitsi-metaconfig}/HOCON, this hardcodes the upstream defaults (see {@code reference.conf}'s
     * {@code jmt.keyframe} section).
     */
    private static final Duration minInterval = Duration.ofMillis(200);
    private static final int maxRequests = 3;
    private static final Duration maxRequestInterval = Duration.ofSeconds(10);

    private static final Duration sourceWideMinInterval = Duration.ofMillis(200);
    private static final int sourceWideMaxRequests = 1;
    private static final Duration sourceWideMaxRequestInterval = Duration.ofMillis(200);

    private final ReadOnlyStreamInformationStore streamInformationStore;
    private final Logger logger;
    private final Clock clock;

    // Map the tuple of requester and SSRC to a rate limiter
    private final Map<String, Map<Long, RateLimit>> perReceiverKeyframeLimiter = new HashMap<>();
    private final Map<Long, RateLimit> perSourceKeyframeLimiter = new HashMap<>();
    private final Object keyframeLimiterSyncRoot = new Object();
    private final AtomicInteger firCommandSequenceNumber = new AtomicInteger(0);
    private Long localSsrc = null;
    private Duration waitInterval = minInterval;

    // Stats

    // Number of PLI/FIRs received and forwarded to the endpoint.
    private int numPlisForwarded = 0;
    private int numFirsForwarded = 0;

    // Number of PLI/FIRs received but dropped due to throttling.
    private int numPlisDropped = 0;
    private int numFirsDropped = 0;

    // Number of PLI/FIRs generated as a result of an API request or due to translation between PLI/FIR.
    private int numPlisGenerated = 0;
    private int numFirsGenerated = 0;

    // Number of calls to requestKeyframe
    private int numApiRequests = 0;

    // Number of calls to requestKeyframe ignored due to throttling
    private int numApiRequestsDropped = 0;

    public KeyframeRequester(ReadOnlyStreamInformationStore streamInformationStore, Logger parentLogger)
    {
        this(streamInformationStore, parentLogger, Clock.systemDefaultZone());
    }

    public KeyframeRequester(ReadOnlyStreamInformationStore streamInformationStore, Logger parentLogger, Clock clock)
    {
        super("Keyframe Requester");
        this.streamInformationStore = streamInformationStore;
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.clock = clock;
    }

    @Override
    protected PacketInfo transform(PacketInfo packetInfo)
    {
        RtcpFbPacket pliOrFirPacket = getPliOrFirPacket(packetInfo);
        if (pliOrFirPacket == null)
        {
            return packetInfo;
        }

        Instant now = clock.instant();
        long sourceSsrc;
        boolean canSend;
        boolean forward;
        if (pliOrFirPacket instanceof RtcpFbPliPacket)
        {
            RtcpFbPliPacket pli = (RtcpFbPliPacket) pliOrFirPacket;
            sourceSsrc = pli.getMediaSourceSsrc();
            canSend = canSendKeyframeRequest(packetInfo.getEndpointId(), sourceSsrc, now);
            forward = canSend && streamInformationStore.getSupportsPli();
            if (forward)
            {
                numPlisForwarded++;
            }
            if (!canSend)
            {
                numPlisDropped++;
            }
        }
        else if (pliOrFirPacket instanceof RtcpFbFirPacket)
        {
            RtcpFbFirPacket fir = (RtcpFbFirPacket) pliOrFirPacket;
            sourceSsrc = fir.getMediaSenderSsrc();
            canSend = canSendKeyframeRequest(packetInfo.getEndpointId(), sourceSsrc, now);
            // When both are supported, we favor generating a PLI rather than forwarding a FIR
            forward = canSend && streamInformationStore.getSupportsFir() && !streamInformationStore.getSupportsPli();
            if (forward)
            {
                // When we forward a FIR we need to update the seq num.
                fir.setSeqNum(firCommandSequenceNumber.incrementAndGet());
                // We manage the seq num space, so we should use the same SSRC
                Long ls = localSsrc;
                if (ls != null)
                {
                    fir.setMediaSenderSsrc(ls);
                }
                numFirsForwarded++;
            }
            if (!canSend)
            {
                numFirsDropped++;
            }
        }
        else
        {
            // This is not possible, but the compiler doesn't know it.
            throw new IllegalStateException("Packet is neither PLI nor FIR");
        }

        if (!forward && canSend)
        {
            doRequestKeyframe(sourceSsrc);
        }

        return forward ? packetInfo : null;
    }

    /**
     * Returns 'true' when at least one method is supported, AND this requester hasn't sent a request very recently.
     */
    private boolean canSendKeyframeRequest(String requesterID, long mediaSsrc, Instant now)
    {
        if (!streamInformationStore.getSupportsPli() && !streamInformationStore.getSupportsFir())
        {
            return false;
        }
        if (requesterID == null)
        {
            /* This request is either triggered by a dominant speaker switch, or possibly came over a proxy connection.
             *  Always allow it. */
            return true;
        }
        synchronized (keyframeLimiterSyncRoot)
        {
            RateLimit perReceiverLimiter = perReceiverKeyframeLimiter
                .computeIfAbsent(requesterID, k -> new HashMap<>())
                .computeIfAbsent(
                    mediaSsrc,
                    k -> new RateLimit(minInterval, maxRequests, maxRequestInterval, Clock.systemUTC()));

            if (!perReceiverLimiter.accept(now, waitInterval))
            {
                logger.debug(() -> "Ignoring keyframe request for " + mediaSsrc + " from " + requesterID +
                    ", per-receiver rate limited");
                return false;
            }

            RateLimit perSourceLimiter = perSourceKeyframeLimiter.computeIfAbsent(
                mediaSsrc,
                k -> new RateLimit(sourceWideMinInterval, sourceWideMaxRequests, sourceWideMaxRequestInterval, Clock.systemUTC()));

            if (!perSourceLimiter.accept(now, waitInterval))
            {
                logger.debug(() -> "Ignoring keyframe request for " + mediaSsrc + " from " + requesterID +
                    ", per-source rate limited");
                return false;
            }

            logger.debug(() -> "Keyframe requester requesting keyframe for " + mediaSsrc + ", requested by " + requesterID);
            return true;
        }
    }

    public void requestKeyframe(String requesterID)
    {
        requestKeyframe(requesterID, null);
    }

    public void requestKeyframe(String requesterID, Long mediaSsrc)
    {
        Long ssrc = mediaSsrc;
        if (ssrc == null)
        {
            ssrc = streamInformationStore.getPrimaryMediaSsrcs().stream().findFirst().orElse(null);
            if (ssrc == null)
            {
                numApiRequestsDropped++;
                logger.debug(() -> "No video SSRC found to request keyframe");
                return;
            }
        }
        numApiRequests++;
        if (!canSendKeyframeRequest(requesterID, ssrc, clock.instant()))
        {
            numApiRequestsDropped++;
            return;
        }

        doRequestKeyframe(ssrc);
    }

    private void doRequestKeyframe(long mediaSsrc)
    {
        RtcpFbPacket pkt;
        if (streamInformationStore.getSupportsPli())
        {
            numPlisGenerated++;
            RtcpFbPliPacketBuilder builder = new RtcpFbPliPacketBuilder();
            builder.setMediaSourceSsrc(mediaSsrc);
            pkt = builder.build();
        }
        else if (streamInformationStore.getSupportsFir())
        {
            numFirsGenerated++;
            RtcpFbFirPacketBuilder builder = new RtcpFbFirPacketBuilder();
            builder.setMediaSenderSsrc(mediaSsrc);
            builder.setFirCommandSeqNum(firCommandSequenceNumber.incrementAndGet());
            pkt = builder.build();
        }
        else
        {
            logger.warn("Can not send neither PLI nor FIR");
            return;
        }

        next(new PacketInfo(pkt));
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
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock stats = super.getNodeStats();
        stats.addNumber("wait_interval_ms", waitInterval.toMillis());
        stats.addNumber("num_api_requests", numApiRequests);
        stats.addNumber("num_api_requests_dropped", numApiRequestsDropped);
        stats.addNumber("num_firs_dropped", numFirsDropped);
        stats.addNumber("num_firs_generated", numFirsGenerated);
        stats.addNumber("num_firs_forwarded", numFirsForwarded);
        stats.addNumber("num_plis_dropped", numPlisDropped);
        stats.addNumber("num_plis_generated", numPlisGenerated);
        stats.addNumber("num_plis_forwarded", numPlisForwarded);
        return stats;
    }

    @Override
    public ObjectNode statsJson()
    {
        ObjectNode json = super.statsJson();
        json.put("num_api_requests", numApiRequests);
        json.put("num_api_requests_dropped", numApiRequestsDropped);
        json.put("num_firs_dropped", numFirsDropped);
        json.put("num_firs_generated", numFirsGenerated);
        json.put("num_firs_forwarded", numFirsForwarded);
        json.put("num_plis_dropped", numPlisDropped);
        json.put("num_plis_generated", numPlisGenerated);
        json.put("num_plis_forwarded", numPlisForwarded);
        return json;
    }

    public void onRttUpdate(double newRtt)
    {
        // avg(rtt) + stddev(rtt) would be more accurate than rtt + 10.
        waitInterval = DurationKt.min(minInterval, DurationKt.durationOfDoubleSeconds((newRtt + 10) / 1e3));
    }

    private static RtcpFbPacket getPliOrFirPacket(PacketInfo packetInfo)
    {
        Packet pkt = packetInfo.getPacket();
        // We intentionally ignore compound RTCP packets in order to avoid unnecessary parsing. We can do this because:
        // 1. Compound packets coming from remote endpoint are terminated in RtcpTermination
        // 2. Whenever a PLI or FIR is generated in our code, it is not part of a compound packet.
        if (pkt instanceof RtcpFbFirPacket)
        {
            return (RtcpFbFirPacket) pkt;
        }
        if (pkt instanceof RtcpFbPliPacket)
        {
            return (RtcpFbPliPacket) pkt;
        }
        return null;
    }
}
