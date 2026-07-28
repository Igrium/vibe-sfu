/*
 * Copyright @ 2019-present 8x8, Inc
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
// This file uses WebRTC's naming style for enums

package org.jitsi.nlj.rtp.bandwidthestimation2;

import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.utils.DurationKt;
import org.jitsi.utils.InstantKt;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loss-based bandwidth estimation,
 * based on WebRTC modules/congestion_controller/goog_cc/loss_based_bwe_v2.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Field trial settings are settable through the API, but not currently as config options.
 *
 * (Deviation: upstream's LossBasedBweV2.kt also declares the top-level enum LossBasedState,
 * ported as its own top-level file LossBasedState.java in this package.)
 */
public class LossBasedBweV2
{
    private static final Duration kInitHoldDuration = DurationKt.getMs(300);
    private static final Duration kMaxHoldDuration = DurationKt.getSecs(60);

    private static boolean isValid(Bandwidth datarate)
    {
        return datarate != null && datarate.isFinite();
    }

    private static boolean isValid(Instant timestamp)
    {
        return InstantKt.isFinite(timestamp);
    }

    private static double getLossProbability(
        double inherentLoss_,
        Bandwidth lossLimitedBandwidth,
        Bandwidth sendingRate)
    {
        if (inherentLoss_ < 0.0 || inherentLoss_ > 1.0)
        {
            logger.warn("The inherent loss must be in [0,1]: " + inherentLoss_);
        }
        double inherentLoss = Math.min(Math.max(inherentLoss_, 0.0), 1.0);
        if (!sendingRate.isFinite())
        {
            logger.warn("The sending rate must be finite: " + sendingRate);
        }
        if (!lossLimitedBandwidth.isFinite())
        {
            logger.warn("The loss limited bandwidth must be finite: " + lossLimitedBandwidth);
        }
        double lossProbability = inherentLoss;

        if (isValid(sendingRate) && isValid(lossLimitedBandwidth) &&
            (sendingRate.compareTo(lossLimitedBandwidth) > 0))
        {
            lossProbability += (1 - inherentLoss) *
                (sendingRate.minus(lossLimitedBandwidth).div(sendingRate));
        }
        return Math.min(Math.max(lossProbability, 1.0e-6), 1.0 - 1.0e-6);
    }

    private final Config config;
    private Bandwidth acknowledgedBitrate = null;
    private ChannelParameters currentBestEstimate = new ChannelParameters();
    private int numObservations = 0;
    private final List<Observation> observations = new ArrayList<>();
    private PartialObservation partialObservation = new PartialObservation();
    private Instant lastSendTimeMostRecentObservation = Instant.MAX;
    private Instant lastTimeEstimateReduced = Instant.MIN;
    private Bandwidth cachedInstantUpperBound = null;
    private Bandwidth cachedInstantLowerBound = null;
    private final double[] instantUpperBoundTemporalWeights;
    private final double[] temporalWeights;
    private Instant recoveringAfterLossTimestamp = Instant.MIN;
    private Bandwidth bandwidthLimitInCurrentWindow = Bandwidth.INFINITY;
    private Bandwidth minBitrate = Bandwidth.ofKbps(1);
    private Bandwidth maxBitrate = Bandwidth.INFINITY;
    private Bandwidth delayBasedEstimate = Bandwidth.INFINITY;
    private Result lossBasedResult = new Result();
    private HoldInfo lastHoldInfo = new HoldInfo(Instant.MIN, kInitHoldDuration, Bandwidth.INFINITY);
    private PaddingInfo lastPaddingInfo = new PaddingInfo();
    private double averageReportedLossRatio = 0.0;

    public LossBasedBweV2(Config configIn)
    {
        this.config = configIn.copy();
        currentBestEstimate.inherentLoss = config.initialInherentLossEstimate;
        for (int i = 0; i < config.observationWindowSize; i++)
        {
            observations.add(new Observation());
        }
        instantUpperBoundTemporalWeights = new double[config.observationWindowSize];
        temporalWeights = new double[config.observationWindowSize];

        if (!config.enabled)
        {
            logger.debug(
                "The configuration does not specify that the " +
                    "estimator should be enabled, disabling it.");
        }
        if (!config.isValid())
        {
            logger.warn("The configuration is not valid, disabling the estimator.");
            config.enabled = false;
        }
        calculateTemporalWeights();
    }

    public LossBasedBweV2()
    {
        this(defaultConfig);
    }

    public static class Result
    {
        public Bandwidth bandwidthEstimate = Bandwidth.ZERO;
        public LossBasedState state = LossBasedState.kDelayBasedEstimate;

        public Result()
        {
        }

        public Result(Bandwidth bandwidthEstimate, LossBasedState state)
        {
            this.bandwidthEstimate = bandwidthEstimate;
            this.state = state;
        }

        public Result copy()
        {
            return new Result(bandwidthEstimate, state);
        }
    }

    public boolean isEnabled()
    {
        return config.enabled;
    }

    /** Returns true iff a BWE can be calculated, i.e., the estimator has been
     initialized with a BWE and then has received enough `PacketResult`s.
     */
    public boolean isReady()
    {
        return isEnabled() &&
            isValid(currentBestEstimate.lossLimitedBandwidth) &&
            numObservations >= config.minNumObservations;
    }

    public boolean readyToUseInStartPhase()
    {
        return isReady() && config.useInStartPhase;
    }

    public boolean useInStartPhase()
    {
        return config.useInStartPhase;
    }

    /** Returns {@link Bandwidth#INFINITY} if no BWE can be calculated. */
    public Result getLossBasedResult()
    {
        if (!isReady())
        {
            if (!isEnabled())
            {
                logger.warn("The estimator must be enabled before it can be used.");
            }
            else
            {
                if (!isValid(currentBestEstimate.lossLimitedBandwidth))
                {
                    logger.warn("The estimator must be initialized before it can be used.");
                }
                if (numObservations <= config.minNumObservations)
                {
                    logger.warn("The estimator must receive enough loss statistics before it can be used.");
                }
            }

            return new Result(
                isValid(delayBasedEstimate) ? delayBasedEstimate : Bandwidth.INFINITY,
                LossBasedState.kDelayBasedEstimate);
        }
        return lossBasedResult.copy();
    }

