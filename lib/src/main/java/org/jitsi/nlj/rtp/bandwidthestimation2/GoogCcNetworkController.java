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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.utils.DurationKt;
import org.jitsi.utils.InstantKt;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.utils.logging2.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;

/** Top-level Google CC Network Controller,
 * based on WebRTC modules/congestion_controller/goog_cc/goog_cc_network_control.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Field trial settings have been generally removed, set to their default settings,
 * except where needed by unit tests.
 */
public class GoogCcNetworkController implements NetworkControllerInterface
{
    // From RTCPSender video report interval.
    private static final Duration kLossUpdateInterval = Duration.ofMillis(1000);

    // Pacing-rate relative to our target send rate.
    // Multiplicative factor that is applied to the target bitrate to calculate
    // the number of bytes that can be transmitted per interval.
    // Increasing this factor will result in lower delays in cases of bitrate
    // overshoots from the encoder.
    private static final double kDefaultPaceMultiplier = 2.5;

    // If the probe result is far below the current throughput estimate
    // it's unlikely that the probe is accurate, so we don't want to drop too far.
    // However, if we actually are overusing, we want to drop to something slightly
    // below the current throughput estimate to drain the network queues.
    private static final double kProbeDropThroughputFraction = 0.85;

    private static final TimeSeriesLogger timeSeriesLogger =
        TimeSeriesLogger.getTimeSeriesLogger(GoogCcNetworkController.class);

    private final Logger logger;
    private final DiagnosticContext diagnosticContext;

    private final boolean packetFeedbackOnly;
    private final boolean safeResetOnRouteChange = false;
    private final boolean safeResetAcknowledgedRate = false;
    private final boolean useMinAllocatableAsLowerBound = true;
    private final boolean limitProbesLowerThanThroughputEstimate = true;
    private final CongestionWindowConfig rateControlSettings;

    private final ProbeController probeController;
    private final CongestionWindowPushbackController congestionWindowPushbackController;

    private final SendSideBandwidthEstimation bandwidthEstimation;
    private final AlrDetector alrDetector;
    private ProbeBitrateEstimator probeBitrateEstimator;
    private DelayBasedBwe delayBasedBwe;
    private AcknowledgedBitrateEstimatorInterface acknowledgedBitrateEstimator;

    private NetworkControllerConfig initialConfig;

    private Bandwidth minTargetRate = Bandwidth.ZERO;
    private Bandwidth minDataRate = Bandwidth.ZERO;
    private Bandwidth maxDataRate = Bandwidth.ZERO;
    private Bandwidth startingRate = null;

    private boolean firstPacketSent = false;

    /* Skipping NetworkStateEstimate */

    private Instant nextLossUpdate = Instant.MIN;
    private int lostPacketsSinceLastLossUpdate = 0;
    private int expectedPacketsSinceLastLossUpdate = 0;

    private final ArrayDeque<Long> feedbackMaxRtts = new ArrayDeque<>();

    private Bandwidth lastLossBasedTargetRate;
    private Bandwidth lastPushbackTargetRate;
    private Bandwidth lastStableTargetRate;
    private LossBasedState lastLossBasedState = LossBasedState.kDelayBasedEstimate;

    // (Deviation: upstream uses Kotlin's UByte? here; ported as int in [0, 255], never null
    // in practice -- upstream initializes it to 0u.)
    private int lastEstimatedFractionLoss = 0;
    private Duration lastEstimatedRoundTripTime = DurationKt.getMAX_DURATION();

    private double pacingFactor;
    private Bandwidth minTotalAllocatedBitrate;
    private Bandwidth maxPaddingRate;

    private boolean previouslyInAlr = false;
    private DataSize currentDataWindow = null;

    public GoogCcNetworkController(NetworkControllerConfig config, GoogCcConfig googCcConfig)
    {
        this.logger = config.parentLogger.createChildLogger(getClass().getName());
        this.diagnosticContext = config.diagnosticContext;

        this.packetFeedbackOnly = googCcConfig.feedbackOnly;
        this.rateControlSettings = googCcConfig.rateControlSettings;

        this.probeController = new ProbeController(logger, diagnosticContext);
        this.congestionWindowPushbackController = rateControlSettings.useCongestionWindowPushback()
            ? new CongestionWindowPushbackController()
            : null;

        this.bandwidthEstimation = new SendSideBandwidthEstimation(logger, diagnosticContext);
        this.alrDetector = new AlrDetector();
        this.probeBitrateEstimator = new ProbeBitrateEstimator(logger, diagnosticContext);
        this.delayBasedBwe = new DelayBasedBwe(logger, diagnosticContext);
        this.delayBasedBwe.setMinBitrate(BweDefines.kCongestionControllerMinBitrate);
        this.acknowledgedBitrateEstimator = AcknowledgedBitrateEstimatorInterface.create();

        this.initialConfig = config;

        this.lastLossBasedTargetRate = config.constraints.startingRate;
        this.lastPushbackTargetRate = lastLossBasedTargetRate;
        this.lastStableTargetRate = lastLossBasedTargetRate;

        this.pacingFactor = config.streamBasedConfig.pacingFactor != null
            ? config.streamBasedConfig.pacingFactor
            : kDefaultPaceMultiplier;
        this.minTotalAllocatedBitrate = config.streamBasedConfig.minTotalAllocatedBitrate != null
            ? config.streamBasedConfig.minTotalAllocatedBitrate
            : Bandwidth.ZERO;
        this.maxPaddingRate = config.streamBasedConfig.maxPaddingRate != null
            ? config.streamBasedConfig.maxPaddingRate
            : Bandwidth.ZERO;
    }

