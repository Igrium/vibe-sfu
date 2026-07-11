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
package org.jitsi.nlj.rtp.bandwidthestimation;

import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.Logger;
import org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator.RemoteBitrateEstimatorAbsSendTime;
import org.jitsi_modified.impl.neomedia.rtp.sendsidebandwidthestimation.SendSideBandwidthEstimation;

import java.time.Duration;
import java.time.Instant;

public class GoogleCcEstimator extends BandwidthEstimator
{
    private static final String algorithmName = "Google CC";

    @Override
    public String getAlgorithmName()
    {
        return algorithmName;
    }

    /* TODO: Use configuration service to set this default value. */
    private Bandwidth initBw = BandwidthEstimatorConfig.initBw;

    @Override
    public Bandwidth getInitBw()
    {
        return initBw;
    }

    @Override
    public void setInitBw(Bandwidth initBw)
    {
        this.initBw = initBw;
    }

    /* TODO: observable which sets the components' values if we're in initial state. */
    private Bandwidth minBw = BandwidthEstimatorConfig.minBw;

    @Override
    public Bandwidth getMinBw()
    {
        return minBw;
    }

    @Override
    public void setMinBw(Bandwidth newValue)
    {
        this.minBw = newValue;
        bitrateEstimatorAbsSendTime.setMinBitrate((int) newValue.getBps());
        sendSideBandwidthEstimation.setMinMaxBitrate((int) newValue.getBps(), (int) maxBw.getBps());
    }

    private Bandwidth maxBw = BandwidthEstimatorConfig.maxBw;

    @Override
    public Bandwidth getMaxBw()
    {
        return maxBw;
    }

    @Override
    public void setMaxBw(Bandwidth newValue)
    {
        this.maxBw = newValue;
        sendSideBandwidthEstimation.setMinMaxBitrate((int) minBw.getBps(), (int) newValue.getBps());
    }

    private final Logger logger;

    /**
     * Implements the delay-based part of Google CC.
     */
    private final RemoteBitrateEstimatorAbsSendTime bitrateEstimatorAbsSendTime;

    /**
     * Implements the loss-based part of Google CC.
     */
    private final SendSideBandwidthEstimation sendSideBandwidthEstimation;

    public GoogleCcEstimator(DiagnosticContext diagnosticContext, Logger parentLogger)
    {
        super(diagnosticContext);
        this.logger = parentLogger.createChildLogger(getClass().getName());

        this.bitrateEstimatorAbsSendTime = new RemoteBitrateEstimatorAbsSendTime(diagnosticContext, logger);
        bitrateEstimatorAbsSendTime.setMinBitrate((int) minBw.getBps());

        this.sendSideBandwidthEstimation =
            new SendSideBandwidthEstimation(diagnosticContext, (long) initBw.getBps(), logger);
        sendSideBandwidthEstimation.setMinMaxBitrate((int) minBw.getBps(), (int) maxBw.getBps());
    }

    @Override
    protected void doProcessPacketArrival(
        Instant now,
        Instant sendTime,
        Instant recvTime,
        int seq,
        DataSize size,
        byte ecn,
        boolean previouslyReportedLost
    )
    {
        if (sendTime != null && recvTime != null)
        {
            bitrateEstimatorAbsSendTime.incomingPacketInfo(
                now.toEpochMilli(),
                sendTime.toEpochMilli(),
                recvTime.toEpochMilli(),
                (int) size.getBytes()
            );
        }
        sendSideBandwidthEstimation.updateReceiverEstimate(bitrateEstimatorAbsSendTime.getLatestEstimate());
        sendSideBandwidthEstimation.reportPacketArrived(now.toEpochMilli(), previouslyReportedLost);
    }

    @Override
    protected void doProcessPacketLoss(Instant now, Instant sendTime, int seq)
    {
        sendSideBandwidthEstimation.reportPacketLost(now.toEpochMilli());
    }

    @Override
    protected void doFeedbackComplete(Instant now)
    {
        /* TODO: rate-limit how often we call updateEstimate? */
        sendSideBandwidthEstimation.updateEstimate(now.toEpochMilli());
        reportBandwidthEstimate(now, Bandwidth.ofBps(sendSideBandwidthEstimation.getLatestEstimate()));
    }

    @Override
    protected void doRttUpdate(Instant now, Duration newRtt)
    {
        bitrateEstimatorAbsSendTime.onRttUpdate(now.toEpochMilli(), newRtt.toMillis());
        sendSideBandwidthEstimation.onRttUpdate(newRtt);
    }

    @Override
    public Bandwidth getCurrentBw(Instant now)
    {
        return Bandwidth.ofBps(sendSideBandwidthEstimation.getLatestEstimate());
    }

    @Override
    public StatisticsSnapshot getStats(Instant now)
    {
        StatisticsSnapshot stats = new StatisticsSnapshot("GoogleCcEstimator", getCurrentBw(now));

        RemoteBitrateEstimatorAbsSendTime.Statistics delayStats = bitrateEstimatorAbsSendTime.getStatistics();
        if (delayStats != null)
        {
            stats.addNumber("delayBasedEstimatorOffset", delayStats.offset);
            stats.addNumber("delayBasedEstimatorThreshold", delayStats.threshold);
            stats.addNumber("delayBasedEstimatorHypothesis", delayStats.hypothesis.getValue());
        }
        stats.addNumber("latestDelayBasedEstimate", sendSideBandwidthEstimation.getLatestREMB());
        stats.addNumber("latestLossFraction", sendSideBandwidthEstimation.getLatestFractionLoss() / 256.0);

        SendSideBandwidthEstimation.Statistics sendSideStats = sendSideBandwidthEstimation.getStatistics();
        sendSideStats.update(now.toEpochMilli());
        stats.addNumber("lossDegradedMs", sendSideStats.getLossDegradedMs());
        stats.addNumber("lossFreeMs", sendSideStats.getLossFreeMs());
        stats.addNumber("lossLimitedMs", sendSideStats.getLossLimitedMs());

        return stats;
    }

    @Override
    public void reset()
    {
        initBw = BandwidthEstimatorConfig.initBw;
        minBw = BandwidthEstimatorConfig.minBw;
        maxBw = BandwidthEstimatorConfig.maxBw;

        bitrateEstimatorAbsSendTime.reset();
        sendSideBandwidthEstimation.reset((long) initBw.getBps());

        sendSideBandwidthEstimation.setMinMaxBitrate((int) minBw.getBps(), (int) maxBw.getBps());
    }

    /* Default config settings to use when the classic Google CC estimator engine is used. */
    // (Library change: plain Java constants with the upstream reference.conf defaults
    // (jmt.bwe.estimator.GoogleCc.*) instead of metaconfig/JitsiConfig.)
    public static final Duration defaultRateTrackerWindowSize = Duration.ofSeconds(5);
    public static final Duration defaultRateTrackerBucketSize = Duration.ofMillis(100);
    public static final Duration defaultInitialIgnoreBwePeriod = Duration.ofSeconds(10);
}