    public void setAcknowledgedBitrate(Bandwidth acknowledgedBitrate)
    {
        if (isValid(acknowledgedBitrate))
        {
            this.acknowledgedBitrate = acknowledgedBitrate;
            calculateInstantLowerBound();
        }
        else
        {
            logger.warn("The acknowledged bitrate must be finite: " + acknowledgedBitrate);
        }
    }

    public void setMinMaxBitrate(Bandwidth minBitrate, Bandwidth maxBitrate)
    {
        if (isValid(minBitrate))
        {
            this.minBitrate = minBitrate;
            calculateInstantLowerBound();
        }
        else
        {
            logger.warn("The min bitrate must be finite: " + minBitrate);
        }

        if (isValid(maxBitrate))
        {
            this.maxBitrate = maxBitrate;
        }
        else
        {
            logger.warn("The max bitrate must be finite: " + maxBitrate);
        }
    }

    public void updateBandwidthEstimate(
        List<PacketResult> packetResults,
        Bandwidth delayBasedEstimate,
        boolean inAlr)
    {
        this.delayBasedEstimate = delayBasedEstimate;
        if (!isEnabled())
        {
            logger.warn("The estimator must be enabled before it can be used.");
            return;
        }

        if (packetResults.isEmpty())
        {
            logger.debug("The estimate cannot be updated without any loss statistics.");
            return;
        }

        if (!pushBackObservation(packetResults))
        {
            return;
        }

        if (!isValid(currentBestEstimate.lossLimitedBandwidth))
        {
            if (!isValid(delayBasedEstimate))
            {
                logger.warn("The delay based estimate must be finite: " + delayBasedEstimate + ".");
                return;
            }
            currentBestEstimate.lossLimitedBandwidth = delayBasedEstimate;
            lossBasedResult = new Result(delayBasedEstimate, LossBasedState.kDelayBasedEstimate);
        }

        ChannelParameters bestCandidate = currentBestEstimate;
        double objectiveMax = -Double.MAX_VALUE;
        for (ChannelParameters candidate : getCandidates(inAlr))
        {
            newtonsMethodUpdate(candidate);

            double candidateObjective = getObjective(candidate);
            if (candidateObjective > objectiveMax)
            {
                objectiveMax = candidateObjective;
                bestCandidate = candidate;
            }
        }

        if (bestCandidate.lossLimitedBandwidth.compareTo(currentBestEstimate.lossLimitedBandwidth) < 0)
        {
            lastTimeEstimateReduced = lastSendTimeMostRecentObservation;
        }

        // Do not increase the estimate if the average loss is greater than current
        // inherent loss.
        if (averageReportedLossRatio > bestCandidate.inherentLoss &&
            config.notIncreaseIfInherentLossLessThanAverageLoss &&
            currentBestEstimate.lossLimitedBandwidth.compareTo(bestCandidate.lossLimitedBandwidth) < 0)
        {
            bestCandidate.lossLimitedBandwidth = currentBestEstimate.lossLimitedBandwidth;
        }

        if (isInLossLimitedState())
        {
            // Bound the estimate increase if:
            // 1. The estimate has been increased for less than
            // `delayed_increase_window` ago, and
            // 2. The best candidate is greater than bandwidth_limit_in_current_window.
            if (InstantKt.isFinite(recoveringAfterLossTimestamp) &&
                recoveringAfterLossTimestamp.plus(config.delayedIncreaseWindow)
                    .compareTo(lastSendTimeMostRecentObservation) > 0 &&
                bestCandidate.lossLimitedBandwidth.compareTo(bandwidthLimitInCurrentWindow) > 0)
            {
                bestCandidate.lossLimitedBandwidth = bandwidthLimitInCurrentWindow;
            }

            boolean increasingWhenLossLimited =
                isEstimateIncreasingWhenLossLimited(
                    /* oldEstimate = */ currentBestEstimate.lossLimitedBandwidth,
                    /* newEstimate = */ bestCandidate.lossLimitedBandwidth);
            // Bound the best candidate by the acked bitrate.
            if (increasingWhenLossLimited && isValid(acknowledgedBitrate))
            {
                double rampupFactor;
                if (isValid(lastHoldInfo.rate) &&
                    acknowledgedBitrate.compareTo(lastHoldInfo.rate.times(config.bandwidthRampupHoldThreshold)) < 0)
                {
                    rampupFactor = config.bandwidthRampupUpperBoundFactorInHold;
                }
                else
                {
                    rampupFactor = config.bandwidthRampupUpperBoundFactor;
                }
                bestCandidate.lossLimitedBandwidth =
                    Bandwidth.max(
                        currentBestEstimate.lossLimitedBandwidth,
                        Bandwidth.min(
                            bestCandidate.lossLimitedBandwidth,
                            acknowledgedBitrate.times(rampupFactor)));
                // Increase current estimate by at least 1kbps to make sure that the state
                // will be switched to kIncreasing, thus padding is triggered.
                if (lossBasedResult.state == LossBasedState.kDecreasing &&
                    bestCandidate.lossLimitedBandwidth.equals(currentBestEstimate.lossLimitedBandwidth))
                {
                    bestCandidate.lossLimitedBandwidth =
                        currentBestEstimate.lossLimitedBandwidth.plus(Bandwidth.ofBps(1));
                }
            }
        }

        Bandwidth boundedBandwidthEstimate = Bandwidth.INFINITY;
        if (isValid(delayBasedEstimate))
        {
            boundedBandwidthEstimate =
                Bandwidth.max(
                    getInstantLowerBound(),
                    Bandwidth.min(
                        bestCandidate.lossLimitedBandwidth,
                        Bandwidth.min(
                            getInstantUpperBound(),
                            delayBasedEstimate)));
        }
        else
        {
            boundedBandwidthEstimate =
                Bandwidth.max(
                    getInstantLowerBound(),
                    Bandwidth.min(bestCandidate.lossLimitedBandwidth, getInstantUpperBound()));
        }
        if (config.boundBestCandidate &&
            boundedBandwidthEstimate.compareTo(bestCandidate.lossLimitedBandwidth) < 0)
        {
            // If network is lossy, cap the best estimate by the instant upper bound,
            // e.g. 450kbps if loss rate is 50%.
            // Otherwise, cap the estimate by the delay-based estimate or max_bitrate.
            currentBestEstimate.lossLimitedBandwidth = boundedBandwidthEstimate;
            currentBestEstimate.inherentLoss = 0.0;
        }
        else
        {
            currentBestEstimate = bestCandidate;
            if (config.lowerBoundByAckedRateFactor > 0.0)
            {
                currentBestEstimate.lossLimitedBandwidth =
                    Bandwidth.max(currentBestEstimate.lossLimitedBandwidth, getInstantLowerBound());
            }
        }

        if (lossBasedResult.state == LossBasedState.kDecreasing &&
            lastHoldInfo.timestamp.compareTo(lastSendTimeMostRecentObservation) > 0 &&
            boundedBandwidthEstimate.compareTo(delayBasedEstimate) < 0)
        {
            // Ensure that acked rate is the lower bound of HOLD rate
            if (config.lowerBoundByAckedRateFactor > 0.0)
            {
                lastHoldInfo.rate =
                    Bandwidth.max(getInstantLowerBound(), lastHoldInfo.rate);
            }
            // BWE is not allowed to increase above the HOLD rate. The purpose of
            // HOLD is to not immediately ramp up BWE to a rate that may cause loss.
            lossBasedResult.bandwidthEstimate =
                Bandwidth.min(lastHoldInfo.rate, boundedBandwidthEstimate);
            return;
        }

        if (isEstimateIncreasingWhenLossLimited(
                /* oldEstimate = */ lossBasedResult.bandwidthEstimate,
                /* newEstimate = */ boundedBandwidthEstimate) &&
            canKeepIncreasingState(boundedBandwidthEstimate) &&
            boundedBandwidthEstimate.compareTo(delayBasedEstimate) < 0 &&
            boundedBandwidthEstimate.compareTo(maxBitrate) < 0)
        {
            if (config.paddingDuration.compareTo(Duration.ZERO) > 0 &&
                boundedBandwidthEstimate.compareTo(lastPaddingInfo.paddingRate) > 0)
            {
                // Start a new padding duration.
                lastPaddingInfo.paddingRate = boundedBandwidthEstimate;
                lastPaddingInfo.paddingTimestamp = lastSendTimeMostRecentObservation;
            }
            lossBasedResult.state = config.paddingDuration.compareTo(Duration.ZERO) > 0
                ? LossBasedState.kIncreaseUsingPadding
                : LossBasedState.kIncreasing;
        }
        else if (boundedBandwidthEstimate.compareTo(delayBasedEstimate) < 0 &&
            boundedBandwidthEstimate.compareTo(maxBitrate) < 0)
        {
            if (lossBasedResult.state != LossBasedState.kDecreasing &&
                config.holdDurationFactor > 0)
            {
                final Bandwidth boundedBandwidthEstimateForLog = boundedBandwidthEstimate;
                logger.info(() ->
                    "Switch to HOLD. Bounded BWE: " + boundedBandwidthEstimateForLog +
                        ", duration: " + lastHoldInfo.duration);
                lastHoldInfo = new HoldInfo(
                    /* timestamp = */ lastSendTimeMostRecentObservation.plus(lastHoldInfo.duration),
                    /* duration = */ DurationKt.min(
                        kMaxHoldDuration,
                        DurationKt.times(lastHoldInfo.duration, config.holdDurationFactor)),
                    /* rate = */ boundedBandwidthEstimate);
            }
            lossBasedResult.state = LossBasedState.kDecreasing;
        }
        else
        {
            // Reset the HOLD info if delay based estimate works to avoid getting
            // stuck in low bitrate.
            lastHoldInfo = new HoldInfo(
                /* timestamp = */ Instant.MIN,
                /* duration = */ kInitHoldDuration,
                /* rate = */ Bandwidth.INFINITY);
            lossBasedResult.state = LossBasedState.kDelayBasedEstimate;
        }
        lossBasedResult.bandwidthEstimate = boundedBandwidthEstimate;

        if (isInLossLimitedState() &&
            (InstantKt.isInfinite(recoveringAfterLossTimestamp) ||
                recoveringAfterLossTimestamp.plus(config.delayedIncreaseWindow)
                    .compareTo(lastSendTimeMostRecentObservation) < 0))
        {
            bandwidthLimitInCurrentWindow =
                Bandwidth.max(
                    BweDefines.kCongestionControllerMinBitrate,
                    currentBestEstimate.lossLimitedBandwidth.times(config.maxIncreaseFactor));
            recoveringAfterLossTimestamp = lastSendTimeMostRecentObservation;
        }
    }