    @Override
    public NetworkControlUpdate onNetworkAvailability(NetworkAvailability msg)
    {
        NetworkControlUpdate update = new NetworkControlUpdate();
        update.probeClusterConfigs = probeController.onNetworkAvailability(msg);
        return update;
    }

    @Override
    public NetworkControlUpdate onNetworkRouteChange(NetworkRouteChange msg)
    {
        if (safeResetOnRouteChange)
        {
            Bandwidth estimatedBitrate;
            if (safeResetAcknowledgedRate)
            {
                estimatedBitrate = acknowledgedBitrateEstimator.bitrate();
                if (estimatedBitrate == null)
                {
                    estimatedBitrate = acknowledgedBitrateEstimator.peekRate();
                }
            }
            else
            {
                estimatedBitrate = bandwidthEstimation.targetRate();
            }
            if (estimatedBitrate != null)
            {
                if (msg.constraints.startingRate != null)
                {
                    msg.constraints.startingRate = Bandwidth.min(msg.constraints.startingRate, estimatedBitrate);
                }
                else
                {
                    msg.constraints.startingRate = estimatedBitrate;
                }
            }
        }

        acknowledgedBitrateEstimator = AcknowledgedBitrateEstimatorInterface.create();
        probeBitrateEstimator = new ProbeBitrateEstimator(logger, diagnosticContext);
        delayBasedBwe = new DelayBasedBwe(logger, diagnosticContext);

        bandwidthEstimation.onRouteChange();
        probeController.reset(msg.atTime);
        MutableNetworkControlUpdate update = new MutableNetworkControlUpdate(resetConstraints(msg.constraints));
        maybeTriggerOnNetworkChanged(update, msg.atTime);

        return update;
    }

    @Override
    public NetworkControlUpdate onProcessInterval(ProcessInterval msg)
    {
        MutableNetworkControlUpdate update = new MutableNetworkControlUpdate();
        if (initialConfig != null)
        {
            update.probeClusterConfigs = resetConstraints(initialConfig.constraints);
            update.pacerConfig = getPacingRates(msg.atTime);

            if (initialConfig.streamBasedConfig.requestsAlrProbing != null)
            {
                probeController.enablePeriodicAlrProbing(initialConfig.streamBasedConfig.requestsAlrProbing);
            }
            if (initialConfig.streamBasedConfig.enableRepeatedInitialProbing != null)
            {
                probeController.enableRepeatedInitialProbing(
                    initialConfig.streamBasedConfig.enableRepeatedInitialProbing);
            }
            Bandwidth totalBitrate = initialConfig.streamBasedConfig.maxTotalAllocatedBitrate;
            if (totalBitrate != null)
            {
                List<ProbeClusterConfig> probes =
                    probeController.onMaxTotalAllocatedBitrate(totalBitrate, msg.atTime);
                update.probeClusterConfigs.addAll(probes);
            }
            initialConfig = null;
        }
        if (congestionWindowPushbackController != null && msg.pacerQueue != null)
        {
            congestionWindowPushbackController.updatePacingQueue(Math.round(msg.pacerQueue.getBytes()));
        }
        bandwidthEstimation.updateEstimate(msg.atTime);
        Long startTimeMs = alrDetector.getApplicationLimitedRegionStartTime();
        probeController.setAlrStartTimeMs(startTimeMs);

        List<ProbeClusterConfig> probes = probeController.process(msg.atTime);
        update.probeClusterConfigs.addAll(probes);

        if (rateControlSettings.useCongestionWindow() &&
            !feedbackMaxRtts.isEmpty())
        {
            updateCongestionWindowSize();
        }
        if (congestionWindowPushbackController != null && currentDataWindow != null)
        {
            congestionWindowPushbackController.setDataWindow(currentDataWindow);
        }
        else
        {
            update.congestionWindow = currentDataWindow;
        }
        maybeTriggerOnNetworkChanged(update, msg.atTime);
        return update;
    }

