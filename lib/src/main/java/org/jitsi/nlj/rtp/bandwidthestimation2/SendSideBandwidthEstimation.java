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
// This file uses WebRTC's naming style for enums and constants

package org.jitsi.nlj.rtp.bandwidthestimation2;

import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.utils.InstantKt;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.utils.logging2.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;

/** Send-side bandwidth estimation,
 * based on WebRTC modules/congestion_controller/goog_cc/send_side_bandwidth_estimation.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Field trial settings have been generally removed, set to their default settings.
 *
 * (Deviation: upstream declares LinkCapacityTracker and RttBasedBackoff as additional top-level
 * declarations in the same Kotlin file; ported here as static nested classes since Java allows
 * only one public top-level type per file.)
 */
public class SendSideBandwidthEstimation
{
    static class LinkCapacityTracker
    {
        private double capacityEstimateBps = 0.0;
        private Instant lastLinkCapcityUpdate = Instant.MIN;
        private Bandwidth lastDelayBasedEstimate = Bandwidth.INFINITY;

        void updateDelayBasedEstimate(Instant atTime, Bandwidth delayBasedEstimate)
        {
            if (delayBasedEstimate.compareTo(lastDelayBasedEstimate) < 0)
            {
                capacityEstimateBps = Math.min(capacityEstimateBps, (double) delayBasedEstimate.getBps());
                lastLinkCapcityUpdate = atTime;
            }
            lastDelayBasedEstimate = delayBasedEstimate;
        }

        void onStartingRate(Bandwidth startRate)
        {
            if (InstantKt.isInfinite(lastLinkCapcityUpdate))
            {
                capacityEstimateBps = (double) startRate.getBps();
            }
        }

        void onRateUpdate(Bandwidth acknowledged, Bandwidth target, Instant atTime)
        {
            if (acknowledged == null)
            {
                return;
            }
            Bandwidth acknowledgedTarget = Bandwidth.min(acknowledged, target);
            if (acknowledgedTarget.getBps() > capacityEstimateBps)
            {
                double alpha;
                if (InstantKt.isFinite(lastLinkCapcityUpdate) && InstantKt.isFinite(atTime))
                {
                    Duration delta = Duration.between(lastLinkCapcityUpdate, atTime);
                    alpha = Math.exp(-((double) delta.toNanos() / Duration.ofSeconds(10).toNanos()));
                }
                else
                {
                    alpha = 0.0;
                }
                capacityEstimateBps =
                    alpha * capacityEstimateBps * alpha + (1 - alpha) * acknowledgedTarget.getBps();
            }
            lastLinkCapcityUpdate = atTime;
        }

        void onRttBackoff(Bandwidth backoffRate, Instant atTime)
        {
            capacityEstimateBps = Math.min(capacityEstimateBps, (double) backoffRate.getBps());
            lastLinkCapcityUpdate = atTime;
        }

        Bandwidth estimate()
        {
            return Bandwidth.ofBps(capacityEstimateBps);
        }
    }

    static class RttBasedBackoff
    {
        final boolean disabled = false;
        final Duration configuredLimit = Duration.ofSeconds(3);
        final double dropFraction = 0.8;
        final Duration dropInterval = Duration.ofSeconds(1);
        final Bandwidth bandwidthFloor = Bandwidth.ofKbps(5);

        Duration rttLimit = configuredLimit;
        Instant lastPropagationRttUpdate = Instant.MAX;
        Duration lastPropagationRtt = Duration.ZERO;
        Instant lastPacketSent = Instant.MIN;

        void updatePropagationRtt(Instant atTime, Duration propagationRtt)
        {
            lastPropagationRttUpdate = atTime;
            lastPropagationRtt = propagationRtt;
        }

        boolean isRttAboveLimit()
        {
            return correctedRtt().compareTo(rttLimit) > 0;
        }

        private Duration correctedRtt()
        {
            // Avoid timeout when no packets are being sent.
            Duration timeoutCorrection = maxDuration(
                Duration.between(lastPropagationRttUpdate, lastPacketSent), Duration.ZERO);
            return timeoutCorrection.plus(lastPropagationRtt);
        }
    }