    public Bandwidth getMedianSendingRate()
    {
        List<Bandwidth> sendingRates = new ArrayList<>();
        for (Observation observation : observations)
        {
            if (!observation.isInitialized() ||
                !isValid(observation.sendingRate) ||
                observation.sendingRate.equals(Bandwidth.ZERO))
            {
                continue;
            }
            sendingRates.add(observation.sendingRate);
        }
        if (sendingRates.isEmpty())
        {
            return Bandwidth.ZERO;
        }
        Collections.sort(sendingRates);
        if (sendingRates.size() % 2 == 0)
        {
            return sendingRates.get(sendingRates.size() / 2 - 1)
                .plus(sendingRates.get(sendingRates.size() / 2))
                .div(2);
        }
        return sendingRates.get(sendingRates.size() / 2);
    }

    // For unit testing only
    public void setBandwidthEstimate(Bandwidth bandwidthEstimate)
    {
        if (isValid(bandwidthEstimate))
        {
            currentBestEstimate.lossLimitedBandwidth = bandwidthEstimate;
            lossBasedResult = new Result(bandwidthEstimate, LossBasedState.kDelayBasedEstimate);
        }
        else
        {
            logger.warn("The bandwidth estimate must be finite: " + bandwidthEstimate);
        }
    }

    private static class ChannelParameters
    {
        double inherentLoss = 0.0;
        Bandwidth lossLimitedBandwidth = Bandwidth.MINUS_INFINITY;

        ChannelParameters copy()
        {
            ChannelParameters c = new ChannelParameters();
            c.inherentLoss = inherentLoss;
            c.lossLimitedBandwidth = lossLimitedBandwidth;
            return c;
        }
    }