    @Override
    public NetworkControlUpdate onRemoteBitrateReport(RemoteBitrateReport msg)
    {
        if (packetFeedbackOnly)
        {
            logger.error("Received REMB for packet feedback only GoogCC");
            return new NetworkControlUpdate();
        }
        bandwidthEstimation.updateReceiverEstimate(msg.receiveTime, msg.bandwidth);

        timeSeriesLogger.trace(() ->
            diagnosticContext.makeTimeSeriesPoint("REMB_BW", msg.receiveTime)
                .addField("REMB_kbps", msg.bandwidth.getKbps()));
        return new NetworkControlUpdate();
    }

    @Override
    public NetworkControlUpdate onRoundTripTimeUpdate(RoundTripTimeUpdate msg)
    {
        if (packetFeedbackOnly || msg.smoothed)
        {
            return new NetworkControlUpdate();
        }
        assert !msg.roundTripTime.equals(Duration.ZERO);
        delayBasedBwe.onRttUpdate(msg.roundTripTime);
        bandwidthEstimation.updateRtt(msg.roundTripTime, msg.receiveTime);
        return new NetworkControlUpdate();
    }

    @Override
    public NetworkControlUpdate onSentPacket(SentPacket sentPacket)
    {
        alrDetector.onBytesSent(Math.round(sentPacket.size.getBytes()), sentPacket.sendTime.toEpochMilli());
        acknowledgedBitrateEstimator.setAlr(alrDetector.getApplicationLimitedRegionStartTime() != null);

        if (!firstPacketSent)
        {
            firstPacketSent = true;
            // Initialize feedback time to send time to allow estimation of RTT until
            // first feedback is received.
            bandwidthEstimation.updatePropagationRtt(sentPacket.sendTime, Duration.ZERO);
        }
        bandwidthEstimation.onSentPacket(sentPacket);

        if (congestionWindowPushbackController != null)
        {
            congestionWindowPushbackController.updateOutstandingData(
                Math.round(sentPacket.dataInFlight.getBytes()));
            MutableNetworkControlUpdate update = new MutableNetworkControlUpdate();
            maybeTriggerOnNetworkChanged(update, sentPacket.sendTime);
            return update;
        }
        else
        {
            return new NetworkControlUpdate();
        }
    }

    @Override
    public NetworkControlUpdate onStreamsConfig(StreamsConfig msg)
    {
        MutableNetworkControlUpdate update = new MutableNetworkControlUpdate();
        if (msg.requestsAlrProbing != null)
        {
            probeController.enablePeriodicAlrProbing(msg.requestsAlrProbing);
        }
        if (msg.maxTotalAllocatedBitrate != null)
        {
            update.probeClusterConfigs =
                probeController.onMaxTotalAllocatedBitrate(msg.maxTotalAllocatedBitrate, msg.atTime);
        }

        boolean pacingChanged = false;
        if (msg.pacingFactor != null && msg.pacingFactor != pacingFactor)
        {
            pacingFactor = msg.pacingFactor;
            pacingChanged = true;
        }
        if (msg.minTotalAllocatedBitrate != null &&
            !minTotalAllocatedBitrate.equals(msg.minTotalAllocatedBitrate))
        {
            minTotalAllocatedBitrate = msg.minTotalAllocatedBitrate;
            pacingChanged = true;

            if (useMinAllocatableAsLowerBound)
            {
                clampConstraints();
                delayBasedBwe.setMinBitrate(minDataRate);
                bandwidthEstimation.setMinMaxBitrate(minDataRate, maxDataRate);
            }
        }
        if (msg.maxPaddingRate != null && !msg.maxPaddingRate.equals(maxPaddingRate))
        {
            maxPaddingRate = msg.maxPaddingRate;
            pacingChanged = true;
        }

        if (pacingChanged)
        {
            update.pacerConfig = getPacingRates(msg.atTime);
        }
        return update;
    }

    @Override
    public NetworkControlUpdate onTargetRateConstraints(TargetRateConstraints constraints)
    {
        MutableNetworkControlUpdate update = new MutableNetworkControlUpdate();
        update.probeClusterConfigs = resetConstraints(constraints);
        maybeTriggerOnNetworkChanged(update, constraints.atTime);
        return update;
    }

    private void clampConstraints()
    {
        // TODO(holmer): We should make sure the default bitrates are set to 10 kbps,
        // and that we don't try to set the min bitrate to 0 from any applications.
        // The congestion controller should allow a min bitrate of 0.
        minDataRate = Bandwidth.max(minTargetRate, BweDefines.kCongestionControllerMinBitrate);
        if (useMinAllocatableAsLowerBound)
        {
            minDataRate = Bandwidth.max(minDataRate, minTotalAllocatedBitrate);
        }
        if (maxDataRate.compareTo(minDataRate) < 0)
        {
            logger.warn("max bitrate " + maxDataRate + " smaller than min bitrate " + minDataRate);
            maxDataRate = minDataRate;
        }
        if (startingRate != null && startingRate.compareTo(minDataRate) < 0)
        {
            logger.warn("start bitrate " + startingRate + " smaller than min bitrate " + minDataRate);
            startingRate = minDataRate;
        }
    }

