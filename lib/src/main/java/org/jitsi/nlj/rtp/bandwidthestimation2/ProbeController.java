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
package org.jitsi.nlj.rtp.bandwidthestimation2;

import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.utils.DurationKt;
import org.jitsi.utils.InstantKt;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.utils.logging2.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Probe controller,
 * based on WebRTC modules/congestion_controller/goog_cc/probe_controller.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Field trial settings have been generally removed, set to their default settings.
 *
 * This class controls initiation of probing to estimate initial channel
 * capacity. There is also support for probing during a session when max
 * bitrate is adjusted by an application.
 */
public class ProbeController
{
    // Maximum waiting time from the time of initiating probing to getting
    // the measured results back.
    private static final Duration kMaxWaitingTimeForProbingResult = DurationKt.getSecs(1);

    // Default probing bitrate limit. Applied only when the application didn't
    // specify max bitrate.
    private static final Bandwidth kDefaultMaxProbingBitrate = Bandwidth.ofKbps(5000);

    // If the bitrate drops to a factor `kBitrateDropThreshold` or lower
    // and we recover within `kBitrateDropTimeoutMs`, then we'll send
    // a probe at a fraction `kProbeFractionAfterDrop` of the original bitrate.
    private static final double kBitrateDropThreshold = 0.66;
    private static final Duration kBitrateDropTimeout = DurationKt.getSecs(5);
    private static final double kProbeFractionAfterDrop = 0.85;

    // Timeout for probing after leaving ALR. If the bitrate drops significantly,
    // (as determined by the delay based estimator) and we leave ALR, then we will
    // send a probe if we recover within `kLeftAlrTimeoutMs` ms.
    private static final Duration kAlrEndedTimeout = DurationKt.getSecs(3);

    // This is a limit on how often probing can be done when there is a BW
    // drop detected in ALR.
    private static final Duration kMinTimeBetweenAlrProbes = DurationKt.getSecs(5);

    // The expected uncertainty of probe result (as a fraction of the target probe
    // bitrate). Used to avoid probing if the probe bitrate is close to our current
    // estimate.
    private static final double kProbeUncertainty = 0.05;

    private final DiagnosticContext diagnosticContext;
    private final ProbeControllerConfig config;

    private final Logger logger;

    private boolean networkAvailable = false;
    private boolean repeatedInitialProbingEnabled = false;
    private Instant lastAllowedRepeatedInitialProbe = Instant.MIN;
    private BandwidthLimitedCause bandwidthLimitedCause = BandwidthLimitedCause.kDelayBasedLimited;
    private State state = State.kInit;
    private Bandwidth minBitrateToProbeFurther = Bandwidth.INFINITY;
    private Instant timeLastProbingInitiated = Instant.MIN;
    private Bandwidth estimatedBitrate = Bandwidth.ZERO;

    /* Skipping network_estimate */

    private Bandwidth startBitrate = Bandwidth.ZERO;
    private Bandwidth maxBitrate = Bandwidth.INFINITY;
    private Instant lastBweDropProbingTime = Instant.MIN;
    private Instant alrStartTime = null;
    private Instant alrEndTime = null;
    private boolean enablePeriodicAlrProbing = false;
    private Instant timeOfLastLargeDrop = Instant.MIN;
    private Bandwidth bitrateBeforeLastLargeDrop = Bandwidth.ZERO;
    private Bandwidth maxTotalAllocatedBitrate = Bandwidth.ZERO;

    private final boolean inRapidRecoveryExperiment = false;

    private int nextProbeClusterId = 1;

    public ProbeController(Logger parentLogger, DiagnosticContext diagnosticContext, ProbeControllerConfig config)
    {
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.diagnosticContext = diagnosticContext;
        this.config = config;
    }

    public ProbeController(Logger parentLogger, DiagnosticContext diagnosticContext)
    {
        this(parentLogger, diagnosticContext, new ProbeControllerConfig());
    }