    /** These are the parameters set by field trial parameters in libwebrtc.  They are initialized to their
     * default values.
     */
    public static class Config
    {
        public boolean enabled = true;
        public double bandwidthRampupUpperBoundFactor = 1.5;
        public double bandwidthRampupUpperBoundFactorInHold = 1.2;
        public double bandwidthRampupHoldThreshold = 1.3;
        public double rampupAccelerationMaxFactor = 0.0;
        public Duration rampupAccelerationMaxoutTime = DurationKt.getSecs(60);
        public double[] candidateFactors = new double[] { 1.02, 1.0, 0.95 };
        public double higherBandwidthBiasFactor = 0.0002;
        public double higherLogBandwidthBiasFactor = 0.02;
        public double inherentLossLowerBound = 1.0e-3;
        public double lossThresholdOfHighBandwidthPreference = 0.2;
        public double bandwidthPreferenceSmoothingFactor = 0.002;
        public Bandwidth inherentLossUpperBoundBandwidthBalance = Bandwidth.ofKbps(100);
        public double inherentLossUpperBoundOffset = 0.05;
        public double initialInherentLossEstimate = 0.01;
        public int newtonIterations = 1;
        public double newtonStepSize = 0.75;
        public boolean appendAcknowledgedRateCandidate = true;
        public boolean appendDelayBasedEstimateCandidate = true;
        public boolean appendUpperBoundCandidateInAlr = false;
        public Duration observationDurationLowerBound = DurationKt.getMs(250);
        public int observationWindowSize = 15;
        public double sendingRateSmoothingFactor = 0.0;
        public double instantUpperBoundTemporalWeightFactor = 0.9;
        public Bandwidth instantUpperBoundBandwidthBalance = Bandwidth.ofKbps(100);
        public double instantUpperBoundLossOffset = 0.05;
        public double temporalWeightFactor = 0.9;
        public double bandwidthBackoffLowerBoundFactor = 1.0;
        public double maxIncreaseFactor = 1.3;
        public Duration delayedIncreaseWindow = DurationKt.getMs(300);
        public boolean notIncreaseIfInherentLossLessThanAverageLoss = true;
        public boolean notUseAckedRateInAlr = true;
        public boolean useInStartPhase = true;
        public int minNumObservations = 3;
        public double lowerBoundByAckedRateFactor = 1.0;
        public double holdDurationFactor = 2.0;
        public boolean useByteLossRate = true;
        public Duration paddingDuration = DurationKt.getSecs(2);
        public boolean boundBestCandidate = true;
        public double medianSendingRateFactor = 2.0;

        public Config copy()
        {
            Config c = new Config();
            c.enabled = enabled;
            c.bandwidthRampupUpperBoundFactor = bandwidthRampupUpperBoundFactor;
            c.bandwidthRampupUpperBoundFactorInHold = bandwidthRampupUpperBoundFactorInHold;
            c.bandwidthRampupHoldThreshold = bandwidthRampupHoldThreshold;
            c.rampupAccelerationMaxFactor = rampupAccelerationMaxFactor;
            c.rampupAccelerationMaxoutTime = rampupAccelerationMaxoutTime;
            c.candidateFactors = candidateFactors;
            c.higherBandwidthBiasFactor = higherBandwidthBiasFactor;
            c.higherLogBandwidthBiasFactor = higherLogBandwidthBiasFactor;
            c.inherentLossLowerBound = inherentLossLowerBound;
            c.lossThresholdOfHighBandwidthPreference = lossThresholdOfHighBandwidthPreference;
            c.bandwidthPreferenceSmoothingFactor = bandwidthPreferenceSmoothingFactor;
            c.inherentLossUpperBoundBandwidthBalance = inherentLossUpperBoundBandwidthBalance;
            c.inherentLossUpperBoundOffset = inherentLossUpperBoundOffset;
            c.initialInherentLossEstimate = initialInherentLossEstimate;
            c.newtonIterations = newtonIterations;
            c.newtonStepSize = newtonStepSize;
            c.appendAcknowledgedRateCandidate = appendAcknowledgedRateCandidate;
            c.appendDelayBasedEstimateCandidate = appendDelayBasedEstimateCandidate;
            c.appendUpperBoundCandidateInAlr = appendUpperBoundCandidateInAlr;
            c.observationDurationLowerBound = observationDurationLowerBound;
            c.observationWindowSize = observationWindowSize;
            c.sendingRateSmoothingFactor = sendingRateSmoothingFactor;
            c.instantUpperBoundTemporalWeightFactor = instantUpperBoundTemporalWeightFactor;
            c.instantUpperBoundBandwidthBalance = instantUpperBoundBandwidthBalance;
            c.instantUpperBoundLossOffset = instantUpperBoundLossOffset;
            c.temporalWeightFactor = temporalWeightFactor;
            c.bandwidthBackoffLowerBoundFactor = bandwidthBackoffLowerBoundFactor;
            c.maxIncreaseFactor = maxIncreaseFactor;
            c.delayedIncreaseWindow = delayedIncreaseWindow;
            c.notIncreaseIfInherentLossLessThanAverageLoss = notIncreaseIfInherentLossLessThanAverageLoss;
            c.notUseAckedRateInAlr = notUseAckedRateInAlr;
            c.useInStartPhase = useInStartPhase;
            c.minNumObservations = minNumObservations;
            c.lowerBoundByAckedRateFactor = lowerBoundByAckedRateFactor;
            c.holdDurationFactor = holdDurationFactor;
            c.useByteLossRate = useByteLossRate;
            c.paddingDuration = paddingDuration;
            c.boundBestCandidate = boundBestCandidate;
            c.medianSendingRateFactor = medianSendingRateFactor;
            return c;
        }

