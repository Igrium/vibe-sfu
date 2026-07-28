/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
package org.jitsi.videobridge.cc.allocation;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kotlin.Unit;
import org.jitsi.nlj.DebugStateMode;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.rtp.VideoRtpPacket;
import org.jitsi.rtp.rtcp.RtcpSrPacket;
import org.jitsi.utils.event.EventEmitter;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.cc.AdaptiveSourceProjection;
import org.jitsi.videobridge.cc.RewriteException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles packets for a {@link BitrateController}, implementing a specific {@link BandwidthAllocation} provided
 * via {@link #allocationChanged}. Serves as a bridge between {@link BitrateController} (which decides which layers
 * are to be forwarded) and {@link AdaptiveSourceProjection} which processes packets and maintains the state of a
 * forwarded stream.
 *
 * Defines "accept" and "transform" functions for video RTP packets and RTCP Sender Reports.
 */
class PacketHandler
{
    private final Clock clock;
    private final Logger logger;
    private final DiagnosticContext diagnosticContext;
    private final EventEmitter<BitrateController.EventHandler> eventEmitter;

    /**
     * The time when this instance first transformed any media. This allows to ignore the BWE during the early
     * stages of the call.
     *
     * NOTE This is only meant to be as a temporary hack and ideally should be fixed.
     */
    private Instant firstMedia;

    private final AtomicInteger numDroppedPacketsUnknownSsrc = new AtomicInteger(0);

    /**
     * The {@link AdaptiveSourceProjection}s that this instance is managing, keyed by the SSRCs of the associated
     * {@link org.jitsi.nlj.MediaSourceDesc}.
     */
    private final Map<Long, AdaptiveSourceProjection> adaptiveSourceProjectionMap = new ConcurrentHashMap<>();

    PacketHandler(
        Clock clock,
        Logger parentLogger,
        DiagnosticContext diagnosticContext,
        EventEmitter<BitrateController.EventHandler> eventEmitter)
    {
        this.clock = clock;
        this.logger = parentLogger.createChildLogger(PacketHandler.class.getName());
        this.diagnosticContext = diagnosticContext;
        this.eventEmitter = eventEmitter;
    }

    /**
     * @return true if the packet was transformed successfully, false otherwise.
     */
    boolean transformRtp(PacketInfo packetInfo)
    {
        VideoRtpPacket videoPacket = packetInfo.packetAs();

        if (firstMedia == null)
        {
            firstMedia = clock.instant();
        }

        AdaptiveSourceProjection adaptiveSourceProjection = adaptiveSourceProjectionMap.get(videoPacket.getSsrc());
        if (adaptiveSourceProjection == null)
        {
            return false;
        }
        try
        {
            adaptiveSourceProjection.rewriteRtp(packetInfo);

            // The rewriteRtp operation must not modify the VP8 payload.
            if (PacketInfo.enablePayloadVerification)
            {
                String expected = packetInfo.getPayloadVerification();
                String actual = videoPacket.getPayloadVerification();
                if (!"".equals(expected) && !expected.equals(actual))
                {
                    logger.warn("Payload unexpectedly modified! Expected: " + expected + ", actual: " + actual);
                }
            }
            return true;
        }
        catch (RewriteException e)
        {
            logger.warn("Failed to rewrite a packet.", e);
            return false;
        }
    }

    /**
     * @return true if a packet should be accepted according to the current configuration.
     */
    boolean accept(PacketInfo packetInfo)
    {
        VideoRtpPacket videoPacket = packetInfo.packetAs();
        AdaptiveSourceProjection adaptiveSourceProjection = adaptiveSourceProjectionMap.get(videoPacket.getSsrc());
        if (adaptiveSourceProjection == null)
        {
            logger.debug(() -> "Dropping an RTP packet for an unknown SSRC: " + videoPacket.getSsrc());
            numDroppedPacketsUnknownSsrc.incrementAndGet();
            return false;
        }
        return adaptiveSourceProjection.accept(packetInfo);
    }