    private List<ProbeClusterConfig> resetConstraints(TargetRateConstraints newConstraints)
    {
        minTargetRate = newConstraints.minDataRate != null ? newConstraints.minDataRate : Bandwidth.ZERO;
        maxDataRate = newConstraints.maxDataRate != null ? newConstraints.maxDataRate : Bandwidth.INFINITY;
        startingRate = newConstraints.startingRate;
        clampConstraints();

        bandwidthEstimation.setBitrates(startingRate, minDataRate, maxDataRate, newConstraints.atTime);
        if (startingRate != null)
        {
            delayBasedBwe.setStartBitrate(startingRate);
        }
        delayBasedBwe.setMinBitrate(minDataRate);

        return probeController.setBitrates(
            minDataRate,
            startingRate != null ? startingRate : Bandwidth.ZERO,
            maxDataRate,
            newConstraints.atTime
        );
    }

    @Override
    public NetworkControlUpdate onTransportLossReport(TransportLossReport msg)
    {
        if (packetFeedbackOnly)
        {
            return new NetworkControlUpdate();
        }
        long totalPacketsDelta = msg.packetsReceivedDelta + msg.packetsLostDelta;
        bandwidthEstimation.updatePacketsLost(msg.packetsLostDelta, totalPacketsDelta, msg.receiveTime);
        return new NetworkControlUpdate();
    }

    private void updateCongestionWindowSize()
    {
        Duration minFeedbackMaxRtt = Duration.ofMillis(Collections.min(feedbackMaxRtts));

        DataSize kMinCwnd = DataSize.ofBytes(2 * 1500);
        Duration timeWindow = minFeedbackMaxRtt.plus(
            Duration.ofMillis(rateControlSettings.getCongestionWindowAdditionalTimeMs()));

        DataSize dataWindow = lastLossBasedTargetRate.times(timeWindow);
        if (currentDataWindow != null)
        {
            dataWindow = DataSize.max(kMinCwnd, dataWindow.plus(currentDataWindow).div(2.0));
        }
        else
        {
            dataWindow = DataSize.max(kMinCwnd, dataWindow);
        }
        currentDataWindow = dataWindow;
    }