        public boolean isValid()
        {
            if (!enabled)
            {
                return false;
            }

            boolean valid = true;

            if (bandwidthRampupUpperBoundFactor <= 1.0)
            {
                logger.warn(
                    "The bandwidth rampup upper bound factor must be greater than 1: " +
                        bandwidthRampupUpperBoundFactor);
                valid = false;
            }
            if (bandwidthRampupUpperBoundFactorInHold <= 1.0)
            {
                logger.warn(
                    "The bandwidth rampup upper bound factor in hold must be greater than 1: " +
                        bandwidthRampupUpperBoundFactorInHold);
                valid = false;
            }
            if (bandwidthRampupHoldThreshold < 0.0)
            {
                logger.warn(
                    "The bandwidth rampup hold threshold must be non-negative.: " +
                        bandwidthRampupHoldThreshold);
                valid = false;
            }

            if (rampupAccelerationMaxFactor < 0.0)
            {
                logger.warn(
                    "The rampup acceleration max factor must be non-negative.: " + rampupAccelerationMaxFactor);
                valid = false;
            }
            if (rampupAccelerationMaxoutTime.compareTo(Duration.ZERO) <= 0)
            {
                logger.warn(
                    "The rampup acceleration maxout time must be above zero: " + rampupAccelerationMaxoutTime);
                valid = false;
            }
            for (double candidateFactor : candidateFactors)
            {
                if (candidateFactor <= 0.0)
                {
                    logger.warn("All candidate factors must be greater than zero: " + candidateFactor);
                    valid = false;
                }
            }

            // Ensure that the configuration allows generation of at least one candidate
            // other than the current estimate.
            boolean anyCandidateFactorOtherThanOne = false;
            for (double candidateFactor : candidateFactors)
            {
                if (candidateFactor != 1.0)
                {
                    anyCandidateFactorOtherThanOne = true;
                }
            }
            if (!appendAcknowledgedRateCandidate && !appendDelayBasedEstimateCandidate &&
                !anyCandidateFactorOtherThanOne)
            {
                logger.warn(
                    "The configuration does not allow generating candidates. Specify " +
                        "a candidate factor other than 1.0, allow the acknowledged rate " +
                        "to be a candidate, and/or allow the delay based estimate to be a " +
                        "candidate.");
                valid = false;
            }
            if (higherBandwidthBiasFactor < 0.0)
            {
                logger.warn(
                    "The higher bandwidth bias factor must be non-negative: " + higherBandwidthBiasFactor);
                valid = false;
            }
            if (inherentLossLowerBound < 0.0 || inherentLossLowerBound >= 1.0)
            {
                logger.warn("The inherent loss lower bound must be in [0, 1): " + inherentLossLowerBound);
                valid = false;
            }
            if (lossThresholdOfHighBandwidthPreference < 0.0 || lossThresholdOfHighBandwidthPreference >= 1.0)
            {
                logger.warn(
                    "The loss threshold of high bandwidth preference must be in [0, 1): " +
                        lossThresholdOfHighBandwidthPreference);
                valid = false;
            }
            if (bandwidthPreferenceSmoothingFactor <= 0.0 || bandwidthPreferenceSmoothingFactor > 1.0)
            {
                logger.warn(
                    "The bandwidth preference smoothing factor must be in (0, 1]: " +
                        bandwidthPreferenceSmoothingFactor);
                valid = false;
            }
            if (inherentLossUpperBoundBandwidthBalance.compareTo(Bandwidth.ZERO) <= 0)
            {
                logger.warn(
                    "The inherent loss upper bound bandwidth balance must be positive: " +
                        inherentLossUpperBoundBandwidthBalance);
                valid = false;
            }
            if (inherentLossUpperBoundOffset < inherentLossLowerBound || inherentLossUpperBoundOffset >= 1.0)
            {
                logger.warn(
                    "The inherent loss upper bound must be greater than or equal to the inherent loss " +
                        "lower bound, which is " + inherentLossLowerBound +
                        ", and less than 1: " + inherentLossUpperBoundOffset);
                valid = false;
            }
            if (initialInherentLossEstimate < 0.0 || initialInherentLossEstimate >= 1.0)
            {
                logger.warn("The initial inherent loss estimate must be in [0, 1): " + initialInherentLossEstimate);
                valid = false;
            }
            if (newtonIterations <= 0)
            {
                logger.warn("The number of Newton iterations must be positive: " + newtonIterations);
                valid = false;
            }
            if (newtonStepSize <= 0.0)
            {
                logger.warn("The Newton step size must be positive: " + newtonStepSize);
                valid = false;
            }
            if (observationDurationLowerBound.compareTo(Duration.ZERO) <= 0)
            {
                logger.warn(
                    "The observation duration lower bound must be positive: " + observationDurationLowerBound);
                valid = false;
            }
            if (observationWindowSize < 2)
            {
                logger.warn("The observation window size must be at least 2: " + observationWindowSize);
                valid = false;
            }
            if (sendingRateSmoothingFactor < 0.0 || sendingRateSmoothingFactor >= 1.0)
            {
                logger.warn("The sending rate smoothing factor must be in [0, 1): " + sendingRateSmoothingFactor);
                valid = false;
            }
            if (instantUpperBoundTemporalWeightFactor <= 0.0 || instantUpperBoundTemporalWeightFactor > 1.0)
            {
                logger.warn(
                    "The instant upper bound temporal weight factor must be in (0, 1]: " +
                        instantUpperBoundTemporalWeightFactor);
                valid = false;
            }
            if (instantUpperBoundBandwidthBalance.compareTo(Bandwidth.ZERO) <= 0)
            {
                logger.warn(
                    "The instant upper bound bandwidth balance must be positive: " +
                        instantUpperBoundBandwidthBalance);
                valid = false;
            }
            if (instantUpperBoundLossOffset < 0.0 || instantUpperBoundLossOffset >= 1.0)
            {
                logger.warn("The instant upper bound loss offset must be in [0, 1): " + instantUpperBoundLossOffset);
                valid = false;
            }
            if (temporalWeightFactor <= 0.0 || temporalWeightFactor > 1.0)
            {
                logger.warn("The temporal weight factor must be in (0, 1]: " + temporalWeightFactor);
                valid = false;
            }
            if (bandwidthBackoffLowerBoundFactor > 1.0)
            {
                logger.warn(
                    "The bandwidth backoff lower bound factor must not be greater than 1: " +
                        bandwidthBackoffLowerBoundFactor);
                valid = false;
            }
            if (maxIncreaseFactor <= 0.0)
            {
                logger.warn("The maximum increase factor must be positive: " + maxIncreaseFactor);
                valid = false;
            }
            if (delayedIncreaseWindow.compareTo(Duration.ZERO) <= 0)
            {
                logger.warn("The delayed increase window must be positive: " + delayedIncreaseWindow);
                valid = false;
            }
            if (minNumObservations <= 0)
            {
                logger.warn("The min number of observations must be positive: " + minNumObservations);
                valid = false;
            }
            if (lowerBoundByAckedRateFactor < 0.0)
            {
                logger.warn(
                    "The estimate lower bound by acknowledged rate factor must be non-negative: " +
                        lowerBoundByAckedRateFactor);
                valid = false;
            }

            return valid;
        }
    }