    /**
     * @return true if {@code rtcpSrPacket} should be accepted, false otherwise.
     *
     * Filters out packets that match one of the streams that this instance manages, but don't match the target
     * SSRC. Allows packets for streams not managed by this instance.
     */
    boolean accept(RtcpSrPacket rtcpSrPacket)
    {
        AdaptiveSourceProjection adaptiveSourceProjection =
            adaptiveSourceProjectionMap.get(rtcpSrPacket.getSenderSsrc());

        // This is probably for an audio stream. In any case, if it's for a stream which we are not forwarding it
        // will be stripped off at a later stage (in RtcpSrUpdater).
        return adaptiveSourceProjection == null ||
            // We only accept SRs for the SSRC that we're forwarding with.
            adaptiveSourceProjection.getTargetSsrc() == rtcpSrPacket.getSenderSsrc();
    }

    /**
     * @return true if the packet was transformed successfully, false otherwise.
     */
    boolean transformRtcp(RtcpSrPacket rtcpSrPacket)
    {
        AdaptiveSourceProjection adaptiveSourceProjection =
            adaptiveSourceProjectionMap.get(rtcpSrPacket.getSenderSsrc());
        return adaptiveSourceProjection != null && adaptiveSourceProjection.rewriteRtcp(rtcpSrPacket);
    }

    /**
     * Utility method that looks-up or creates the adaptive source projection of a source.
     */
    private AdaptiveSourceProjection lookupOrCreateAdaptiveSourceProjection(
        BandwidthAllocation.SingleAllocation singleAllocation)
    {
        org.jitsi.nlj.MediaSourceDesc source = singleAllocation.getMediaSource();
        String endpointId = singleAllocation.getEndpointId();
        if (source == null)
        {
            return null;
        }
        synchronized (adaptiveSourceProjectionMap)
        {
            AdaptiveSourceProjection existing = adaptiveSourceProjectionMap.get(source.getPrimarySSRC());
            if (existing != null)
            {
                return existing;
            }

            if (source.getRtpEncodings().length == 0)
            {
                return null;
            }

            AdaptiveSourceProjection adaptiveSourceProjection = new AdaptiveSourceProjection(
                diagnosticContext,
                source,
                () -> eventEmitter.fireEvent(handler -> {
                    handler.keyframeNeeded(endpointId, source.getPrimarySSRC());
                    return Unit.INSTANCE;
                }),
                logger
            );
            logger.debug(() -> "new source projection for " + source);

            // Route all encodings to the specified bitrate controller.
            for (org.jitsi.nlj.RtpEncodingDesc encoding : source.getRtpEncodings())
            {
                adaptiveSourceProjectionMap.put(encoding.getPrimarySSRC(), adaptiveSourceProjection);
            }
            return adaptiveSourceProjection;
        }
    }

    Duration timeSinceFirstMedia()
    {
        return firstMedia != null ? Duration.between(firstMedia, clock.instant()) : Duration.ZERO;
    }

    ObjectNode debugState(DebugStateMode mode)
    {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("num_dropped_packets_unknown_ssrc", numDroppedPacketsUnknownSsrc.get());
        ObjectNode mapNode = JsonNodeFactory.instance.objectNode();
        adaptiveSourceProjectionMap.forEach(
            (ssrc, adaptiveSourceProjection) -> mapNode.set(ssrc.toString(), adaptiveSourceProjection.getDebugState(mode)));
        node.set("adaptive_source_projection_map", mapNode);
        return node;
    }

    /**
     * Signals to this instance that the allocation chosen by the {@code BitrateAllocator} has changed.
     */
    void allocationChanged(BandwidthAllocation allocation)
    {
        if (allocation.getAllocations().isEmpty())
        {
            adaptiveSourceProjectionMap.values().forEach(
                p -> p.setTargetIndex(RtpLayerDesc.SUSPENDED_INDEX));
        }
        else
        {
            for (BandwidthAllocation.SingleAllocation singleAllocation : allocation.getAllocations())
            {
                int sourceTargetIdx =
                    singleAllocation.getTargetLayer() != null ? singleAllocation.getTargetLayer().getIndex() : -1;

                // Review this.
                AdaptiveSourceProjection adaptiveSourceProjection =
                    lookupOrCreateAdaptiveSourceProjection(singleAllocation);
                if (adaptiveSourceProjection != null)
                {
                    adaptiveSourceProjection.setTargetIndex(sourceTargetIdx);
                }
            }
        }
    }
}
