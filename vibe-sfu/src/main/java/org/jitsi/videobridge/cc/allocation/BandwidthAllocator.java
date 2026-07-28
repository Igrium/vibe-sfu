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
import org.jitsi.nlj.MediaSourceDesc;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.utils.event.EventEmitter;
import org.jitsi.utils.event.SyncEventEmitter;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.cc.config.BitrateControllerConfig;
import org.jitsi.videobridge.util.TaskPools;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @param <T> the type of the endpoints/media-source-containers that this allocator selects among. Preserves
 * upstream's generic seam ({@code MediaSourceContainer}) so the host supplies its own endpoint/relay type.
 */
class BandwidthAllocator<T extends MediaSourceContainer>
{
    private static final TimeSeriesLogger timeSeriesLogger =
        TimeSeriesLogger.getTimeSeriesLogger(BandwidthAllocator.class);

    private final Logger logger;

    /**
     * Provide the current list of endpoints (in no particular order).
     * TODO: Simplify to avoid the weird (and slow) flow involving `endpointsSupplier` and `sortedEndpointIds`.
     */
    private final Supplier<List<T>> endpointsSupplier;

    /**
     * Whether bandwidth allocation should be constrained to the available bandwidth (when {@code true}), or assume
     * infinite bandwidth (when {@code false}).
     */
    private final Supplier<Boolean> trustBwe;

    private final DiagnosticContext diagnosticContext;
    private final Clock clock;

    /** The estimated available bandwidth in bits per second. */
    private long bweBps = -1;

    /** Whether this bandwidth estimator has been expired. Once expired we stop periodic re-allocation. */
    private boolean expired = false;

    /**
     * The "effective" constraints for an endpoint indicate the maximum resolution/fps that this
     * {@link BandwidthAllocator} would allocate for this endpoint given enough bandwidth.
     *
     * They are the constraints signaled by the receiver, further reduced to 0 when the endpoint is "outside
     * lastN".
     *
     * Effective constraints are used to signal to video senders to reduce their resolution to the minimum that
     * satisfies all receivers.
     *
     * With the multi-stream support added, the mapping is stored on a per source name basis instead of an
     * endpoint id.
     *
     * When an endpoint falls out of the last N, the constraints of all the sources of this endpoint are reduced
     * to 0.
     *
     * TODO Update this description when the endpoint ID signaling is removed from the JVB.
     */
    private Map<MediaSourceDesc, VideoConstraints> effectiveConstraints = Collections.emptyMap();

    private final EventEmitter<EventHandler> eventEmitter = new SyncEventEmitter<>();

    /** The allocations settings signalled by the receiver. */
    private AllocationSettings allocationSettings =
        new AllocationSettings(new VideoConstraints(BitrateControllerConfig.config.initialMaxHeightPx));

    /**
     * The last time {@link BandwidthAllocator#update()} was called.
     * Initialized as initialization time to prevent triggering an update immediately, because the settings might
     * not have been configured yet.
     */
    private Instant lastUpdateTime;

    /** The result of the bitrate control algorithm, the last time it ran. */
    private BandwidthAllocation allocation = new BandwidthAllocation(Collections.emptySet());

    /** The task scheduled to call {@link #update()}. */
    private ScheduledFuture<?> updateTask;

    BandwidthAllocator(
        EventHandler eventHandler,
        Supplier<List<T>> endpointsSupplier,
        Supplier<Boolean> trustBwe,
        Logger parentLogger,
        DiagnosticContext diagnosticContext,
        Clock clock)
    {
        this.logger = parentLogger.createChildLogger(BandwidthAllocator.class.getName());
        this.endpointsSupplier = endpointsSupplier;
        this.trustBwe = trustBwe;
        this.diagnosticContext = diagnosticContext;
        this.clock = clock;
        this.lastUpdateTime = clock.instant();
        this.eventEmitter.addHandler(eventHandler);

        rescheduleUpdate();
    }

    BandwidthAllocation getAllocation()
    {
        return allocation;
    }