    public List<ProbeClusterConfig> setBitrates(
        Bandwidth minBitrate,
        Bandwidth startBitrate,
        Bandwidth maxBitrate,
        Instant atTime)
    {
        if (startBitrate.compareTo(Bandwidth.ZERO) > 0)
        {
            this.startBitrate = startBitrate;
            estimatedBitrate = startBitrate;
        }
        else if (this.startBitrate.equals(Bandwidth.ZERO))
        {
            this.startBitrate = minBitrate;
        }

        // The reason we use the variable `old_max_bitrate_pbs` is because we
        // need to set `max_bitrate_` before we call InitiateProbing.
        Bandwidth oldMaxBitrate = this.maxBitrate;
        this.maxBitrate = maxBitrate.isFinite() ? maxBitrate : kDefaultMaxProbingBitrate;

        switch (state)
        {
            case kInit:
                if (networkAvailable)
                {
                    return initiateExponentialProbing(atTime);
                }
                break;

            case kWaitingForProbingResult:
                break;

            case kProbingComplete:
                // If the new max bitrate is higher than both the old max bitrate and the
                // estimate then initiate probing.
                if (!estimatedBitrate.equals(Bandwidth.ZERO) && oldMaxBitrate.compareTo(this.maxBitrate) < 0 &&
                    estimatedBitrate.compareTo(this.maxBitrate) < 0)
                {
                    return initiateProbing(atTime, List.of(maxBitrate), false);
                }
                break;
        }

        return new ArrayList<>();
    }

    // The total bitrate, as opposed to the max bitrate, is the sum of the
    // configured bitrates for all active streams.
    public List<ProbeClusterConfig> onMaxTotalAllocatedBitrate(
        Bandwidth maxTotalAllocatedBitrate,
        Instant atTime)
    {
        boolean inAlr = alrStartTime != null;
        boolean allowAllocationProbe = inAlr;

        if (config.probeOnMaxAllocatedBitrateChange &&
            state == State.kProbingComplete &&
            !maxTotalAllocatedBitrate.equals(this.maxTotalAllocatedBitrate) &&
            estimatedBitrate.compareTo(maxBitrate) < 0 &&
            estimatedBitrate.compareTo(maxTotalAllocatedBitrate) < 0 &&
            allowAllocationProbe)
        {
            this.maxTotalAllocatedBitrate = maxTotalAllocatedBitrate;

            if (config.firstAllocationProbeScale == null)
            {
                return new ArrayList<>();
            }
            Bandwidth firstProbeRate = maxTotalAllocatedBitrate.times(config.firstAllocationProbeScale);
            Bandwidth currentBweLimit =
                estimatedBitrate.times(config.allocationProbeLimitByCurrentScale);
            boolean limitedByCurrentBwe = currentBweLimit.compareTo(firstProbeRate) < 0;
            if (limitedByCurrentBwe)
            {
                firstProbeRate = currentBweLimit;
            }

            List<Bandwidth> probes = new ArrayList<>();
            probes.add(firstProbeRate);
            if (!limitedByCurrentBwe && config.secondAllocationProbeScale != null)
            {
                Bandwidth secondProbeRate = maxTotalAllocatedBitrate.times(config.secondAllocationProbeScale);
                limitedByCurrentBwe = currentBweLimit.compareTo(secondProbeRate) < 0;
                if (limitedByCurrentBwe)
                {
                    secondProbeRate = currentBweLimit;
                }
                if (secondProbeRate.compareTo(firstProbeRate) > 0)
                {
                    probes.add(secondProbeRate);
                }
            }
            boolean allowFurtherProbing = limitedByCurrentBwe;
            return initiateProbing(atTime, probes, allowFurtherProbing);
        }
        if (!maxTotalAllocatedBitrate.equals(Bandwidth.ZERO))
        {
            lastAllowedRepeatedInitialProbe = atTime;
        }

        this.maxTotalAllocatedBitrate = maxTotalAllocatedBitrate;
        return new ArrayList<>();
    }

    public List<ProbeClusterConfig> onNetworkAvailability(NetworkAvailability msg)
    {
        networkAvailable = msg.networkAvailable;

        if (!networkAvailable && state == State.kWaitingForProbingResult)
        {
            state = State.kProbingComplete;
            minBitrateToProbeFurther = Bandwidth.INFINITY;
        }

        if (networkAvailable && state == State.kInit && !startBitrate.equals(Bandwidth.ZERO))
        {
            return initiateExponentialProbing(msg.atTime);
        }
        return new ArrayList<>();
    }