    private static final Duration kBweIncreaseInterval = Duration.ofMillis(1000);
    private static final Duration kBweDecreaseInterval = Duration.ofMillis(300);
    private static final Duration kStartPhase = Duration.ofMillis(2000);
    private static final Duration kBweConverganceTime = Duration.ofMillis(20000);
    private static final int kLimitNumPackets = 20;
    private static final Bandwidth kDefaultMaxBitrate = Bandwidth.ofBps(1000000000L);
    private static final Duration kLowBitrateLogPeriod = Duration.ofMillis(10000);
    private static final Duration kRtcEventLogPeriod = Duration.ofMillis(5000);

    // Expecting that RTCP feedback is sent uniformly within [0.5, 1.5]s intervals.
    private static final Duration kMaxRtcpFeedbackInterval = Duration.ofMillis(5000);

    private static final float kDefaultLowLossThreshold = 0.02f;
    private static final float kDefaultHighLossThreshold = 0.1f;
    private static final Bandwidth kDefaultBitrateThreshold = Bandwidth.ZERO;

    public static final boolean disableReceiverLimitCapsOnly = false;

    private final DiagnosticContext diagnosticContext;
    private static final TimeSeriesLogger timeSeriesLogger =
        TimeSeriesLogger.getTimeSeriesLogger(SendSideBandwidthEstimation.class);
    private final Logger logger;

    private final RttBasedBackoff rttBackoff = new RttBasedBackoff();
    private final LinkCapacityTracker linkCapacity = new LinkCapacityTracker();

    private final ArrayDeque<MinBitrateEntry> minBitrateHistory = new ArrayDeque<>();

    // incoming filters
    private long lostPacketsSinceLastLossUpdate = 0L;
    private long expectedPacketsSinceLastLossUpdate = 0L;

    private Bandwidth acknowledgedRate = null;
    private Bandwidth currentTarget = Bandwidth.ZERO;
    private Bandwidth lastLoggedTarget = Bandwidth.ZERO;
    private Bandwidth minBitrateConfigured = BweDefines.kCongestionControllerMinBitrate;
    private Bandwidth maxBitrateConfigured = kDefaultMaxBitrate;
    private Instant lastLowBitrateLog = Instant.MIN;

    private boolean hasDecreasedSinceLastFractionLoss = false;

    private Instant lastLossFeedback = Instant.MIN;
    private Instant lastLossPacketReport = Instant.MIN;
    // Q8 fraction loss (0..255). Upstream uses a UByte; represented here as an int in [0, 255].
    private int lastFractionLoss = 0;
    private int lastLoggedFractionLoss = 0;
    private Duration lastRoundTripTime = Duration.ZERO;

    // The max bitrate as set by the receiver in the call. This is typically
    // signalled using the REMB RTCP message and is used when we don't have any
    // send side delay based estimate.
    private Bandwidth receiverLimit = Bandwidth.INFINITY;
    private Bandwidth delayBasedLimit = Bandwidth.INFINITY;
    private Instant timeLastDecrease = Instant.MIN;
    private Instant firstReportTime = Instant.MIN;
    private int initiallyLostPackets = 0;
    private Bandwidth bitrateAt2Seconds = Bandwidth.ZERO;
    private UmaState umaUpdateState = UmaState.kNoUpdate;
    private UmaState umaRttState = UmaState.kNoUpdate;
    private final ArrayList<Boolean> rampupUmaStatsUpdated = new ArrayList<>(kUmaRampupMetrics.length);
    private Instant lastRtcEventLog = Instant.MIN;
    private final float lowLossThreshold = kDefaultLowLossThreshold;
    private final float highLossThreshold = kDefaultHighLossThreshold;
    private final Bandwidth bitrateThreshold = kDefaultBitrateThreshold;
    private LossBasedBweV2 lossBasedBandwidthEstimatorV2;
    private LossBasedState lossBasedState = LossBasedState.kDelayBasedEstimate;