    @Override
    public NetworkControlUpdate onTransportPacketsFeedback(TransportPacketsFeedback report)
    {
        if (report.packetFeedbacks.isEmpty())
        {
            // TODO(bugs.webrtc.org/10125): Design a better mechanism to safe-guard
            // against building very large network queues.
            return new NetworkControlUpdate();
        }
        if (congestionWindowPushbackController != null)
        {
            congestionWindowPushbackController.updateOutstandingData(
                Math.round(report.dataInFlight.getBytes()));
        }
        Duration maxFeedbackRtt = DurationKt.getMIN_DURATION();
        Duration minPropagationRtt = DurationKt.getMAX_DURATION();
        Instant maxRecvTime = Instant.MIN;

        List<PacketResult> feedbacks = report.receivedWithSendInfo();
        for (PacketResult feedback : feedbacks)
        {
            maxRecvTime = InstantKt.max(maxRecvTime, feedback.receiveTime);
        }
        for (PacketResult feedback : feedbacks)
        {
            Duration feedbackRtt = Duration.between(feedback.sentPacket.sendTime, report.feedbackTime);
            Duration minPendingTime = Duration.between(feedback.receiveTime, maxRecvTime);
            Duration propagationRtt = feedbackRtt.minus(minPendingTime);
            maxFeedbackRtt = DurationKt.max(maxFeedbackRtt, feedbackRtt);
            minPropagationRtt = DurationKt.min(minPropagationRtt, propagationRtt);
        }

        if (DurationKt.isFinite(maxFeedbackRtt))
        {
            feedbackMaxRtts.add(DurationKt.toRoundedMillis(maxFeedbackRtt));
            final int kMaxFeedbackRttWindow = 32;
            if (feedbackMaxRtts.size() > kMaxFeedbackRttWindow)
            {
                feedbackMaxRtts.removeFirst();
            }
            // TODO(srte): Use time since last unacknowledged packet.
            bandwidthEstimation.updatePropagationRtt(report.feedbackTime, minPropagationRtt);
        }
        if (packetFeedbackOnly)
        {
            if (!feedbackMaxRtts.isEmpty())
            {
                long sumRttMs = 0;
                for (long rttMs : feedbackMaxRtts)
                {
                    sumRttMs += rttMs;
                }
                long meanRttMs = sumRttMs / feedbackMaxRtts.size();
                if (delayBasedBwe != null)
                {
                    delayBasedBwe.onRttUpdate(Duration.ofMillis(meanRttMs));
                }
            }

            Duration feedbackMinRtt = DurationKt.getMAX_DURATION();
            for (PacketResult packetFeedback : feedbacks)
            {
                Duration pendingTime = Duration.between(packetFeedback.receiveTime, maxRecvTime);
                Duration rtt = Duration.between(packetFeedback.sentPacket.sendTime, report.feedbackTime)
                    .minus(pendingTime);
                // Value used for predicting NACK round trip time in FEC controller.
                feedbackMinRtt = DurationKt.min(rtt, feedbackMinRtt);
            }
            if (DurationKt.isFinite(feedbackMinRtt))
            {
                bandwidthEstimation.updateRtt(feedbackMinRtt, report.feedbackTime);
            }

            expectedPacketsSinceLastLossUpdate += report.packetsWithFeedback().size();
            for (PacketResult packetFeedback : report.packetsWithFeedback())
            {
                if (!packetFeedback.isReceived())
                {
                    lostPacketsSinceLastLossUpdate += 1;
                }
            }
            if (report.feedbackTime.isAfter(nextLossUpdate))
            {
                nextLossUpdate = report.feedbackTime.plus(kLossUpdateInterval);
                bandwidthEstimation.updatePacketsLost(
                    lostPacketsSinceLastLossUpdate,
                    expectedPacketsSinceLastLossUpdate,
                    report.feedbackTime
                );
                expectedPacketsSinceLastLossUpdate = 0;
                lostPacketsSinceLastLossUpdate = 0;
            }
        }
        Long alrStartTime = alrDetector.getApplicationLimitedRegionStartTime();

        if (previouslyInAlr && alrStartTime == null)
        {
            long nowMs = report.feedbackTime.toEpochMilli();
            acknowledgedBitrateEstimator.setAlrEndedTime(report.feedbackTime);
            probeController.setAlrEndedTimeMs(nowMs);
        }
        previouslyInAlr = alrStartTime != null;
        acknowledgedBitrateEstimator.incomingPacketFeedbackVector(report.sortedByReceiveTime());
        Bandwidth acknowledgedBitrate = acknowledgedBitrateEstimator.bitrate();
        bandwidthEstimation.setAcknowledgedRate(acknowledgedBitrate, report.feedbackTime);
        for (PacketResult feedback : report.sortedByReceiveTime())
        {
            if (feedback.sentPacket.pacingInfo.probeClusterId != PacedPacketInfo.kNotAProbe)
            {
                probeBitrateEstimator.handleProbeAndEstimateBitrate(feedback);
            }
        }

        /* Skipped network_estimator code */

        Bandwidth probeBitrate = probeBitrateEstimator.fetchAndResetLastEstimatedBitrate();

        /* Skipped network_estimator code */

        if (limitProbesLowerThanThroughputEstimate && probeBitrate != null && acknowledgedBitrate != null)
        {
            // Limit the backoff to something slightly below the acknowledged
            // bitrate. ("Slightly below" because we want to drain the queues
            // if we are actually overusing.)
            // The acknowledged bitrate shouldn't normally be higher than the delay
            // based estimate, but it could happen e.g. due to packet bursts or
            // encoder overshoot. We use std::min to ensure that a probe result
            // below the current BWE never causes an increase.
            Bandwidth limit =
                Bandwidth.min(delayBasedBwe.lastEstimate(), acknowledgedBitrate.times(kProbeDropThroughputFraction));
            probeBitrate = Bandwidth.max(probeBitrate, limit);
        }

        MutableNetworkControlUpdate update = new MutableNetworkControlUpdate();
        boolean recoveredFromOveruse = false;

        DelayBasedBwe.Result result = delayBasedBwe.incomingPacketFeedbackVector(
            report,
            acknowledgedBitrate,
            probeBitrate,
            alrStartTime != null
        );

        if (result.updated)
        {
            if (result.probe)
            {
                bandwidthEstimation.setSendBitrate(result.targetBitrate, report.feedbackTime);
            }
            // Since SetSendBitrate now resets the delay-based estimate, we have to
            // call UpdateDelayBasedEstimate after SetSendBitrate.
            bandwidthEstimation.updateDelayBasedEstimate(report.feedbackTime, result.targetBitrate);
        }
        bandwidthEstimation.updateLossBasedEstimator(
            report,
            result.delayDetectorState,
            probeBitrate,
            alrStartTime != null
        );
        if (result.updated)
        {
            // Update the estimate in the ProbeController, in case we want to probe.
            maybeTriggerOnNetworkChanged(update, report.feedbackTime);
        }

        recoveredFromOveruse = result.recoveredFromOveruse;

        if (recoveredFromOveruse)
        {
            probeController.setAlrStartTimeMs(alrStartTime);
            List<ProbeClusterConfig> probes = probeController.requestProbe(report.feedbackTime);
            update.probeClusterConfigs.addAll(probes);
        }

        // No valid RTT could be because send-side BWE isn't used, in which case
        // we don't try to limit the outstanding packets.
        if (rateControlSettings.useCongestionWindow() && DurationKt.isFinite(maxFeedbackRtt))
        {
            updateCongestionWindowSize();
        }
        if (congestionWindowPushbackController != null && currentDataWindow != null)
        {
            congestionWindowPushbackController.setDataWindow(currentDataWindow);
        }
        else
        {
            update.congestionWindow = currentDataWindow;
        }

        return update;
    }