    private void updateState(State newState)
    {
        switch (newState)
        {
            case kInit:
                state = State.kInit;
                break;
            case kWaitingForProbingResult:
                state = State.kWaitingForProbingResult;
                break;
            case kProbingComplete:
                state = State.kProbingComplete;
                minBitrateToProbeFurther = Bandwidth.INFINITY;
                break;
        }
    }

    private List<ProbeClusterConfig> initiateExponentialProbing(Instant atTime)
    {
        assert (networkAvailable);
        assert (state == State.kInit);
        assert (startBitrate.compareTo(Bandwidth.ZERO) > 0);

        // When probing at 1.8 Mbps ( 6x 300), this represents a threshold of
        // 1.2 Mbps to continue probing.
        List<Bandwidth> probes = new ArrayList<>();
        probes.add(startBitrate.times(config.firstExponentialProbeScale));
        if (config.secondExponentialProbeScale != null && config.secondExponentialProbeScale > 0.0)
        {
            probes.add(startBitrate.times(config.secondExponentialProbeScale));
        }
        if (repeatedInitialProbingEnabled && maxTotalAllocatedBitrate.equals(Bandwidth.ZERO))
        {
            lastAllowedRepeatedInitialProbe =
                atTime.plus(config.repeatedInitialProbingTimePeriod);
            logger.info(() ->
                "Repeated initial probing enabled, last allowed probe: " + lastAllowedRepeatedInitialProbe +
                    " now: " + atTime
            );
        }

        return initiateProbing(atTime, probes, true);
    }

    public List<ProbeClusterConfig> setEstimatedBitrate(
        Bandwidth bitrate,
        BandwidthLimitedCause bandwidthLimitedCause,
        Instant atTime)
    {
        this.bandwidthLimitedCause = bandwidthLimitedCause;
        if (bitrate.compareTo(estimatedBitrate.times(kBitrateDropThreshold)) < 0)
        {
            timeOfLastLargeDrop = atTime;
            bitrateBeforeLastLargeDrop = estimatedBitrate;
        }
        estimatedBitrate = bitrate;

        if (state == State.kWaitingForProbingResult)
        {
            // Continue probing if probing results indicate channel has greater
            // capacity unless we already reached the needed bitrate.
            if (config.abortFurtherProbeIfMaxLowerThanCurrent && (
                bitrate.compareTo(maxBitrate) > 0 || (
                    !maxTotalAllocatedBitrate.equals(Bandwidth.ZERO) &&
                        bitrate.compareTo(maxTotalAllocatedBitrate.times(2)) > 0
                    )
                ))
            {
                // No need to continue probing
                minBitrateToProbeFurther = Bandwidth.INFINITY;
            }
            Bandwidth networkStateEstimateProbeFurtherLimit =
                /* Skipping networkEstimate */
                Bandwidth.INFINITY;
            logger.info(
                "Measured bitrate: " + bitrate + " Minimum to probe further: " + minBitrateToProbeFurther +
                    " upper limit: " + networkStateEstimateProbeFurtherLimit
            );

            if (bitrate.compareTo(minBitrateToProbeFurther) > 0 &&
                bitrate.compareTo(networkStateEstimateProbeFurtherLimit) <= 0)
            {
                return initiateProbing(atTime, List.of(bitrate.times(config.furtherExponentialProbeScale)), true);
            }
        }
        return new ArrayList<>();
    }

    public void enablePeriodicAlrProbing(boolean enable)
    {
        enablePeriodicAlrProbing = enable;
    }

    // Probes are sent periodically every 1s during the first 5s after the network
    // becomes available or until OnMaxTotalAllocatedBitrate is invoked with a
    // none zero max_total_allocated_bitrate (there are active streams being
    // sent.) Probe rate is up to max configured bitrate configured via
    // SetBitrates.
    public void enableRepeatedInitialProbing(boolean enable)
    {
        repeatedInitialProbingEnabled = enable;
    }

    public void setAlrStartTimeMs(Long alrStartTimeMs)
    {
        if (alrStartTimeMs != null)
        {
            alrStartTime = Instant.ofEpochMilli(alrStartTimeMs);
        }
        else
        {
            alrStartTime = null;
        }
    }

    public void setAlrEndedTimeMs(long alrEndTimeMs)
    {
        alrEndTime = Instant.ofEpochMilli(alrEndTimeMs);
    }