    public SendSideBandwidthEstimation(Logger parentLogger, DiagnosticContext diagnosticContext)
    {
        this.diagnosticContext = diagnosticContext;
        this.logger = parentLogger.createChildLogger(SendSideBandwidthEstimation.class.getName());
        this.lossBasedBandwidthEstimatorV2 = new LossBasedBweV2();
        this.lossBasedBandwidthEstimatorV2.setMinMaxBitrate(minBitrateConfigured, maxBitrateConfigured);
    }

    public LossBasedBweV2 getLossBasedBandwidthEstimatorV2()
    {
        return lossBasedBandwidthEstimatorV2;
    }

    public void onRouteChange()
    {
        lostPacketsSinceLastLossUpdate = 0;
        expectedPacketsSinceLastLossUpdate = 0;
        currentTarget = Bandwidth.ZERO;
        minBitrateConfigured = BweDefines.kCongestionControllerMinBitrate;
        maxBitrateConfigured = kDefaultMaxBitrate;
        lastLowBitrateLog = Instant.MIN;
        hasDecreasedSinceLastFractionLoss = false;
        lastLossFeedback = Instant.MIN;
        lastLossPacketReport = Instant.MIN;
        lastFractionLoss = 0;
        lastLoggedFractionLoss = 0;
        lastRoundTripTime = Duration.ZERO;
        receiverLimit = Bandwidth.INFINITY;
        delayBasedLimit = Bandwidth.INFINITY;
        timeLastDecrease = Instant.MIN;
        firstReportTime = Instant.MIN;
        initiallyLostPackets = 0;
        bitrateAt2Seconds = Bandwidth.ZERO;
        umaUpdateState = UmaState.kNoUpdate;
        umaRttState = UmaState.kNoUpdate;
        lastRtcEventLog = Instant.MIN;
        if (lossBasedBandwidthEstimatorV2.useInStartPhase())
        {
            lossBasedBandwidthEstimatorV2 = new LossBasedBweV2();
        }
    }

    public Bandwidth targetRate()
    {
        Bandwidth target = currentTarget;
        if (!disableReceiverLimitCapsOnly)
        {
            target = Bandwidth.min(target, receiverLimit);
        }
        return Bandwidth.max(minBitrateConfigured, target);
    }

    public LossBasedState lossBasedState()
    {
        return lossBasedState;
    }

    // Return whether the current rtt is higher than the rtt limited configured in
    // RttBasedBackoff.
    public boolean isRttAboveLimit()
    {
        return rttBackoff.isRttAboveLimit();
    }

    public int fractionLoss()
    {
        return lastFractionLoss;
    }

    public Duration roundTripTime()
    {
        return lastRoundTripTime;
    }

    public Bandwidth getEstimatedLinkCapacity()
    {
        return linkCapacity.estimate();
    }

