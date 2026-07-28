/*
 * Copyright @ 2020 - present 8x8, Inc.
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
import org.jitsi.nlj.MediaSourceDesc;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.format.PayloadTypeEncoding;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.rtp.rtcp.RtcpSrPacket;
import org.jitsi.utils.event.SyncEventEmitter;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.cc.config.BitrateControllerConfig;
import org.jitsi.videobridge.util.BooleanStateTimeTracker;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@link BitrateController} is responsible for controlling the send bitrate to an {@code Endpoint}. This includes
 * two tasks:
 * 1. Decide how to allocate the available bandwidth between the available streams.
 * 2. Implement the allocation via a packet handling interface.
 *
 * Historically both were implemented in a single class, but they are now split between {@link BandwidthAllocator}
 * and {@link PacketHandler}. This class was introduced as a lightweight shim in order to preserve the previous
 * API.
 *
 * @param <T> the type of the endpoints/media-source-containers this instance selects among; see
 * {@link MediaSourceContainer}.
 */
public class BitrateController<T extends MediaSourceContainer>
{
    private final SyncEventEmitter<EventHandler> eventEmitter = new SyncEventEmitter<>();

    private final Logger logger;

    private final DiagnosticContext diagnosticContext;

    private final Clock clock;

    private final BitrateAllocatorEventHandler bitrateAllocatorEventHandler = new BitrateAllocatorEventHandler();

    /**
     * Keep track of the "forwarded" sources, i.e. the media sources for which we are forwarding *some* layer.
     */
    private Set<String> forwardedSources = new HashSet<>();

    /**
     * Keep track of how much time we spend knowingly oversending (due to enableOnstageVideoSuspend being false)
     */
    private final BooleanStateTimeTracker oversendingTimeTracker = new BooleanStateTimeTracker();

    private final TimeSeriesLogger timeSeriesLogger;

    /**
     * NOTE(george): this flag acts as an approximation for determining whether or not adaptivity/probing is
     * supported. Eventually we need to scrap this and implement something cleaner, i.e. disable adaptivity if the
     * endpoint hasn't signaled `goog-remb` nor `transport-cc`.
     *
     * Unfortunately the channel iq from jicofo lists `goog-remb` and `transport-cc` support, even tho the jingle
     * from firefox doesn't (which is the main use case for wanting to disable adaptivity).
     */
    private boolean supportsRtx = false;

    private final PacketHandler packetHandler;
    private final BandwidthAllocator<T> bandwidthAllocator;

    private final AllocationSettings.AllocationSettingsWrapper allocationSettingsWrapper;

    private boolean bweSet = false;

    public BitrateController(
        EventHandler eventHandler,
        Supplier<List<T>> endpointsSupplier,
        DiagnosticContext diagnosticContext,
        Logger parentLogger,
        Clock clock)
    {
        this.diagnosticContext = diagnosticContext;
        this.clock = clock;
        this.logger = parentLogger.createChildLogger(BitrateController.class.getName());

        TimeSeriesLogger tsLogger = TimeSeriesLogger.getTimeSeriesLogger(BitrateController.class);
        this.timeSeriesLogger = tsLogger.isTraceEnabled() ? tsLogger : null;

        this.packetHandler = new PacketHandler(clock, parentLogger, diagnosticContext, eventEmitter);
        this.bandwidthAllocator = new BandwidthAllocator<>(
            bitrateAllocatorEventHandler,
            endpointsSupplier,
            this::getTrustBwe,
            parentLogger,
            diagnosticContext,
            clock
        );
        this.allocationSettingsWrapper = new AllocationSettings.AllocationSettingsWrapper(parentLogger);

        eventEmitter.addHandler(eventHandler);
    }

    public BitrateController(
        EventHandler eventHandler,
        Supplier<List<T>> endpointsSupplier,
        DiagnosticContext diagnosticContext,
        Logger parentLogger)
    {
        this(eventHandler, endpointsSupplier, diagnosticContext, parentLogger, Clock.systemUTC());
    }

    public boolean hasSuspendedSources()
    {
        return bandwidthAllocator.getAllocation().getHasSuspendedSources();
    }