    public NetworkControlUpdate getNetworkState(Instant atTime)
    {
        MutableNetworkControlUpdate update = new MutableNetworkControlUpdate();
        TargetTransferRate targetRate = new TargetTransferRate();
        targetRate.networkEstimate.atTime = atTime;
        targetRate.networkEstimate.lossRateRatio = lastEstimatedFractionLoss / 255.0f;
        targetRate.networkEstimate.roundTripTime = lastEstimatedRoundTripTime;
        targetRate.networkEstimate.bwePeriod = delayBasedBwe.getExpectedBwePeriod();

        targetRate.atTime = atTime;
        if (rateControlSettings.useCongestionWindowDropFrameOnly())
        {
            targetRate.targetRate = lastLossBasedTargetRate;
        }
        else
        {
            targetRate.targetRate = lastPushbackTargetRate;
        }

        targetRate.targetRate = lastPushbackTargetRate;
        targetRate.stableTargetRate = bandwidthEstimation.getEstimatedLinkCapacity();
        update.targetRate = targetRate;
        update.pacerConfig = getPacingRates(atTime);
        update.congestionWindow = currentDataWindow;
        return update;
    }

    private void maybeTriggerOnNetworkChanged(MutableNetworkControlUpdate update, Instant atTime)
    {
        int fractionLoss = bandwidthEstimation.fractionLoss();
        Duration roundTripTime = bandwidthEstimation.roundTripTime();
        Bandwidth lossBasedTargetRate = bandwidthEstimation.targetRate();
        LossBasedState lossBasedState = bandwidthEstimation.lossBasedState();
        Bandwidth pushbackTargetRate = lossBasedTargetRate;

        /* TODO: plotting */

        double cwndReduceRatio = 0.0;
        if (congestionWindowPushbackController != null)
        {
            int pushbackRate = congestionWindowPushbackController.updateTargetBitrate(
                (int) lossBasedTargetRate.getBps());
            pushbackRate = Math.max(bandwidthEstimation.getMinBitrate(), pushbackRate);
            pushbackTargetRate = Bandwidth.ofBps(pushbackRate);
            if (rateControlSettings.useCongestionWindowDropFrameOnly())
            {
                cwndReduceRatio = (double) (lossBasedTargetRate.getBps() - pushbackTargetRate.getBps())
                    / lossBasedTargetRate.getBps();
            }
        }
        Bandwidth stableTargetRate = bandwidthEstimation.getEstimatedLinkCapacity();
        stableTargetRate = Bandwidth.min(stableTargetRate, pushbackTargetRate);

        if (!lossBasedTargetRate.equals(lastLossBasedTargetRate) ||
            lossBasedState != lastLossBasedState ||
            fractionLoss != lastEstimatedFractionLoss ||
            !roundTripTime.equals(lastEstimatedRoundTripTime) ||
            !pushbackTargetRate.equals(lastPushbackTargetRate) ||
            !stableTargetRate.equals(lastStableTargetRate))
        {
            lastLossBasedTargetRate = lossBasedTargetRate;
            lastPushbackTargetRate = pushbackTargetRate;
            lastEstimatedFractionLoss = fractionLoss;
            lastEstimatedRoundTripTime = roundTripTime;
            lastStableTargetRate = stableTargetRate;
            lastLossBasedState = lossBasedState;

            alrDetector.setEstimatedBitrate((int) lossBasedTargetRate.getBps());

            Duration bwePeriod = delayBasedBwe.getExpectedBwePeriod();

            TargetTransferRate targetRateMsg = new TargetTransferRate();
            targetRateMsg.atTime = atTime;
            if (rateControlSettings.useCongestionWindowDropFrameOnly())
            {
                targetRateMsg.targetRate = lossBasedTargetRate;
                targetRateMsg.cwndReduceRatio = cwndReduceRatio;
            }
            else
            {
                targetRateMsg.targetRate = pushbackTargetRate;
            }
            targetRateMsg.stableTargetRate = stableTargetRate;
            targetRateMsg.networkEstimate.atTime = atTime;
            targetRateMsg.networkEstimate.roundTripTime = roundTripTime;
            targetRateMsg.networkEstimate.lossRateRatio = fractionLoss / 255.0f;
            targetRateMsg.networkEstimate.bwePeriod = bwePeriod;

            update.targetRate = targetRateMsg;

            List<ProbeClusterConfig> probes = probeController.setEstimatedBitrate(
                lossBasedTargetRate,
                getBandwidthLimitedCause(
                    bandwidthEstimation.lossBasedState(),
                    bandwidthEstimation.isRttAboveLimit(),
                    delayBasedBwe.lastState()
                ),
                atTime
            );
            update.probeClusterConfigs.addAll(probes);
            update.pacerConfig = getPacingRates(atTime);
            logger.debug(() ->
                "bwe " + atTime + ": pushback_target_bps=" + lastPushbackTargetRate.getBps()
                    + " estimate_bps=" + lossBasedTargetRate.getBps());
        }
    }