    // Call periodically to update estimate
    public void updateEstimate(Instant atTime)
    {
        if (rttBackoff.isRttAboveLimit())
        {
            if (Duration.between(timeLastDecrease, atTime).compareTo(rttBackoff.dropInterval) >= 0 &&
                currentTarget.compareTo(rttBackoff.bandwidthFloor) > 0)
            {
                timeLastDecrease = atTime;
                Bandwidth newBitrate =
                    Bandwidth.max(currentTarget.times(rttBackoff.dropFraction), rttBackoff.bandwidthFloor);
                linkCapacity.onRttBackoff(newBitrate, atTime);
                updateTargetBitrate(newBitrate, atTime);
                return;
            }
            // TODO(srte): This is likely redundant in most cases.
            applyTargetLimits(atTime);
            return;
        }

        // We trust the REMB and/or delay-based estimate during the first 2 seconds if
        // we haven't had any packet loss reported, to allow startup bitrate probing.
        if (lastFractionLoss == 0 && isInStartPhase(atTime) &&
            !lossBasedBandwidthEstimatorV2.readyToUseInStartPhase())
        {
            Bandwidth newBitrate = currentTarget;
            // TODO(srte): We should not allow the new_bitrate to be larger than the
            // receiver limit here.
            if (receiverLimit.isFinite())
            {
                newBitrate = Bandwidth.max(receiverLimit, newBitrate);
            }
            if (delayBasedLimit.isFinite())
            {
                newBitrate = Bandwidth.max(delayBasedLimit, newBitrate);
            }

            if (!newBitrate.equals(currentTarget))
            {
                minBitrateHistory.clear();
                minBitrateHistory.add(new MinBitrateEntry(atTime, currentTarget));
                updateTargetBitrate(newBitrate, atTime);
                return;
            }
        }
        updateMinHistory(atTime);
        if (InstantKt.isInfinite(lastLossPacketReport))
        {
            // No feedback received.
            // TODO(srte): This is likely redundant in most cases.
            applyTargetLimits(atTime);
            return;
        }
        if (lossBasedBandwidthEstimatorV2.isReady())
        {
            LossBasedBweV2.Result result = lossBasedBandwidthEstimatorV2.getLossBasedResult();
            lossBasedState = result.state;
            updateTargetBitrate(result.bandwidthEstimate, atTime);
            return;
        }

        Duration timeSinceLossPacketReport = Duration.between(lastLossPacketReport, atTime);
        if (timeSinceLossPacketReport.compareTo(scaleDuration(kMaxRtcpFeedbackInterval, 1.2)) < 0)
        {
            // We only care about loss above a given bitrate threshold.
            float loss = lastFractionLoss / 256f;
            // We only make decisions based on loss when the bitrate is above a
            // threshold. This is a crude way of handling loss which is uncorrelated
            // to congestion.
            if (currentTarget.compareTo(bitrateThreshold) < 0 || loss <= lowLossThreshold)
            {
                // Loss < 2%: Increase rate by 8% of the min bitrate in the last
                // kBweIncreaseInterval.
                // Note that by remembering the bitrate over the last second one can
                // rampup up one second faster than if only allowed to start ramping
                // at 8% per second rate now. E.g.:
                //   If sending a constant 100kbps it can rampup immediately to 108kbps
                //   whenever a receiver report is received with lower packet loss.
                //   If instead one would do: current_bitrate_ *= 1.08^(delta time),
                //   it would take over one second since the lower packet loss to achieve
                //   108kbps.
                Bandwidth newBitrate =
                    Bandwidth.ofBps(minBitrateHistory.getFirst().bitrate.getBps() * 1.08 + 0.5);

                // Add 1 kbps extra, just to make sure that we do not get stuck
                // (gives a little extra increase at low rates, negligible at higher
                // rates).
                newBitrate = newBitrate.plus(Bandwidth.ofBps(1000L));
                updateTargetBitrate(newBitrate, atTime);
                return;
            }
            else if (currentTarget.compareTo(bitrateThreshold) > 0)
            {
                if (loss <= highLossThreshold)
                {
                    // Loss between 2% - 10%: Do nothing.
                }
                else
                {
                    // Loss > 10%: Limit the rate decreases to once a kBweDecreaseInterval
                    // + rtt.
                    if (!hasDecreasedSinceLastFractionLoss &&
                        Duration.between(timeLastDecrease, atTime)
                            .compareTo(kBweDecreaseInterval.plus(lastRoundTripTime)) >= 0)
                    {
                        timeLastDecrease = atTime;

                        // Reduce rate:
                        //   newRate = rate * (1 - 0.5*lossRate);
                        //   where packetLoss = 256*lossRate;
                        Bandwidth newBitrate =
                            Bandwidth.ofBps(currentTarget.getBps() * (512 - lastFractionLoss) / 512.0);
                        hasDecreasedSinceLastFractionLoss = true;
                        updateTargetBitrate(newBitrate, atTime);
                        return;
                    }
                }
            }
        }
        // TODO(srte): This is likely redundant in most cases.
        applyTargetLimits(atTime);
    }

    public void onSentPacket(SentPacket sentPacket)
    {
        // Only feedback-triggering packets will be reported here.
        rttBackoff.lastPacketSent = sentPacket.sendTime;
    }