    public List<ProbeClusterConfig> requestProbe(Instant atTime)
    {
        // Called once we have returned to normal state after a large drop in
        // estimated bandwidth. The current response is to initiate a single probe
        // session (if not already probing) at the previous bitrate.
        //
        // If the probe session fails, the assumption is that this drop was a
        // real one from a competing flow or a network change.
        boolean inAlr = alrStartTime != null;
        boolean alrEndedRecently = (
            alrEndTime != null &&
                Duration.between(alrEndTime, atTime).compareTo(kAlrEndedTimeout) < 0
            );
        if (inAlr || alrEndedRecently || inRapidRecoveryExperiment)
        {
            if (state == State.kProbingComplete)
            {
                Bandwidth suggestedProbe = bitrateBeforeLastLargeDrop.times(kProbeFractionAfterDrop);
                Bandwidth minExpectedProbeResult = suggestedProbe.times(1 - kProbeUncertainty);
                Duration timeSinceDrop = Duration.between(timeOfLastLargeDrop, atTime);
                Duration timeSinceProbe = Duration.between(lastBweDropProbingTime, atTime);
                if (minExpectedProbeResult.compareTo(estimatedBitrate) > 0 &&
                    timeSinceDrop.compareTo(kBitrateDropTimeout) < 0 &&
                    timeSinceProbe.compareTo(kMinTimeBetweenAlrProbes) > 0)
                {
                    logger.info("Detected big bandwidth drop, start probing");
                    // Track how often we probe in response to bandwidth drop in ALR.
                    // TODO: histogram
                    lastBweDropProbingTime = atTime;
                    return initiateProbing(atTime, List.of(suggestedProbe), false);
                }
            }
        }
        return new ArrayList<>();
    }

    /* Skipping setNetworkStateEstimate */

    /**
     * Resets the ProbeController to a state equivalent to as if it was just
     * created EXCEPT for configuration settings like
     * `enable_periodic_alr_probing_` `network_available_` and
     * `max_total_allocated_bitrate_`.
     */
    public void reset(Instant atTime)
    {
        bandwidthLimitedCause = BandwidthLimitedCause.kDelayBasedLimited;
        state = State.kInit;
        minBitrateToProbeFurther = Bandwidth.INFINITY;
        timeLastProbingInitiated = Instant.MIN;
        estimatedBitrate = Bandwidth.ZERO;
        startBitrate = Bandwidth.ZERO;
        maxBitrate = kDefaultMaxProbingBitrate;
        Instant now = atTime;
        lastBweDropProbingTime = now;
        alrEndTime = null;
        timeOfLastLargeDrop = now;
        bitrateBeforeLastLargeDrop = Bandwidth.ZERO;
    }

    private boolean timeForAlrProbe(Instant atTime)
    {
        if (enablePeriodicAlrProbing && alrStartTime != null)
        {
            Instant nextProbeTime =
                InstantKt.max(alrStartTime, timeLastProbingInitiated)
                    .plus(config.alrProbingInterval);
            return atTime.compareTo(nextProbeTime) >= 0;
        }
        return false;
    }

    private boolean timeForNetworkStateProbe(Instant atTime)
    {
        /* Not using network_estimate */
        return false;
    }

    private boolean timeForNextRepeatedInitialProbe(Instant atTime)
    {
        if (state != State.kWaitingForProbingResult &&
            lastAllowedRepeatedInitialProbe.isAfter(atTime))
        {
            Instant nextProbeTime = timeLastProbingInitiated.plus(kMaxWaitingTimeForProbingResult);
            if (atTime.compareTo(nextProbeTime) >= 0)
            {
                return true;
            }
        }
        return false;
    }

    private ProbeClusterConfig createProbeClusterConfig(Instant atTime, Bandwidth bitrate)
    {
        ProbeClusterConfig config = new ProbeClusterConfig();
        config.atTime = atTime;
        config.targetDataRate = bitrate;
        if (atTime.isBefore(lastAllowedRepeatedInitialProbe))
        {
            config.targetDuration = this.config.initialProbeDuration;
            config.minProbeDelta = this.config.initialMinProbeDelta;
        }
        else
        {
            config.targetDuration = this.config.minProbeDuration;
            config.minProbeDelta = this.config.minProbeDelta;
        }
        config.targetProbeCount = this.config.minProbePacketsSent;
        config.id = nextProbeClusterId;
        nextProbeClusterId++;
        maybeLogProbeClusterCreated(diagnosticContext, config);
        return config;
    }