    private PacerConfig getPacingRates(Instant atTime)
    {
        // Pacing rate is based on target rate before congestion window pushback,
        // because we don't want to build queues in the pacer when pushback occurs.
        Bandwidth pacingRate =
            Bandwidth.max(minTotalAllocatedBitrate, lastLossBasedTargetRate).times(pacingFactor);
        Bandwidth paddingRate;
        if (lastLossBasedState == LossBasedState.kIncreaseUsingPadding)
        {
            paddingRate = Bandwidth.max(maxPaddingRate, lastLossBasedTargetRate);
        }
        else
        {
            paddingRate = maxPaddingRate;
        }
        paddingRate = Bandwidth.min(paddingRate, lastPushbackTargetRate);
        PacerConfig msg = new PacerConfig();
        msg.atTime = atTime;
        msg.timeWindow = Duration.ofSeconds(1);
        msg.dataWindow = pacingRate.times(msg.timeWindow);
        msg.padWindow = paddingRate.times(msg.timeWindow);
        return msg;
    }

    /** Jitsi local addition.
     * Fields based on WebRTC modules/congestion_controller/goog_cc/test/goog_cc_printer.{cc,h}
     */
    public static class StatisticsSnapshot
    {
        public final Instant time;
        public final Duration rtt;
        public final Bandwidth target;
        public final Bandwidth stableTarget;
        public final Bandwidth pacing;
        public final Bandwidth padding;
        public final DataSize window;
        public final AimdRateControl.RateControlState rateControlState;
        public final Bandwidth stableEstimate;
        public final double trendline;
        public final double trendlineModifiedOffset;
        public final double trendlineOffsetThreshold;
        public final Bandwidth acknowledgedRate;
        /* Skipped, based on NetworkStateEstimate
         * estCapacity
         * estCapacityDev
         * estCapacityMin
         * estCrossDelay
         * estSpikeDelay
         * estPreBuffer
         * estPostBuffer
         * estPropagation
         */

        public final float lossRatio;

        /* Fields where data from LossBasedBweV1 are printed, even though LossBasedBweV2 is the default.
         TODO: print state out of LossBasedBweV2.
        val lossAverage: Double,
        val lossAverageMax: Double,
        val lossThresInc: Double,
        val lossThresDec: Double,
        val lossBasedRate: Bandwidth,
        val lossAckRate: Bandwidth,
         */
        /* SendSideBandwidthEstimator populates itself from LossBasedBwe's estimate. */
        public final Bandwidth sendSideTarget;
        public final LossBasedState lossBasedState;
        public final DataSize dataWindow;
        public final Bandwidth pushbackTarget;
        /* Additions to the fields from goog_cc_printer */
        public final boolean inAlr;

        public StatisticsSnapshot(
            Instant time,
            Duration rtt,
            Bandwidth target,
            Bandwidth stableTarget,
            Bandwidth pacing,
            Bandwidth padding,
            DataSize window,
            AimdRateControl.RateControlState rateControlState,
            Bandwidth stableEstimate,
            double trendline,
            double trendlineModifiedOffset,
            double trendlineOffsetThreshold,
            Bandwidth acknowledgedRate,
            float lossRatio,
            Bandwidth sendSideTarget,
            LossBasedState lossBasedState,
            DataSize dataWindow,
            Bandwidth pushbackTarget,
            boolean inAlr)
        {
            this.time = time;
            this.rtt = rtt;
            this.target = target;
            this.stableTarget = stableTarget;
            this.pacing = pacing;
            this.padding = padding;
            this.window = window;
            this.rateControlState = rateControlState;
            this.stableEstimate = stableEstimate;
            this.trendline = trendline;
            this.trendlineModifiedOffset = trendlineModifiedOffset;
            this.trendlineOffsetThreshold = trendlineOffsetThreshold;
            this.acknowledgedRate = acknowledgedRate;
            this.lossRatio = lossRatio;
            this.sendSideTarget = sendSideTarget;
            this.lossBasedState = lossBasedState;
            this.dataWindow = dataWindow;
            this.pushbackTarget = pushbackTarget;
            this.inAlr = inAlr;
        }