    public void updatePropagationRtt(Instant atTime, Duration propagationRtt)
    {
        rttBackoff.updatePropagationRtt(atTime, propagationRtt);
    }

    // Call when we receive a RTCP message with TMMBR or REMB.
    public void updateReceiverEstimate(Instant atTime, Bandwidth bandwidth)
    {
        // TODO(srte): Ensure caller passes PlusInfinity, not zero, to represent no
        // limitation.
        receiverLimit = bandwidth.equals(Bandwidth.ZERO) ? Bandwidth.INFINITY : bandwidth;
        applyTargetLimits(atTime);
    }

    // Call when a new delay-based estimate is available.
    public void updateDelayBasedEstimate(Instant atTime, Bandwidth bitrate)
    {
        linkCapacity.updateDelayBasedEstimate(atTime, bitrate);
        // TODO(srte): Ensure caller passes PlusInfinity, not zero, to represent no
        // limitation.
        delayBasedLimit = bitrate.equals(Bandwidth.ZERO) ? Bandwidth.INFINITY : bitrate;
        applyTargetLimits(atTime);
    }

    // Call when we receive a RTCP message with a ReceiveBlock.
    public void updatePacketsLost(long packetsLost, long numberOfPackets, Instant atTime)
    {
        lastLossFeedback = atTime;
        if (InstantKt.isInfinite(firstReportTime))
        {
            firstReportTime = atTime;
        }

        // Check sequence number diff and weight loss report
        if (numberOfPackets > 0)
        {
            long expected = expectedPacketsSinceLastLossUpdate + numberOfPackets;

            // Don't generate a loss rate until it can be based on enough packets.
            if (expected < kLimitNumPackets)
            {
                // Accumulate reports
                expectedPacketsSinceLastLossUpdate = expected;
                lostPacketsSinceLastLossUpdate = packetsLost;
                return;
            }

            hasDecreasedSinceLastFractionLoss = false;
            long lostQ8 = Math.max(lostPacketsSinceLastLossUpdate + packetsLost, 0L) << 8;
            lastFractionLoss = (int) Math.min(lostQ8 / expected, 255);

            // Reset accumulators
            lostPacketsSinceLastLossUpdate = 0;
            expectedPacketsSinceLastLossUpdate = 0;
            lastLossPacketReport = atTime;
            updateEstimate(atTime);
        }
    }

    // Call when we receive a RTCP message with a ReceiveBlock.
    public void updateRtt(Duration rtt, Instant atTime)
    {
        // Update RTT if we were able to compute an RTT based on this RTCP.
        // FlexFEC doesn't send RTCP SR, which means we won't be able to compute RTT.
        if (rtt.compareTo(Duration.ZERO) > 0)
        {
            lastRoundTripTime = rtt;
        }

        if (!isInStartPhase(atTime) && umaRttState == UmaState.kNoUpdate)
        {
            umaRttState = UmaState.kDone;
            /* TODO: histograms */
        }
    }

    public void setBitrates(Bandwidth sendBitrate, Bandwidth minBitrate, Bandwidth maxBitrate, Instant atTime)
    {
        setMinMaxBitrate(minBitrate, maxBitrate);
        if (sendBitrate != null)
        {
            linkCapacity.onStartingRate(sendBitrate);
            setSendBitrate(sendBitrate, atTime);
        }
    }

    public void setSendBitrate(Bandwidth bitrate, Instant atTime)
    {
        assert bitrate.compareTo(Bandwidth.ZERO) >= 0;
        // Reset to avoid being capped by the estimate.
        delayBasedLimit = Bandwidth.INFINITY;
        updateTargetBitrate(bitrate, atTime);
        minBitrateHistory.clear();
    }