    public AllocationSettings getAllocationSettings()
    {
        return allocationSettingsWrapper.get();
    }

    /**
     * Ignore the bandwidth estimations for some initial time because the REMBs don't ramp up fast enough.
     * This shouldn't be needed for other bandwidth estimation algorithms.
     */
    private boolean getTrustBwe()
    {
        return BitrateControllerConfig.config.trustBwe && supportsRtx && bweSet &&
            packetHandler.timeSinceFirstMedia().compareTo(BitrateControllerConfig.config.getInitialIgnoreBwePeriod()) >= 0;
    }

    // Proxy to the allocator
    public void endpointOrderingChanged()
    {
        bandwidthAllocator.update();
    }

    public int getLastN()
    {
        return allocationSettingsWrapper.lastN;
    }

    public void setLastN(int value)
    {
        if (allocationSettingsWrapper.setLastN(value))
        {
            bandwidthAllocator.update(allocationSettingsWrapper.get());
        }
    }

    public void expire()
    {
        bandwidthAllocator.expire();
    }

    /** Return the number of sources currently being forwarded. */
    public int numForwardedSources()
    {
        return forwardedSources.size();
    }

    public Duration getTotalOversendingTime()
    {
        return oversendingTimeTracker.totalTimeOn();
    }

    public boolean isOversending()
    {
        return oversendingTimeTracker.getState();
    }

    public void bandwidthChanged(long newBandwidthBps)
    {
        bweSet = true;
        if (timeSeriesLogger != null)
        {
            logBweChange(newBandwidthBps);
        }
        bandwidthAllocator.bandwidthChanged(newBandwidthBps);
    }

    // Proxy to the packet handler
    public boolean accept(PacketInfo packetInfo)
    {
        if (packetInfo.isLayeringChanged())
        {
            // This needs to be done synchronously, so it's complete before the accept, below.
            logger.debug(() -> "Layering information changed for packet from " + packetInfo.getEndpointId() +
                ", updating bandwidth allocation");
            bandwidthAllocator.update();
        }
        return packetHandler.accept(packetInfo);
    }

    public boolean accept(RtcpSrPacket rtcpSrPacket)
    {
        return packetHandler.accept(rtcpSrPacket);
    }

    public boolean transformRtcp(RtcpSrPacket rtcpSrPacket)
    {
        return packetHandler.transformRtcp(rtcpSrPacket);
    }

    public boolean transformRtp(PacketInfo packetInfo)
    {
        return packetHandler.transformRtp(packetInfo);
    }

    public ObjectNode debugState(DebugStateMode mode)
    {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.set("bitrate_allocator", bandwidthAllocator.getDebugState());
        node.set("packet_handler", packetHandler.debugState(mode));
        node.put("forwarded_sources", forwardedSources.toString());
        node.put("oversending", oversendingTimeTracker.getState());
        node.put("total_oversending_time_secs", oversendingTimeTracker.totalTimeOn().getSeconds());
        node.put("supports_rtx", supportsRtx);
        node.put("trust_bwe", getTrustBwe());
        return node;
    }

    public void addPayloadType(PayloadType payloadType)
    {
        if (payloadType.getEncoding() == PayloadTypeEncoding.RTX)
        {
            supportsRtx = true;
        }
    }

    public void setBandwidthAllocationSettings(AllocationSettings.ReceiverVideoConstraintsMessage message)
    {
        if (allocationSettingsWrapper.setBandwidthAllocationSettings(message))
        {
            bandwidthAllocator.update(allocationSettingsWrapper.get());
        }
    }

    /**
     * Query whether this source is on stage or selected, as of the most recent video constraints
     */
    public boolean isOnStageOrSelected(MediaSourceDesc source)
    {
        AllocationSettings allocationSettings = getAllocationSettings();
        return allocationSettings.getOnStageSources().contains(source.getSourceName()) ||
            allocationSettings.getSelectedSources().contains(source.getSourceName());
    }

    /**
     * Query whether this allocator has non-zero effective constraints for a given source
     */
    public boolean hasNonZeroEffectiveConstraints(MediaSourceDesc source)
    {
        return bandwidthAllocator.hasNonZeroEffectiveConstraints(source);
    }