    public List<ProbeClusterConfig> process(Instant atTime)
    {
        if (Duration.between(timeLastProbingInitiated, atTime).compareTo(kMaxWaitingTimeForProbingResult) > 0)
        {
            if (state == State.kWaitingForProbingResult)
            {
                logger.info("kWaitingForProbingResult: timeout");
                updateState(State.kProbingComplete);
            }
        }
        if (estimatedBitrate.equals(Bandwidth.ZERO) || state != State.kProbingComplete)
        {
            return new ArrayList<>();
        }
        if (timeForNextRepeatedInitialProbe(atTime))
        {
            return initiateProbing(atTime, List.of(estimatedBitrate.times(config.firstExponentialProbeScale)), true);
        }
        if (timeForAlrProbe(atTime) || timeForNetworkStateProbe(atTime))
        {
            return initiateProbing(atTime, List.of(estimatedBitrate.times(config.alrProbeScale)), true);
        }
        return new ArrayList<>();
    }

    private enum State
    {
        /** Initial state where no probing has been triggrered yet */
        kInit,

        /** Waiting for probing results to continue further probing. */
        kWaitingForProbingResult,

        /** Probing is complete. */
        kProbingComplete
    }

    private List<ProbeClusterConfig> initiateProbing(
        Instant now,
        List<Bandwidth> bitratesToProbe,
        boolean probeFurtherIn)
    {
        boolean probeFurther = probeFurtherIn;
        if (config.skipIfEstimateLargerThanFractionOfMax > 0.0)
        {
            Bandwidth networkEstimate = Bandwidth.INFINITY;
            Bandwidth maxProbeRate = maxTotalAllocatedBitrate.equals(Bandwidth.ZERO)
                ? maxBitrate
                : Bandwidth.min(maxTotalAllocatedBitrate.times(config.skipProbeMaxAllocatedScale), maxBitrate);
            if (Bandwidth.min(networkEstimate, estimatedBitrate)
                .compareTo(maxProbeRate.times(config.skipIfEstimateLargerThanFractionOfMax)) > 0)
            {
                updateState(State.kProbingComplete);
                return new ArrayList<>();
            }
        }

        Bandwidth maxProbeBitrate = maxBitrate;
        if (maxTotalAllocatedBitrate.compareTo(Bandwidth.ZERO) > 0)
        {
            // If a max allocated bitrate has been configured, allow probing up to 2x
            // that rate. This allows some overhead to account for bursty streams,
            // which otherwise would have to ramp up when the overshoot is already in
            // progress.
            // It also avoids minor quality reduction caused by probes often being
            // received at slightly less than the target probe bitrate.
            maxProbeBitrate = Bandwidth.min(maxProbeBitrate, maxTotalAllocatedBitrate.times(2));
        }

        switch (bandwidthLimitedCause)
        {
            case kRttBasedBackOffHighRtt:
            case kDelayBasedLimitedDelayIncreased:
            case kLossLimitedBwe:
                logger.info(() -> "Not sending probe in bandwidth limited state. " + bandwidthLimitedCause);
                return new ArrayList<>();
            case kLossLimitedBweIncreasing:
                maxProbeBitrate =
                    Bandwidth.min(maxProbeBitrate, estimatedBitrate.times(config.lossLimitedProbeScale));
                break;
            case kDelayBasedLimited:
                break;
            default:
                break;
        }

        /* Skipping use of networkEstimate */

        List<ProbeClusterConfig> pendingProbes = new ArrayList<>();
        for (Bandwidth b : bitratesToProbe)
        {
            assert (!b.equals(Bandwidth.ZERO));
            Bandwidth bitrate = b;
            if (bitrate.compareTo(maxProbeBitrate) >= 0)
            {
                bitrate = maxProbeBitrate;
                probeFurther = false;
            }

            pendingProbes.add(createProbeClusterConfig(now, bitrate));
        }
        timeLastProbingInitiated = now;
        if (probeFurther)
        {
            updateState(State.kWaitingForProbingResult);
            // Dont expect probe results to be larger than a fraction of the actual
            // probe rate.
            minBitrateToProbeFurther =
                pendingProbes.get(pendingProbes.size() - 1).targetDataRate.times(config.furtherProbeThreshold);
        }
        else
        {
            updateState(State.kProbingComplete);
        }
        return pendingProbes;
    }