    public void setMinMaxBitrate(Bandwidth minBitrate, Bandwidth maxBitrate)
    {
        minBitrateConfigured = Bandwidth.max(minBitrate, BweDefines.kCongestionControllerMinBitrate);
        if (maxBitrate.compareTo(Bandwidth.ZERO) > 0 && maxBitrate.isFinite())
        {
            maxBitrateConfigured = Bandwidth.max(minBitrateConfigured, maxBitrate);
        }
        else
        {
            maxBitrateConfigured = kDefaultMaxBitrate;
        }
        lossBasedBandwidthEstimatorV2.setMinMaxBitrate(minBitrateConfigured, maxBitrateConfigured);
    }

    public int getMinBitrate()
    {
        return (int) minBitrateConfigured.getBps();
    }

    public void setAcknowledgedRate(Bandwidth acknowledgedRate, Instant atTime)
    {
        this.acknowledgedRate = acknowledgedRate;
        if (acknowledgedRate == null)
        {
            return;
        }
        lossBasedBandwidthEstimatorV2.setAcknowledgedBitrate(acknowledgedRate);
    }

    public void updateLossBasedEstimator(
        TransportPacketsFeedback report,
        BandwidthUsage delayDetectorState,
        Bandwidth probeBitrate,
        boolean inAlr)
    {
        lossBasedBandwidthEstimatorV2.updateBandwidthEstimate(
            report.packetFeedbacks,
            delayBasedLimit,
            inAlr
        );
        updateEstimate(report.feedbackTime);
    }

    private boolean isInStartPhase(Instant atTime)
    {
        return InstantKt.isInfinite(firstReportTime) ||
            Duration.between(firstReportTime, atTime).compareTo(kStartPhase) <= 0;
    }

    private void updateUmaStatsPacketsLost(Instant atTime, int packetsLost)
    {
        Bandwidth bitrateKbps = Bandwidth.ofKbps((currentTarget.getBps() + 500) / 1000);
        for (int i = 0; i < kUmaRampupMetrics.length; i++)
        {
            if (!rampupUmaStatsUpdated.get(i) &&
                bitrateKbps.getKbps() >= kUmaRampupMetrics[i].bitrateKbps)
            {
                /* TODO: histograms */
                rampupUmaStatsUpdated.set(i, true);
            }
        }
        if (isInStartPhase(atTime))
        {
            initiallyLostPackets += packetsLost;
        }
        else if (umaUpdateState == UmaState.kNoUpdate)
        {
            umaUpdateState = UmaState.kFirstDone;
            bitrateAt2Seconds = bitrateKbps;
            /* TODO: histograms */
        }
        else if (umaUpdateState == UmaState.kFirstDone &&
            Duration.between(firstReportTime, atTime).compareTo(kBweConverganceTime) >= 0)
        {
            umaUpdateState = UmaState.kDone;
            /* TODO: histograms */
        }
    }

    // Updates history of min bitrates.
    // After this method returns min_bitrate_history_.front().second contains the
    // min bitrate used during last kBweIncreaseIntervalMs.
    private void updateMinHistory(Instant atTime)
    {
        // Remove old data points from history.
        // Since history precision is in ms, add one so it is able to increase
        // bitrate if it is off by as little as 0.5ms.
        while (!minBitrateHistory.isEmpty() &&
            Duration.between(minBitrateHistory.getFirst().time, atTime).plus(Duration.ofMillis(1))
                .compareTo(kBweIncreaseInterval) > 0)
        {
            minBitrateHistory.removeFirst();
        }

        // Typical minimum sliding-window algorithm: Pop values higher than current
        // bitrate before pushing it.
        while (!minBitrateHistory.isEmpty() &&
            currentTarget.compareTo(minBitrateHistory.getLast().bitrate) <= 0)
        {
            minBitrateHistory.removeLast();
        }

        minBitrateHistory.add(new MinBitrateEntry(atTime, currentTarget));
    }

    // Gets the upper limit for the target bitrate. This is the minimum of the
    // delay based limit, the receiver limit and the loss based controller limit.
    private Bandwidth getUpperLimit()
    {
        Bandwidth upperLimit = delayBasedLimit;
        if (disableReceiverLimitCapsOnly)
        {
            upperLimit = Bandwidth.min(upperLimit, receiverLimit);
        }
        return Bandwidth.min(upperLimit, maxBitrateConfigured);
    }