    /**
     * Get the target and ideal bitrate of the current {@link BandwidthAllocation}, as well as the list of SSRCs
     * being forwarded, for use in probing.
     *
     * Note that the ideal layers are calculated with the allocation, and inactive layers are not considered. So
     * when a higher layer becomes active, it will not be accounted for until until the allocation updates.
     * Conversely, if the ideal layer becomes inactive, it will contribute 0 bps to the total ideal bitrate until
     * the allocation updates and a lower layer is selected as idea.
     */
    public BitrateControllerStatusSnapshot getStatusSnapshot()
    {
        Bandwidth totalTargetBitrate = Bandwidth.ZERO;
        Bandwidth totalIdealBitrate = Bandwidth.ZERO;
        Set<Long> activeSsrcs = new HashSet<>();
        boolean hasNonIdealLayer = false;

        long nowMs = clock.instant().toEpochMilli();
        BandwidthAllocation allocation = bandwidthAllocator.getAllocation();
        for (BandwidthAllocation.SingleAllocation singleAllocation : allocation.getAllocations())
        {
            Bandwidth allocationTargetBitrate =
                singleAllocation.getTargetLayer() != null ? singleAllocation.getTargetLayer().getBitrate(nowMs) : null;

            if (allocationTargetBitrate != null)
            {
                totalTargetBitrate = totalTargetBitrate.plus(allocationTargetBitrate);
                if (singleAllocation.getMediaSource() != null)
                {
                    activeSsrcs.add(singleAllocation.getMediaSource().getPrimarySSRC());
                }
            }

            Bandwidth allocationIdealBitrate;
            if (BitrateControllerConfig.config.useVlaTargetBitrate)
            {
                allocationIdealBitrate = singleAllocation.getIdealLayer() != null
                    ? (singleAllocation.getIdealLayer().getTargetBitrate() != null
                        ? singleAllocation.getIdealLayer().getTargetBitrate()
                        : singleAllocation.getIdealLayer().getBitrate(nowMs))
                    : null;
            }
            else
            {
                allocationIdealBitrate =
                    singleAllocation.getIdealLayer() != null ? singleAllocation.getIdealLayer().getBitrate(nowMs) : null;
            }

            if (allocationIdealBitrate != null)
            {
                totalIdealBitrate = totalIdealBitrate.plus(allocationIdealBitrate);
            }

            if (singleAllocation.getIdealLayer() != null &&
                !singleAllocation.getIdealLayer().equals(singleAllocation.getTargetLayer()))
            {
                hasNonIdealLayer = true;
            }
        }

        activeSsrcs.removeIf(ssrc -> ssrc < 0);

        return new BitrateControllerStatusSnapshot(
            totalTargetBitrate.getBps(),
            totalIdealBitrate.getBps(),
            activeSsrcs,
            hasNonIdealLayer
        );
    }

    private void logBweChange(long newBweBps)
    {
        timeSeriesLogger.trace(diagnosticContext.makeTimeSeriesPoint("new_bwe").addField("bwe_bps", newBweBps));
    }