    private static class Derivatives
    {
        double first = 0.0;
        double second = 0.0;
    }

    private static class Observation
    {
        int numPackets = 0;
        int numLostPackets = 0;
        int numReceivedPackets = 0;
        Bandwidth sendingRate = Bandwidth.MINUS_INFINITY;
        DataSize size = DataSize.ZERO;
        DataSize lostSize = DataSize.ZERO;
        int id = -1;

        boolean isInitialized()
        {
            return id != -1;
        }
    }

    private static class PartialObservation
    {
        int numPackets = 0;
        final Map<Long, DataSize> lostPackets = new HashMap<>();
        DataSize size = DataSize.ZERO;
    }

    private static class PaddingInfo
    {
        Bandwidth paddingRate = Bandwidth.MINUS_INFINITY;
        Instant paddingTimestamp = Instant.MIN;
    }

    private static class HoldInfo
    {
        Instant timestamp;
        Duration duration;
        Bandwidth rate;

        HoldInfo(Instant timestamp, Duration duration, Bandwidth rate)
        {
            this.timestamp = timestamp;
            this.duration = duration;
            this.rate = rate;
        }
    }

    /** Returns `0.0` if not enough loss statistics have been received. */
    private void updateAverageReportedLossRatio()
    {
        averageReportedLossRatio = config.useByteLossRate
            ? calculateAverageReportedByteLossRatio()
            : calculateAverageReportedPacketLossRatio();
    }

    private double calculateAverageReportedPacketLossRatio()
    {
        if (numObservations <= 0)
        {
            return 0.0;
        }

        double numPackets = 0.0;
        double numLostPackets = 0.0;

        for (Observation observation : observations)
        {
            if (!observation.isInitialized())
            {
                continue;
            }

            double instantTemporalWeight =
                instantUpperBoundTemporalWeights[(numObservations - 1) - observation.id];
            numPackets += instantTemporalWeight * observation.numPackets;
            numLostPackets += instantTemporalWeight * observation.numLostPackets;
        }

        return numLostPackets / numPackets;
    }

    // Calculates the average loss ratio over the last `observation_window_size`
    // observations but skips the observation with min and max loss ratio in order
    // to filter out loss spikes.
    private double calculateAverageReportedByteLossRatio()
    {
        if (numObservations <= 0)
        {
            return 0.0;
        }

        DataSize totalBytes = DataSize.ZERO;
        DataSize lostBytes = DataSize.ZERO;
        double minLossRate = 1.0;
        double maxLossRate = 0.0;
        DataSize minLostBytes = DataSize.ZERO;
        DataSize maxLostBytes = DataSize.ZERO;
        DataSize minBytesReceived = DataSize.ZERO;
        DataSize maxBytesReceived = DataSize.ZERO;

        Bandwidth sendRateOfMaxLossObservation = Bandwidth.ZERO;
        for (Observation observation : observations)
        {
            if (!observation.isInitialized())
            {
                continue;
            }

            double instantTemporalWeight =
                instantUpperBoundTemporalWeights[(numObservations - 1) - observation.id];
            totalBytes = totalBytes.plus(observation.size.times(instantTemporalWeight));
            lostBytes = lostBytes.plus(observation.lostSize.times(instantTemporalWeight));

            double lossRate = !observation.size.equals(DataSize.ZERO)
                ? observation.lostSize.div(observation.size)
                : 0.0;
            if (numObservations > 3)
            {
                if (lossRate > maxLossRate)
                {
                    maxLossRate = lossRate;
                    maxLostBytes = observation.lostSize.times(instantTemporalWeight);
                    maxBytesReceived = observation.size.times(instantTemporalWeight);
                    sendRateOfMaxLossObservation = observation.sendingRate;
                }
                if (lossRate < minLossRate)
                {
                    minLossRate = lossRate;
                    minLostBytes = observation.lostSize.times(instantTemporalWeight);
                    minBytesReceived = observation.size.times(instantTemporalWeight);
                }
            }
        }
        if (getMedianSendingRate().times(config.medianSendingRateFactor)
            .compareTo(sendRateOfMaxLossObservation) <= 0)
        {
            // If the median sending rate is less than half of the sending rate of the
            // observation with max loss rate, i.e. we suddenly send a lot of data, then
            // the loss rate might not be due to a spike.
            return lostBytes.div(totalBytes);
        }

        if (totalBytes.equals(maxBytesReceived.plus(minBytesReceived)))
        {
            // It could happen if the observation window was 2.
            return lostBytes.div(totalBytes);
        }

        return lostBytes.minus(minLostBytes).minus(maxLostBytes)
            .div(totalBytes.minus(maxBytesReceived).minus(minBytesReceived));
    }

    private Bandwidth getCandidateBandwidthUpperBound()
    {
        Bandwidth candidateBandwidthUpperBound = maxBitrate;
        if (isInLossLimitedState() && isValid(bandwidthLimitInCurrentWindow))
        {
            candidateBandwidthUpperBound = bandwidthLimitInCurrentWindow;
        }

        if (acknowledgedBitrate == null)
        {
            return candidateBandwidthUpperBound;
        }

        if (config.rampupAccelerationMaxFactor > 0.0)
        {
            Duration timeSinceBandwidthReduced = DurationKt.min(
                config.rampupAccelerationMaxoutTime,
                DurationKt.max(
                    Duration.ZERO,
                    Duration.between(lastTimeEstimateReduced, lastSendTimeMostRecentObservation)));
            double rampupAcceleration = config.rampupAccelerationMaxFactor *
                DurationKt.div(timeSinceBandwidthReduced, config.rampupAccelerationMaxoutTime);

            candidateBandwidthUpperBound =
                candidateBandwidthUpperBound.plus(acknowledgedBitrate.times(rampupAcceleration));
        }

        return candidateBandwidthUpperBound;
    }