    /** Gets a JSON representation of the parts of this object's state that are deemed useful for debugging. */
    ObjectNode getDebugState()
    {
        ObjectNode debugState = JsonNodeFactory.instance.objectNode();
        debugState.put("trustBwe", trustBwe.get());
        debugState.put("bweBps", bweBps);
        debugState.set("allocation", allocation.getDebugState());
        debugState.set("allocationSettings", allocationSettings.toJson());
        debugState.put(
            "effectiveConstraints",
            effectiveConstraints.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().getSourceName(), Map.Entry::getValue, (a, b) -> b))
                .toString()
        );
        return debugState;
    }

    /** Get the available bandwidth, taking into account the `trustBwe` option. */
    private long getAvailableBandwidth()
    {
        return trustBwe.get() ? bweBps : Long.MAX_VALUE;
    }

    /**
     * Notify the {@link BandwidthAllocator} that the estimated available bandwidth has changed.
     * @param newBandwidthBps the newly estimated bandwidth in bps
     */
    void bandwidthChanged(long newBandwidthBps)
    {
        if (!bweChangeIsLargerThanThreshold(bweBps, newBandwidthBps))
        {
            logger.debug(() -> "New bwe (" + newBandwidthBps + ") is not significantly changed from previous bwe " +
                "(" + bweBps + "), ignoring.");
            // If this is a "negligible" change in the bandwidth estimation
            // wrt the last bandwidth estimation that we reacted to, then
            // do not update the bandwidth allocation. The goal is to limit
            // the resolution changes due to bandwidth estimation changes,
            // as often resolution changes can negatively impact user
            // experience, at the risk of clogging the receiver pipe.
        }
        else
        {
            logger.debug(() -> "new bandwidth is " + newBandwidthBps + ", updating");
            bweBps = newBandwidthBps;
            update();
        }
    }

    /**
     * Updates the allocation settings and calculates a new bitrate {@link BandwidthAllocation}.
     * @param allocationSettings the new allocation settings.
     */
    void update(AllocationSettings allocationSettings)
    {
        this.allocationSettings = allocationSettings;
        update();
    }

    /**
     * Runs the bandwidth allocation algorithm, and fires events if the result is different from the previous
     * result.
     */
    synchronized void update()
    {
        if (expired)
        {
            return;
        }
        lastUpdateTime = clock.instant();

        // Order the sources by selection, followed by Endpoint's speech activity.
        List<MediaSourceDesc> sources = new ArrayList<>();
        for (T endpoint : endpointsSupplier.get())
        {
            Collections.addAll(sources, endpoint.getMediaSources());
        }
        List<MediaSourceDesc> sortedSources = Prioritize.prioritize(sources, getSelectedSources());

        // Extract and update the effective constraints.
        Map<MediaSourceDesc, VideoConstraints> oldEffectiveConstraints = effectiveConstraints;
        Map<MediaSourceDesc, VideoConstraints> newEffectiveConstraints =
            Prioritize.getEffectiveConstraints(sortedSources, allocationSettings);
        effectiveConstraints = newEffectiveConstraints;

        logger.trace(() -> "Allocating: sortedSources=" +
            sortedSources.stream().map(MediaSourceDesc::getSourceName).collect(Collectors.toList()) +
            ", effectiveConstraints=" +
            newEffectiveConstraints.entrySet().stream()
                .map(e -> e.getKey().getSourceName() + "=" + e.getValue())
                .collect(Collectors.toList()));

        // Compute the bandwidth allocation.
        BandwidthAllocation newAllocation = allocate(sortedSources);
        boolean allocationChanged = !allocation.isTheSameAs(newAllocation);
        boolean effectiveConstraintsChanged = !effectiveConstraints.equals(oldEffectiveConstraints);

        if (timeSeriesLogger.isTraceEnabled())
        {
            timeSeriesLogger.trace(
                diagnosticContext.makeTimeSeriesPoint("allocator_update", lastUpdateTime)
                    .addField("target_bps", newAllocation.getTargetBps())
                    .addField("ideal_bps", newAllocation.getIdealBps())
                    .addField("bwe_bps", bweBps)
                    .addField("oversending", newAllocation.isOversending())
                    .addField("allocation_changed", allocationChanged)
                    .addField("effective_constraints_changed", effectiveConstraintsChanged)
            );
        }

        if (allocationChanged)
        {
            eventEmitter.fireEvent(handler -> {
                handler.allocationChanged(newAllocation);
                return Unit.INSTANCE;
            });
        }

        allocation = newAllocation;

        logger.trace(() -> "Finished allocation: allocationChanged=" + allocationChanged +
            ", effectiveConstraintsChanged=" + effectiveConstraintsChanged + ", allocation=[" + allocation + "]");
        if (effectiveConstraintsChanged)
        {
            Map<MediaSourceDesc, VideoConstraints> oldConstraintsCopy = oldEffectiveConstraints;
            eventEmitter.fireEvent(handler -> {
                handler.effectiveVideoConstraintsChanged(oldConstraintsCopy, effectiveConstraints);
                return Unit.INSTANCE;
            });
        }
    }

    // On-stage sources are considered selected (with higher priority).
    private List<String> getSelectedSources()
    {
        List<String> selectedSources = new ArrayList<>(allocationSettings.getOnStageSources());
        for (String source : allocationSettings.getSelectedSources())
        {
            if (!selectedSources.contains(source))
            {
                selectedSources.add(source);
            }
        }
        return selectedSources;
    }

    /**
     * Implements the bandwidth allocation algorithm for the given ordered list of media sources.
     *
     * The new version which works with multiple streams per endpoint.
     *
     * @param conferenceMediaSources the list of endpoint media sources in order of priority to allocate for.
     * @return the new {@link BandwidthAllocation}.
     */
    private synchronized BandwidthAllocation allocate(List<MediaSourceDesc> conferenceMediaSources)
    {
        List<SingleSourceAllocation> sourceBitrateAllocations = createAllocations(conferenceMediaSources);
        if (sourceBitrateAllocations.isEmpty())
        {
            return new BandwidthAllocation(Collections.emptySet());
        }
        long remainingBandwidth = allocationSettings.getAssumedBandwidthBps() >= 0
            ? allocationSettings.getAssumedBandwidthBps()
            : getAvailableBandwidth();
        if (allocationSettings.getAssumedBandwidthBps() >= 0)
        {
            logger.warn("Allocating with assumed bandwidth " +
                Bandwidth.ofBps(allocationSettings.getAssumedBandwidthBps()) + ".");
        }
        long oldRemainingBandwidth = -1;
        boolean oversending = false;
        while (oldRemainingBandwidth != remainingBandwidth)
        {
            oldRemainingBandwidth = remainingBandwidth;
            for (int i = 0; i < sourceBitrateAllocations.size(); i++)
            {
                SingleSourceAllocation sourceBitrateAllocation = sourceBitrateAllocations.get(i);
                if (sourceBitrateAllocation.getConstraints().isDisabled())
                {
                    continue;
                }

                // In stage view improve greedily until preferred, in tile view go step-by-step.
                remainingBandwidth -= sourceBitrateAllocation.improve(remainingBandwidth, i == 0);
                if (remainingBandwidth < 0)
                {
                    oversending = true;
                }

                // In stage view, do not allocate bandwidth for thumbnails until the on-stage reaches "preferred".
                // This prevents enabling thumbnail only to disable them when bwe slightly increases allowing
                // on-stage to take more.
                if (sourceBitrateAllocation.isOnStage() && !sourceBitrateAllocation.hasReachedPreferred())
                {
                    break;
                }
            }
        }

        // The sources which are in lastN, and are sending video, but were suspended due to bwe.
        List<String> suspendedIds = sourceBitrateAllocations.stream()
            .filter(SingleSourceAllocation::isSuspended)
            .map(a -> a.getMediaSource().getSourceName())
            .collect(Collectors.toList());
        if (!suspendedIds.isEmpty())
        {
            logger.info("Sources suspended due to insufficient bandwidth (bwe=" + getAvailableBandwidth() +
                " bps): " + suspendedIds);
        }
        Set<BandwidthAllocation.SingleAllocation> allocations = new HashSet<>();
        long targetBps = 0;
        long idealBps = 0;
        for (SingleSourceAllocation sourceBitrateAllocation : sourceBitrateAllocations)
        {
            allocations.add(sourceBitrateAllocation.getResult());
            targetBps += sourceBitrateAllocation.getTargetBitrate();
            idealBps += sourceBitrateAllocation.getIdealBitrate();
        }
        return new BandwidthAllocation(allocations, oversending, idealBps, targetBps, suspendedIds);
    }

    /**
     * Query whether the allocator has non-zero effective constraints for the given endpoint or source.
     */
    boolean hasNonZeroEffectiveConstraints(MediaSourceDesc source)
    {
        VideoConstraints constraints = effectiveConstraints.get(source);
        return constraints != null && !constraints.isDisabled();
    }

    private synchronized List<SingleSourceAllocation> createAllocations(List<MediaSourceDesc> conferenceMediaSources)
    {
        List<SingleSourceAllocation> result = new ArrayList<>();
        for (MediaSourceDesc source : conferenceMediaSources)
        {
            result.add(new SingleSourceAllocation(
                source.getOwner(),
                source,
                // Note that we use the effective constraints and not the receiver's constraints
                // directly. This means we never even try to allocate bitrate to sources "outside
                // lastN". For example, if LastN=1 and the first endpoint sends a non-scalable
                // stream with bitrate higher that the available bandwidth, we will forward no
                // video at all instead of going to the second endpoint in the list.
                // I think this is not desired behavior. However, it is required for the "effective
                // constraints" to work as designed.
                effectiveConstraints.get(source),
                allocationSettings.getOnStageSources().contains(source.getSourceName()),
                diagnosticContext,
                clock,
                logger
            ));
        }
        return result;
    }

    /**
     * Expire this bandwidth allocator.
     */
    void expire()
    {
        expired = true;
        if (updateTask != null)
        {
            updateTask.cancel(false);
        }
    }

    /**
     * Submits a call to `update` in a CPU thread if bandwidth allocation has not been performed recently.
     *
     * Also, re-schedule the next update in at most `maxTimeBetweenCalculations`. This should only be run
     * in the constructor or in the scheduler thread, otherwise it will schedule multiple tasks.
     */
    private void rescheduleUpdate()
    {
        if (expired)
        {
            return;
        }
        Duration timeSinceLastUpdate = Duration.between(lastUpdateTime, clock.instant());
        Duration period = BitrateControllerConfig.config.maxTimeBetweenCalculations;
        long delayMs;
        if (timeSinceLastUpdate.compareTo(period) > 0)
        {
            logger.debug("Running periodic re-allocation.");
            TaskPools.CPU_POOL.execute(this::update);
            delayMs = period.toMillis();
        }
        else
        {
            delayMs = period.minus(timeSinceLastUpdate).toMillis();
        }

        // Add 5ms to avoid having to re-schedule right away. This increases the average period at which we
        // re-allocate by an insignificant amount.
        updateTask = TaskPools.SCHEDULED_POOL.schedule(
            this::rescheduleUpdate,
            delayMs + 5,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Returns a boolean that indicates whether the current bandwidth estimation (in bps) has changed above the
     * configured threshold with respect to the previous bandwidth estimation.
     *
     * @param previousBwe the previous bandwidth estimation (in bps).
     * @param currentBwe the current bandwidth estimation (in bps).
     * @return true if the bandwidth has changed above the configured threshold, false otherwise.
     */
    private static boolean bweChangeIsLargerThanThreshold(long previousBwe, long currentBwe)
    {
        if (previousBwe == currentBwe)
        { // Even if we're "changing" -1 to -1
            return false;
        }
        if (previousBwe == -1L || currentBwe == -1L)
        {
            return true;
        }

        // We suppress re-allocation when BWE has changed less than 15% (by default) of its previous value in
        // order to prevent excessive changes during ramp-up.
        // When BWE increases it should eventually increase past the threshold because of probing.
        // When BWE decreases it is probably above the threshold because of AIMD. It's not clear to me whether we
        // need the threshold in this case.
        // In any case, there are other triggers for re-allocation, so any suppression we do here will only last
        // up to a few seconds.
        long deltaBwe = Math.abs(currentBwe - previousBwe);
        return deltaBwe > previousBwe * BitrateControllerConfig.config.bweChangeThreshold;

        // If, on the other hand, the bwe has decreased, we require at least a 15% drop in order to update the
        // bitrate allocation. This is an ugly hack to prevent too many resolution/UI changes in case the bridge
        // produces too low bandwidth estimate, at the risk of clogging the receiver's pipe.
        // TODO: do we still need this? Do we ever ever see BWE drop by <%15?
    }

    interface EventHandler
    {
        void allocationChanged(BandwidthAllocation allocation);

        void effectiveVideoConstraintsChanged(
            Map<MediaSourceDesc, VideoConstraints> oldEffectiveConstraints,
            Map<MediaSourceDesc, VideoConstraints> newEffectiveConstraints
        );
    }
}