    private static final TimeSeriesLogger timeSeriesLogger =
        TimeSeriesLogger.getTimeSeriesLogger(ProbeController.class);

    private static void maybeLogProbeClusterCreated(DiagnosticContext diagnosticContext, ProbeClusterConfig probe)
    {
        DataSize minDataSize = probe.targetDataRate.times(probe.targetDuration);
        if (timeSeriesLogger.isTraceEnabled())
        {
            timeSeriesLogger.trace(
                diagnosticContext.makeTimeSeriesPoint("ProbeClusterCreated")
                    .addField("probe_id", probe.id)
                    .addField("probe_target_data_rate_bps", probe.targetDataRate.getBps())
                    .addField("probe_target_probe_count", probe.targetProbeCount)
                    .addField("probe_min_data_size", minDataSize.getBytes())
            );
        }
    }
}

class ProbeControllerConfig
{
    // These parameters configure the initial probes. First we send one or two
    // probes of sizes p1 * start_bitrate_ and p2 * start_bitrate_.
    // Then whenever we get a bitrate estimate of at least further_probe_threshold
    // times the size of the last sent probe we'll send another one of size
    // step_size times the new estimate.
    final double firstExponentialProbeScale = 3.0;
    final Double secondExponentialProbeScale = 6.0;
    final double furtherExponentialProbeScale = 2.0;
    final double furtherProbeThreshold = 0.7;
    final boolean abortFurtherProbeIfMaxLowerThanCurrent = false;

    final Duration repeatedInitialProbingTimePeriod = DurationKt.getSecs(5);
    // The minimum probing duration of an individual probe during
    // the repeated_initial_probing_time_period.
    final Duration initialProbeDuration = DurationKt.getMs(100);
    // Delta time between sent bursts of packets in a probe during
    // the repeated_initial_probing_time_period.
    final Duration initialMinProbeDelta = DurationKt.getMs(20);
    // Configures how often we send ALR probes and how big they are.
    final Duration alrProbingInterval = DurationKt.getSecs(5);
    final double alrProbeScale = 2.0;
    // Configures how often we send probes if NetworkStateEstimate is available.
    final Duration networkStateEstimateProbingInterval = DurationKt.getMAX_DURATION();
    // Periodically probe as long as the ratio between current estimate and
    // NetworkStateEstimate is lower then this.
    final double probeIfEstimateLowerThanNetworkStateEstimateRatio = 0.0;
    final Duration estimateLowerThanNetworkStateProbingInterval = DurationKt.getSecs(3);
    final double networkStateProbeScale = 1.0;
    // Overrides min_probe_duration if network_state_estimate_probing_interval
    // is set and a network state estimate is known.
    final Duration networkStateProbeDuration = DurationKt.getMs(15);
    // Overrides min_probe_delta if network_state_estimate_probing_interval
    // is set and a network state estimate is known and equal or higher than the
    // probe target.
    final Duration networkStateMinProbeDelta = DurationKt.getMs(20);

    // Configures the probes emitted by changed to the allocated bitrate.
    final boolean probeOnMaxAllocatedBitrateChange = true;
    final Double firstAllocationProbeScale = 1.0;
    final Double secondAllocationProbeScale = 2.0;
    final double allocationProbeLimitByCurrentScale = 2.0;

    // The minimum number probing packets used.
    final int minProbePacketsSent = 5;
    // The minimum probing duration.
    final Duration minProbeDuration = DurationKt.getMs(15);
    // Delta time between sent bursts of packets in a probe.
    final Duration minProbeDelta = DurationKt.getMs(2);
    final double lossLimitedProbeScale = 1.5;
    // Don't send a probe if min(estimate, network state estimate) is larger than
    // this fraction of the set max or max allocated bitrate.
    final double skipIfEstimateLargerThanFractionOfMax = 0.0;
    // Scale factor of the max allocated bitrate. Used when deciding if a probe
    // can be skiped due to that the estimate is already high enough.
    final double skipProbeMaxAllocatedScale = 1.0;
}