        public ObjectNode toJson()
        {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("time", time.toEpochMilli());
            node.put("rtt", DurationKt.toDouble(rtt));
            node.put("target", target.getBps());
            node.put("stable_target", stableTarget.getBps());
            node.put("pacing", pacing != null ? (double) pacing.getBps() : Double.NaN);
            node.put("padding", padding != null ? (double) padding.getBps() : Double.NaN);
            node.put("window", window.getBytes());
            node.put("rate_control_state", rateControlState.name());
            node.put("stable_estimate", stableEstimate != null ? (double) stableEstimate.getBps() : Double.NaN);
            node.put("trendline", trendline);
            node.put("trendline_modified_offset", trendlineModifiedOffset);
            node.put("trendline_modified_threshold", trendlineOffsetThreshold);
            node.put("acknowledged_rate",
                acknowledgedRate != null ? (double) acknowledgedRate.getBps() : Double.NaN);
            node.put("loss_ratio", lossRatio);
            node.put("send_side_target", sendSideTarget.getBps());
            node.put("last_loss_based_state", lossBasedState.name());
            node.put("data_window", dataWindow != null ? dataWindow.getBytes() : Double.NaN);
            node.put("pushback_target", pushbackTarget.getBps());
            node.put("in_alr", inAlr);
            return node;
        }

        public void addToTimeSeriesPoint(DiagnosticContext.TimeSeriesPoint point)
        {
            point.addField("rtt", DurationKt.toDouble(rtt));
            point.addField("target", target.getBps());
            point.addField("stable_target", stableTarget.getBps());
            point.addField("pacing", pacing != null ? pacing.getBps() : Double.NaN);
            point.addField("padding", padding != null ? padding.getBps() : Double.NaN);
            point.addField("window", window.getBytes());
            point.addField("rate_control_state", rateControlState.name());
            point.addField("stable_estimate", stableEstimate != null ? stableEstimate.getBps() : Double.NaN);
            point.addField("trendline", trendline);
            point.addField("trendline_modified_offset", trendlineModifiedOffset);
            point.addField("trendline_modified_threshold", trendlineOffsetThreshold);
            point.addField("acknowledged_rate", acknowledgedRate != null ? acknowledgedRate.getBps() : Double.NaN);
            point.addField("loss_ratio", lossRatio);
            point.addField("send_side_target", sendSideTarget.getBps());
            point.addField("last_loss_based_state", lossBasedState.name());
            point.addField("data_window", dataWindow != null ? dataWindow.getBytes() : Double.NaN);
            point.addField("pushback_target", pushbackTarget.getBps());
            point.addField("in_alr", inAlr);
        }
    }

    private TrendlineEstimator trend()
    {
        return (TrendlineEstimator) delayBasedBwe.delayDetector;
    }

    public StatisticsSnapshot getStatistics(Instant now)
    {
        NetworkControlUpdate stateUpdate = getNetworkState(now);
        TargetTransferRate target = stateUpdate.targetRate;
        PacerConfig pacing = stateUpdate.pacerConfig;
        DataSize congestionWindow =
            stateUpdate.congestionWindow != null ? stateUpdate.congestionWindow : DataSize.INFINITY;
        return new StatisticsSnapshot(
            target.atTime,
            target.networkEstimate.roundTripTime,
            target.targetRate,
            target.stableTargetRate,
            pacing != null ? pacing.dataRate() : null,
            pacing != null ? pacing.padRate() : null,
            congestionWindow,
            delayBasedBwe.rateControl.getRateControlState(),
            delayBasedBwe.rateControl.linkCapacity.getEstimate(),
            trend().getPrevTrend(),
            trend().getPrevModifiedTrend(),
            trend().getThreshold(),
            acknowledgedBitrateEstimator.bitrate(),
            target.networkEstimate.lossRateRatio,
            bandwidthEstimation.targetRate(),
            lastLossBasedState,
            currentDataWindow,
            lastPushbackTargetRate,
            previouslyInAlr
        );
    }

    private static BandwidthLimitedCause getBandwidthLimitedCause(
        LossBasedState lossBasedState,
        boolean isRttAboveLimit,
        BandwidthUsage bandwidthUsage)
    {
        if (bandwidthUsage == BandwidthUsage.kBwOverusing ||
            bandwidthUsage == BandwidthUsage.kBwUnderusing)
        {
            return BandwidthLimitedCause.kDelayBasedLimitedDelayIncreased;
        }
        else if (isRttAboveLimit)
        {
            return BandwidthLimitedCause.kRttBasedBackOffHighRtt;
        }

        switch (lossBasedState)
        {
        case kDecreasing:
            // Probes may not be sent in this state.
            return BandwidthLimitedCause.kLossLimitedBwe;
        case kIncreaseUsingPadding:
            // Probes may not be sent in this state.
            return BandwidthLimitedCause.kLossLimitedBwe;
        case kIncreasing:
            // Probes may be sent in this state.
            return BandwidthLimitedCause.kLossLimitedBweIncreasing;
        case kDelayBasedEstimate:
        default:
            return BandwidthLimitedCause.kDelayBasedLimited;
        }
    }
}