    private List<ChannelParameters> getCandidates(boolean inAlr)
    {
        ChannelParameters bestEstimate = currentBestEstimate.copy();
        List<Bandwidth> bandwidths = new ArrayList<>();
        for (double candidateFactor : config.candidateFactors)
        {
            bandwidths.add(currentBestEstimate.lossLimitedBandwidth.times(candidateFactor));
        }

        if (acknowledgedBitrate != null &&
            config.appendAcknowledgedRateCandidate)
        {
            if (!(config.notUseAckedRateInAlr && inAlr) ||
                (config.paddingDuration.compareTo(Duration.ZERO) > 0 &&
                    lastPaddingInfo.paddingTimestamp.plus(config.paddingDuration)
                        .compareTo(lastSendTimeMostRecentObservation) >= 0))
            {
                bandwidths.add(acknowledgedBitrate.times(config.bandwidthBackoffLowerBoundFactor));
            }
        }

        if (isValid(delayBasedEstimate) && config.appendDelayBasedEstimateCandidate)
        {
            if (delayBasedEstimate.compareTo(bestEstimate.lossLimitedBandwidth) > 0)
            {
                bandwidths.add(delayBasedEstimate);
            }
        }

        if (inAlr && config.appendUpperBoundCandidateInAlr &&
            bestEstimate.lossLimitedBandwidth.compareTo(getInstantUpperBound()) > 0)
        {
            bandwidths.add(getInstantUpperBound());
        }

        Bandwidth candidateBandwidthUpperBound = getCandidateBandwidthUpperBound();

        List<ChannelParameters> candidates = new ArrayList<>();
        for (int i = 0; i < bandwidths.size(); i++)
        {
            ChannelParameters candidate = bestEstimate.copy();
            candidate.lossLimitedBandwidth = Bandwidth.min(
                bandwidths.get(i),
                Bandwidth.max(bestEstimate.lossLimitedBandwidth, candidateBandwidthUpperBound));
            candidate.inherentLoss = getFeasibleInherentLoss(candidate);
            candidates.add(candidate);
        }
        if (candidates.size() != bandwidths.size())
        {
            throw new IllegalStateException("Check failed: candidates.size == bandwidths.size");
        }
        return candidates;
    }

    private Derivatives getDerivatives(ChannelParameters channelParameters)
    {
        Derivatives derivatives = new Derivatives();

        for (Observation observation : observations)
        {
            if (!observation.isInitialized())
            {
                continue;
            }

            double lossProbability = getLossProbability(
                channelParameters.inherentLoss,
                channelParameters.lossLimitedBandwidth,
                observation.sendingRate);

            double temporalWeight = temporalWeights[(numObservations - 1) - observation.id];

            if (config.useByteLossRate)
            {
                derivatives.first +=
                    temporalWeight *
                        (observation.lostSize.getKiloBytes() / lossProbability -
                            (observation.size.minus(observation.lostSize).getKiloBytes()) / (1.0 - lossProbability));
                derivatives.second -=
                    temporalWeight *
                        (observation.lostSize.getKiloBytes() / Math.pow(lossProbability, 2) +
                            observation.size.minus(observation.lostSize).getKiloBytes() /
                                Math.pow(1.0 - lossProbability, 2));
            }
            else
            {
                derivatives.first +=
                    temporalWeight *
                        ((observation.numLostPackets / lossProbability) -
                            (observation.numReceivedPackets / (1.0 - lossProbability)));
                derivatives.second -=
                    temporalWeight *
                        ((observation.numLostPackets / Math.pow(lossProbability, 2)) +
                            (observation.numReceivedPackets / Math.pow(1.0 - lossProbability, 2)));
            }
        }

        if (derivatives.second >= 0.0)
        {
            logger.error(
                "The second derivative is mathematically guaranteed " +
                    "to be negative but is " + derivatives.second + ".");
            derivatives.second = -1.0e6;
        }

        return derivatives;
    }

    private double getFeasibleInherentLoss(ChannelParameters channelParameters)
    {
        return Math.min(
            Math.max(channelParameters.inherentLoss, config.inherentLossLowerBound),
            getInherentLossUpperBound(channelParameters.lossLimitedBandwidth));
    }

    private double getInherentLossUpperBound(Bandwidth bandwidth)
    {
        if (bandwidth.equals(Bandwidth.ZERO))
        {
            return 1.0;
        }

        double inherentLossUpperBound =
            config.inherentLossUpperBoundOffset +
                config.inherentLossUpperBoundBandwidthBalance.div(bandwidth);

        return Math.min(inherentLossUpperBound, 1.0);
    }

    private double adjustBiasFactor(double lossRate, double biasFactor)
    {
        return biasFactor *
            (config.lossThresholdOfHighBandwidthPreference - lossRate) /
            (config.bandwidthPreferenceSmoothingFactor +
                Math.abs(config.lossThresholdOfHighBandwidthPreference - lossRate));
    }

    private double getHighBandwidthBias(Bandwidth bandwidth)
    {
        if (isValid(bandwidth))
        {
            return adjustBiasFactor(averageReportedLossRatio, config.higherBandwidthBiasFactor) *
                bandwidth.getKbps() +
                adjustBiasFactor(averageReportedLossRatio, config.higherLogBandwidthBiasFactor) *
                    Math.log(1.0 + bandwidth.getKbps());
        }
        return 0.0;
    }

    private double getObjective(ChannelParameters channelParameters)
    {
        double objective = 0.0;

        double highBandwidthBias = getHighBandwidthBias(channelParameters.lossLimitedBandwidth);

        for (Observation observation : observations)
        {
            if (!observation.isInitialized())
            {
                continue;
            }

            double lossProbability = getLossProbability(
                channelParameters.inherentLoss,
                channelParameters.lossLimitedBandwidth,
                observation.sendingRate);

            double temporalWeight = temporalWeights[(numObservations - 1) - observation.id];

            if (config.useByteLossRate)
            {
                objective +=
                    temporalWeight *
                        ((observation.lostSize.getKiloBytes() * Math.log(lossProbability)) +
                            (observation.size.minus(observation.lostSize).getKiloBytes() *
                                Math.log(1.0 - lossProbability)));
                objective +=
                    temporalWeight * highBandwidthBias * observation.size.getKiloBytes();
            }
            else
            {
                objective +=
                    temporalWeight *
                        ((observation.numLostPackets * Math.log(lossProbability)) +
                            (observation.numReceivedPackets * Math.log(1.0 - lossProbability)));
                objective +=
                    temporalWeight * highBandwidthBias * observation.numPackets;
            }
        }

        return objective;
    }