    // Prints a warning if `bitrate` if sufficiently long time has past since last
    // warning.
    private void maybeLogLowBitrateWarning(Bandwidth bitrate, Instant atTime)
    {
        if (Duration.between(lastLowBitrateLog, atTime).compareTo(kLowBitrateLogPeriod) > 0)
        {
            logger.warn("Estimated available bandwidth " + bitrate +
                " is below configured min bitrate " + minBitrateConfigured + ".");
            lastLowBitrateLog = atTime;
        }
    }

    // Stores an update to the event log if the loss rate has changed, the target
    // has changed, or sufficient time has passed since last stored event.
    private void maybeLogLossBasedEvent(Instant atTime)
    {
        if (!currentTarget.equals(lastLoggedTarget) ||
            lastFractionLoss != lastLoggedFractionLoss ||
            Duration.between(lastRtcEventLog, atTime).compareTo(kRtcEventLogPeriod) > 0)
        {
            if (timeSeriesLogger.isTraceEnabled())
            {
                timeSeriesLogger.trace(
                    diagnosticContext.makeTimeSeriesPoint("RtcEventBweUpdateLossBased", atTime)
                        .addField("currentTarget", currentTarget.getBps())
                        .addField("lastFractionLoss", lastFractionLoss)
                        .addField("expectedPacketsSinceLastLossUpdate", expectedPacketsSinceLastLossUpdate));
            }
            lastLoggedFractionLoss = lastFractionLoss;
            lastLoggedTarget = currentTarget;
            lastRtcEventLog = atTime;
        }
    }

    // Cap `bitrate` to [min_bitrate_configured_, max_bitrate_configured_] and
    // set `current_bitrate_` to the capped value and updates the event log.
    private void updateTargetBitrate(Bandwidth bitrate, Instant atTime)
    {
        Bandwidth newBitrate = Bandwidth.min(bitrate, getUpperLimit());
        if (newBitrate.compareTo(minBitrateConfigured) < 0)
        {
            maybeLogLowBitrateWarning(newBitrate, atTime);
        }
        currentTarget = newBitrate;
        maybeLogLossBasedEvent(atTime);
        linkCapacity.onRateUpdate(acknowledgedRate, currentTarget, atTime);
    }

    // Applies lower and upper bounds to the current target rate.
    // TODO(srte): This seems to be called even when limits haven't changed, that
    // should be cleaned up.
    private void applyTargetLimits(Instant atTime)
    {
        updateTargetBitrate(currentTarget, atTime);
    }

    private enum UmaState { kNoUpdate, kFirstDone, kDone }

    /** Upstream stores minBitrateHistory as ArrayDeque of Pair&lt;Instant, Bandwidth&gt;. */
    private static class MinBitrateEntry
    {
        final Instant time;
        final Bandwidth bitrate;

        MinBitrateEntry(Instant time, Bandwidth bitrate)
        {
            this.time = time;
            this.bitrate = bitrate;
        }
    }

    private static class UmaRampUpMetric
    {
        final String metricName;
        final int bitrateKbps;

        UmaRampUpMetric(String metricName, int bitrateKbps)
        {
            this.metricName = metricName;
            this.bitrateKbps = bitrateKbps;
        }
    }

    private static final UmaRampUpMetric[] kUmaRampupMetrics = new UmaRampUpMetric[] {
        new UmaRampUpMetric("WebRTC.BWE.RampUpTimeTo500kbpsInMs", 500),
        new UmaRampUpMetric("WebRTC.BWE.RampUpTimeTo1000kbpsInMs", 1000),
        new UmaRampUpMetric("WebRTC.BWE.RampUpTimeTo2000kbpsInMs", 2000)
    };

    private static Duration maxDuration(Duration a, Duration b)
    {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static Duration scaleDuration(Duration d, double factor)
    {
        return Duration.ofNanos((long) (d.toNanos() * factor));
    }
}