    private void logAllocationChange(BandwidthAllocation allocation)
    {
        long nowMs = clock.millis();

        double totalTargetBps = 0.0;
        double totalIdealBps = 0.0;
        double totalTargetVlaBps = 0.0;
        double totalIdealVlaBps = 0.0;

        for (BandwidthAllocation.SingleAllocation singleAllocation : allocation.getAllocations())
        {
            RtpLayerDescOrNull target = new RtpLayerDescOrNull(singleAllocation.getTargetLayer());
            RtpLayerDescOrNull ideal = new RtpLayerDescOrNull(singleAllocation.getIdealLayer());

            double targetMeasuredBps = target.getBitrateBps(nowMs);
            double idealMeasuredBps = ideal.getBitrateBps(nowMs);
            double targetVlaBps = target.getTargetBitrateBps();
            double idealVlaBps = ideal.getTargetBitrateBps();

            totalTargetBps += targetMeasuredBps;
            totalIdealBps += idealMeasuredBps;
            totalTargetVlaBps += targetVlaBps;
            totalIdealVlaBps += idealVlaBps;

            timeSeriesLogger.trace(
                diagnosticContext
                    .makeTimeSeriesPoint("allocation_for_source", nowMs)
                    .addField("remote_endpoint_id", singleAllocation.getEndpointId())
                    .addField("target_idx", target.getIndex())
                    .addField("ideal_idx", ideal.getIndex())
                    .addField("target_bps_measured", target.hasLayer() ? targetMeasuredBps : -1)
                    .addField("target_bps_vla", target.hasLayer() ? targetVlaBps : -1)
                    .addField("ideal_bps_measured", ideal.hasLayer() ? idealMeasuredBps : -1)
                    .addField("ideal_bps_vla", ideal.hasLayer() ? idealVlaBps : -1)
            );
        }

        timeSeriesLogger.trace(
            diagnosticContext
                .makeTimeSeriesPoint("allocation", nowMs)
                .addField("total_target_measured_bps", totalTargetBps)
                .addField("total_ideal_measured_bps", totalIdealBps)
                .addField("total_target_vla_bps", totalTargetVlaBps)
                .addField("total_ideal_vla_bps", totalIdealVlaBps)
        );
    }

    /**
     * (Library addition: small helper to mirror Kotlin's null-safe {@code ?.} chaining used in
     * {@code logAllocationChange} without repeating null checks everywhere.)
     */
    private static class RtpLayerDescOrNull
    {
        private final org.jitsi.nlj.RtpLayerDesc layer;

        RtpLayerDescOrNull(org.jitsi.nlj.RtpLayerDesc layer)
        {
            this.layer = layer;
        }

        boolean hasLayer()
        {
            return layer != null;
        }

        int getIndex()
        {
            return layer != null ? layer.getIndex() : -1;
        }

        double getBitrateBps(long nowMs)
        {
            return layer != null ? layer.getBitrate(nowMs).getBps() : -1;
        }

        double getTargetBitrateBps()
        {
            return layer != null && layer.getTargetBitrate() != null ? layer.getTargetBitrate().getBps() : -1;
        }
    }

    public interface EventHandler
    {
        void forwardedSourcesChanged(Set<String> forwardedSources);

        void effectiveVideoConstraintsChanged(
            Map<MediaSourceDesc, VideoConstraints> oldEffectiveConstraints,
            Map<MediaSourceDesc, VideoConstraints> newEffectiveConstraints
        );

        void keyframeNeeded(String endpointId, long ssrc);

        /**
         * This is meant to be internal to BitrateAllocator, but is exposed here temporarily for the purposes of
         * testing.
         */
        default void allocationChanged(BandwidthAllocation allocation)
        {
        }
    }

    private class BitrateAllocatorEventHandler implements BandwidthAllocator.EventHandler
    {
        @Override
        public void allocationChanged(BandwidthAllocation allocation)
        {
            if (timeSeriesLogger != null)
            {
                logAllocationChange(allocation);
            }
            // Actually implement the allocation (configure the packet filter to forward the chosen target layers).
            packetHandler.allocationChanged(allocation);

            Set<String> newForwardedSources = allocation.getForwardedSources();
            if (!forwardedSources.equals(newForwardedSources))
            {
                forwardedSources = newForwardedSources;
                eventEmitter.fireEvent(handler -> {
                    handler.forwardedSourcesChanged(newForwardedSources);
                    return Unit.INSTANCE;
                });
            }

            oversendingTimeTracker.setState(allocation.isOversending());

            // TODO: this is for testing only. Should we change the tests to work with [BitrateAllocator] directly?
            eventEmitter.fireEvent(handler -> {
                handler.allocationChanged(allocation);
                return Unit.INSTANCE;
            });
        }

        @Override
        public void effectiveVideoConstraintsChanged(
            Map<MediaSourceDesc, VideoConstraints> oldEffectiveConstraints,
            Map<MediaSourceDesc, VideoConstraints> newEffectiveConstraints)
        {
            // Forward to the outer EventHandler.
            eventEmitter.fireEvent(handler -> {
                handler.effectiveVideoConstraintsChanged(oldEffectiveConstraints, newEffectiveConstraints);
                return Unit.INSTANCE;
            });
        }
    }
}