    private Bandwidth getSendingRate(Bandwidth instantaneousSendingRate)
    {
        if (numObservations <= 0)
        {
            return instantaneousSendingRate;
        }

        int mostRecentObservationIdx =
            (numObservations - 1) % config.observationWindowSize;
        Observation mostRecentObservation = observations.get(mostRecentObservationIdx);
        Bandwidth sendingRatePreviousObservation =
            mostRecentObservation.sendingRate;

        return sendingRatePreviousObservation.times(config.sendingRateSmoothingFactor)
            .plus(instantaneousSendingRate.times(1.0 - config.sendingRateSmoothingFactor));
    }

    private Bandwidth getInstantUpperBound()
    {
        return cachedInstantUpperBound != null ? cachedInstantUpperBound : maxBitrate;
    }

    private void calculateInstantUpperBound()
    {
        Bandwidth instantLimit = maxBitrate;
        if (averageReportedLossRatio > config.instantUpperBoundLossOffset)
        {
            instantLimit = config.instantUpperBoundBandwidthBalance.div(
                averageReportedLossRatio - config.instantUpperBoundLossOffset);
        }

        cachedInstantUpperBound = instantLimit;
    }

    private Bandwidth getInstantLowerBound()
    {
        return cachedInstantLowerBound != null ? cachedInstantLowerBound : Bandwidth.ZERO;
    }

    private void calculateInstantLowerBound()
    {
        Bandwidth instanceLowerBound = Bandwidth.ZERO;
        if (isValid(acknowledgedBitrate) &&
            config.lowerBoundByAckedRateFactor > 0.0)
        {
            instanceLowerBound = acknowledgedBitrate.times(config.lowerBoundByAckedRateFactor);
        }
        if (isValid(minBitrate))
        {
            instanceLowerBound = Bandwidth.max(instanceLowerBound, minBitrate);
        }
        cachedInstantLowerBound = instanceLowerBound;
    }

    private void calculateTemporalWeights()
    {
        for (int i = 0; i < config.observationWindowSize; i++)
        {
            temporalWeights[i] = Math.pow(config.temporalWeightFactor, i);
            instantUpperBoundTemporalWeights[i] = Math.pow(config.instantUpperBoundTemporalWeightFactor, i);
        }
    }

    private void newtonsMethodUpdate(ChannelParameters channelParameters)
    {
        if (numObservations <= 0)
        {
            return;
        }

        for (int i = 0; i < config.newtonIterations; i++)
        {
            Derivatives derivatives = getDerivatives(channelParameters);
            channelParameters.inherentLoss -=
                config.newtonStepSize * derivatives.first / derivatives.second;
            channelParameters.inherentLoss =
                getFeasibleInherentLoss(channelParameters);
        }
    }

    /** Returns false if no observation was created. */
    private boolean pushBackObservation(List<PacketResult> packetResults)
    {
        if (packetResults.isEmpty())
        {
            return false;
        }

        partialObservation.numPackets += packetResults.size();
        Instant lastSendTime = Instant.MIN;
        Instant firstSendTime = Instant.MAX;
        for (PacketResult packet : packetResults)
        {
            if (packet.isReceived())
            {
                partialObservation.lostPackets.remove(packet.sentPacket.sequenceNumber);
            }
            else
            {
                partialObservation.lostPackets.put(packet.sentPacket.sequenceNumber, packet.sentPacket.size);
            }
            partialObservation.size = partialObservation.size.plus(packet.sentPacket.size);
            lastSendTime = InstantKt.max(lastSendTime, packet.sentPacket.sendTime);
            firstSendTime = InstantKt.min(firstSendTime, packet.sentPacket.sendTime);
        }

        // This is the first packet report we have received.
        if (!isValid(lastSendTimeMostRecentObservation))
        {
            lastSendTimeMostRecentObservation = firstSendTime;
        }

        Duration observationDuration = Duration.between(lastSendTimeMostRecentObservation, lastSendTime);
        // Too small to be meaningful.
        // To consider: what if it is too long?, i.e. we did not receive any packets
        // for a long time, then all the packets we received are too old.
        if (observationDuration.compareTo(Duration.ZERO) <= 0 ||
            observationDuration.compareTo(config.observationDurationLowerBound) < 0)
        {
            return false;
        }

        lastSendTimeMostRecentObservation = lastSendTime;

        Observation observation = new Observation();
        observation.numPackets = partialObservation.numPackets;
        observation.numLostPackets = partialObservation.lostPackets.size();
        observation.numReceivedPackets =
            observation.numPackets - observation.numLostPackets;
        observation.sendingRate =
            getSendingRate(partialObservation.size.per(observationDuration));
        for (DataSize packetSize : partialObservation.lostPackets.values())
        {
            observation.lostSize = observation.lostSize.plus(packetSize);
        }
        observation.size = partialObservation.size;
        observation.id = numObservations++;
        observations.set(observation.id % config.observationWindowSize,
            observation);

        partialObservation = new PartialObservation();

        updateAverageReportedLossRatio();
        calculateInstantUpperBound();
        return true;
    }

    private boolean isEstimateIncreasingWhenLossLimited(Bandwidth oldEstimate, Bandwidth newEstimate)
    {
        return (oldEstimate.compareTo(newEstimate) < 0 ||
            (oldEstimate.equals(newEstimate) &&
                (lossBasedResult.state == LossBasedState.kIncreasing ||
                    lossBasedResult.state == LossBasedState.kIncreaseUsingPadding))) &&
            isInLossLimitedState();
    }

    private boolean isInLossLimitedState()
    {
        return lossBasedResult.state != LossBasedState.kDelayBasedEstimate;
    }

    private boolean canKeepIncreasingState(Bandwidth estimate)
    {
        if (config.paddingDuration.equals(Duration.ZERO) ||
            lossBasedResult.state != LossBasedState.kIncreaseUsingPadding)
        {
            return true;
        }

        // Keep using the kIncreaseUsingPadding if either the state has been
        // kIncreaseUsingPadding for less than kPaddingDuration or the estimate
        // increases.
        return lastPaddingInfo.paddingTimestamp.plus(config.paddingDuration)
            .compareTo(lastSendTimeMostRecentObservation) >= 0 ||
            lastPaddingInfo.paddingRate.compareTo(estimate) < 0;
    }

    private static final Config defaultConfig = new Config();

    static final Logger logger = new LoggerImpl(LossBasedBweV2.class.getName());
}
