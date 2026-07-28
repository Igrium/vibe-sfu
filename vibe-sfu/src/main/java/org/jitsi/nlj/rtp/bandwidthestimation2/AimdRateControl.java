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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.utils.DurationKt;
import org.jitsi.utils.InstantKt;

import java.time.Duration;
import java.time.Instant;

/**
 * A rate control implementation based on additive increases of bitrate when no
 * over-use is detected and multiplicative decreases when over-uses are
 * detected. When we think the available bandwidth has changes or is unknown, we
 * will switch to a "slow-start mode" where we increase multiplicatively.
 *
 * Based on WebRTC modules/remote_bitrate_estimator/aimd_rate_control.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class AimdRateControl
{
    private static final Duration kDefaultRtt = DurationKt.getMs(200);
    private static final double kDefaultBackoffFactor = 0.85;

    private final boolean sendSide;

    private Bandwidth minConfiguredBitrate = BweDefines.kCongestionControllerMinBitrate;
    private Bandwidth maxConfiguredBitrate = Bandwidth.ofKbps(30000);
    private Bandwidth currentBitrate = maxConfiguredBitrate;
    private Bandwidth latestEstimatedThroughput = currentBitrate;

    public final LinkCapacityEstimator linkCapacity = new LinkCapacityEstimator();

    // Omitted:
    // val networkEstimate: NetworkStateEstimate? = null
    // As far as I can tell it is not ever set in current Chromium?

    private RateControlState rateControlState = RateControlState.kRcHold;

    private Instant timeLastBitrateChange = InstantKt.NEVER;

    @SuppressFBWarnings("URF_UNREAD_FIELD")
    private Instant timeLastBitrateDecrease = InstantKt.NEVER;
    private Instant timeFirstThroughputEstimate = InstantKt.NEVER;

    private boolean bitrateIsInitialized = false;
    private double beta = kDefaultBackoffFactor;
    public boolean inAlr = false;
    public Duration rtt = kDefaultRtt;

    // TODO: field trials: remove code that checks these

    // Allow the delay based estimate to only increase as long as application
    // limited region (alr) is not detected.
    private final boolean noBitrateIncreaseInAlr = false;

    // Only tested when networkEstimate != null
    // private val disableEstimateBoundedIncrease: Boolean = false
    // private val useCurrentEstimateAsMinUpperBound = true

    private Bandwidth lastDecrease = null;

    public AimdRateControl(boolean sendSide)
    {
        this.sendSide = sendSide;
    }

    public AimdRateControl()
    {
        this(false);
    }

    public void setStartBitrate(Bandwidth startBitrate)
    {
        currentBitrate = startBitrate;
        latestEstimatedThroughput = startBitrate;
        bitrateIsInitialized = true;
    }

    public void setMinBitrate(Bandwidth minBitrate)
    {
        minConfiguredBitrate = minBitrate;
        currentBitrate = Bandwidth.max(minBitrate, currentBitrate);
    }

    public boolean validEstimate()
    {
        return bitrateIsInitialized;
    }

    public Duration getFeedbackInterval()
    {
        // Estimate how often we can send RTCP if we allocate up to 5% of bandwidth
        // to feedback.
        DataSize kRtcpSize = DataSize.ofBytes(80);
        Bandwidth rtcpBitrate = currentBitrate.times(0.05);
        Duration interval = kRtcpSize.div(rtcpBitrate);
        Duration kMinFeedbackInterval = DurationKt.getMs(200);
        Duration kMaxFeedbackInterval = DurationKt.getMs(1000);
        return DurationKt.coerceIn(interval, kMinFeedbackInterval, kMaxFeedbackInterval);
    }

    public boolean timeToReduceFurther(Instant atTime, Bandwidth estimatedThroughput)
    {
        Duration bitrateReductionInterval = DurationKt.coerceIn(rtt, DurationKt.getMs(10), DurationKt.getMs(200));
        if (Duration.between(timeLastBitrateChange, atTime).compareTo(bitrateReductionInterval) >= 0)
        {
            return true;
        }
        if (validEstimate())
        {
            // TODO(terelius/holmer): Investigate consequences of increasing
            // the threshold to 0.95 * LatestEstimate().
            Bandwidth threshold = latestEstimate().times(0.5);
            return estimatedThroughput.compareTo(threshold) < 0;
        }
        return false;
    }

    public boolean initialTimeToReduceFurther(Instant atTime)
    {
        return validEstimate() &&
            timeToReduceFurther(atTime, latestEstimate().div(2).minus(Bandwidth.ofBps(1)));
    }

    public Bandwidth latestEstimate()
    {
        return currentBitrate;
    }

    public Bandwidth update(RateControlInput input, Instant atTime)
    {
        // Set the initial bit rate value to what we're receiving the first half
        // second.
        // TODO(bugs.webrtc.org/9379): The comment above doesn't match to the code.
        if (!bitrateIsInitialized)
        {
            Duration kInitializionTime = DurationKt.getSecs(5);
            if (BweDefines.kBitrateWindow.compareTo(kInitializionTime) > 0)
            {
                throw new IllegalStateException("Check failed: kBitrateWindow <= kInitializionTime");
            }
            if (timeFirstThroughputEstimate.equals(InstantKt.NEVER))
            {
                if (input.estimatedThroughput != null)
                {
                    timeFirstThroughputEstimate = atTime;
                }
            }
            else if (Duration.between(timeFirstThroughputEstimate, atTime).compareTo(kInitializionTime) > 0)
            {
                if (input.estimatedThroughput != null)
                {
                    currentBitrate = input.estimatedThroughput;
                    bitrateIsInitialized = true;
                }
            }
        }
        changeBitrate(input, atTime);
        return currentBitrate;
    }

    public void setEstimate(Bandwidth bitrate, Instant atTime)
    {
        bitrateIsInitialized = true;
        Bandwidth prevBitrate = currentBitrate;
        currentBitrate = clampBitrate(bitrate);
        timeLastBitrateChange = atTime;
        if (currentBitrate.compareTo(prevBitrate) < 0)
        {
            timeLastBitrateDecrease = atTime;
        }
    }

    public double getNearMaxIncreaseRateBpsPerSecond()
    {
        if (currentBitrate.equals(Bandwidth.ofBps(0)))
        {
            throw new IllegalStateException("Check failed: currentBitrate != 0.bps");
        }
        Duration kFrameInterval = DurationKt.div(DurationKt.getSecs(1), 30.0);
        DataSize frameSize = currentBitrate.times(kFrameInterval).toWholeBytes();
        DataSize kPacketSize = DataSize.ofBytes(1200);
        double packetsPerFrame = Math.ceil(frameSize.div(kPacketSize));
        DataSize avgPacketSize = frameSize.div(packetsPerFrame).toWholeBytes();

        // Approximate the over-use estimator delay to 100 ms.
        Duration responseTime = rtt.plus(DurationKt.getMs(100));

        responseTime = DurationKt.times(responseTime, 2);
        double increaseRateBpsPerSecond = avgPacketSize.per(responseTime).getBps();
        double kMinIncreaseRateBpsPerSecond = 4000.0;
        return Math.max(increaseRateBpsPerSecond, kMinIncreaseRateBpsPerSecond);
    }

    public Duration getExpectedBandwidthPeriod()
    {
        Duration kMinPeriod = DurationKt.getSecs(2);
        Duration kDefaultPeriod = DurationKt.getSecs(3);
        Duration kMaxPeriod = DurationKt.getSecs(50);

        double increaseRateBpsPerSecond = getNearMaxIncreaseRateBpsPerSecond();
        if (lastDecrease == null)
        {
            return kDefaultPeriod;
        }

        double timeToRecoverDecreaseSeconds = lastDecrease.getBps() / increaseRateBpsPerSecond;

        Duration period = DurationKt.durationOfDoubleSeconds(timeToRecoverDecreaseSeconds);
        return DurationKt.coerceIn(period, kMinPeriod, kMaxPeriod);
    }

    private void changeBitrate(RateControlInput input, Instant atTime)
    {
        Bandwidth newBitrate = null;
        Bandwidth estimatedThroughput = input.estimatedThroughput != null ?
            input.estimatedThroughput : latestEstimatedThroughput;
        if (input.estimatedThroughput != null)
        {
            latestEstimatedThroughput = input.estimatedThroughput;
        }

        // An over-use should always trigger us to reduce the bitrate, even though
        // we have not yet established our first estimate. By acting on the over-use,
        // we will end up with a valid estimate.
        if (!bitrateIsInitialized && input.bwState != BandwidthUsage.kBwOverusing)
        {
            return;
        }

        changeState(input, atTime);

        switch (rateControlState)
        {
        case kRcHold:
            break;

        case kRcIncrease:
        {
            if (estimatedThroughput.compareTo(linkCapacity.upperBound()) > 0)
            {
                linkCapacity.reset();
            }

            // We limit the new bitrate based on the troughput to avoid unlimited
            // bitrate increases. We allow a bit more lag at very low rates to not too
            // easily get stuck if the encoder produces uneven outputs.

            Bandwidth increaseLimit = estimatedThroughput.times(1.5).plus(Bandwidth.ofKbps(10));
            if (sendSide && inAlr && noBitrateIncreaseInAlr)
            {
                // TODO this is dead code because we're not using the noBitrateIncreaseInAlr field trial
                // Do not increase the delay based estimate in alr since the estimator
                // will not be able to get transport feedback necessary to detect if
                // the new estimate is correct.
                // If we have previously increased above the limit (for instance due to
                // probing), we don't allow further changes.
                increaseLimit = currentBitrate;
            }

            if (currentBitrate.compareTo(increaseLimit) < 0)
            {
                Bandwidth increasedBitrate = Bandwidth.MINUS_INFINITY;
                if (linkCapacity.hasEstimate())
                {
                    // The link_capacity estimate is reset if the measured throughput
                    // is too far from the estimate. We can therefore assume that our
                    // target rate is reasonably close to link capacity and use additive
                    // increase.
                    Bandwidth additiveIncrease = additiveRateIncrease(atTime, timeLastBitrateChange);
                    increasedBitrate = currentBitrate.plus(additiveIncrease);
                }
                else
                {
                    // If we don't have an estimate of the link capacity, use faster ramp
                    // up to discover the capacity.
                    Bandwidth multiplicativeIncrease = multiplicativeRateIncrease(
                        atTime,
                        timeLastBitrateChange,
                        currentBitrate
                    );
                    increasedBitrate = currentBitrate.plus(multiplicativeIncrease);
                }
                newBitrate = increasedBitrate.coerceAtMost(increaseLimit);
            }
            timeLastBitrateChange = atTime;
            break;
        }
        case kRcDecrease:
        {
            Bandwidth decreasedBitrate;

            // Set bit rate to something slightly lower than the measured throughput
            // to get rid of any self-induced delay.
            decreasedBitrate = estimatedThroughput.times(beta);
            if (decreasedBitrate.compareTo(Bandwidth.ofKbps(5)) > 0)
            {
                decreasedBitrate = decreasedBitrate.minus(Bandwidth.ofKbps(5));
            }
            if (decreasedBitrate.compareTo(currentBitrate) > 0)
            {
                // TODO(terelius): The link_capacity estimate may be based on old
                // throughput measurements. Relying on them may lead to unnecessary
                // BWE drops.
                if (linkCapacity.hasEstimate())
                {
                    decreasedBitrate = linkCapacity.getEstimate().times(beta);
                }
            }
            // Avoid increasing the rate when over-using.
            if (decreasedBitrate.compareTo(currentBitrate) < 0)
            {
                newBitrate = decreasedBitrate;
            }

            if (bitrateIsInitialized && estimatedThroughput.compareTo(currentBitrate) < 0)
            {
                if (newBitrate == null)
                {
                    lastDecrease = Bandwidth.ZERO;
                }
                else
                {
                    lastDecrease = currentBitrate.minus(newBitrate);
                }
            }
            if (estimatedThroughput.compareTo(linkCapacity.lowerBound()) < 0)
            {
                // The current throughput is far from the estimated link capacity. Clear
                // the estimate to allow an immediate update in onOveruseDetected.
                linkCapacity.reset();
            }

            bitrateIsInitialized = true;
            linkCapacity.onOveruseDetected(estimatedThroughput);
            // Stay on hold until the pipes are cleared.
            rateControlState = RateControlState.kRcHold;
            timeLastBitrateChange = atTime;
            timeLastBitrateDecrease = atTime;
            break;
        }
        }
        currentBitrate = clampBitrate(newBitrate != null ? newBitrate : currentBitrate);
    }

    private Bandwidth clampBitrate(Bandwidth newBitrate)
    {
        /* Skipping some conditions related to networkEstimate != null */

        return Bandwidth.max(newBitrate, minConfiguredBitrate);
    }

    private Bandwidth multiplicativeRateIncrease(Instant atTime, Instant lastTime, Bandwidth currentBitrate)
    {
        double alpha = 1.08;
        if (!lastTime.equals(InstantKt.NEVER))
        {
            Duration timeSinceLastUpdate = Duration.between(lastTime, atTime);
            alpha = Math.pow(alpha, Math.min(DurationKt.toDouble(timeSinceLastUpdate), 1.0));
        }
        Bandwidth multiplicativeIncrease =
            Bandwidth.max(currentBitrate.times(alpha - 1.0), Bandwidth.ofBps(1000));
        return multiplicativeIncrease;
    }

    private Bandwidth additiveRateIncrease(Instant atTime, Instant lastTime)
    {
        double timePeriodSeconds = DurationKt.toDouble(Duration.between(lastTime, atTime));
        double dataRateIncreaseBps = getNearMaxIncreaseRateBpsPerSecond() * timePeriodSeconds;
        return Bandwidth.ofBps(dataRateIncreaseBps);
    }

    private void changeState(RateControlInput input, Instant atTime)
    {
        switch (input.bwState)
        {
        case kBwNormal:
            if (rateControlState == RateControlState.kRcHold)
            {
                timeLastBitrateChange = atTime;
                rateControlState = RateControlState.kRcIncrease;
            }
            break;
        case kBwOverusing:
            if (rateControlState != RateControlState.kRcDecrease)
            {
                rateControlState = RateControlState.kRcDecrease;
            }
            break;
        case kBwUnderusing:
            rateControlState = RateControlState.kRcHold;
            break;
        }
    }

    public RateControlState getRateControlState()
    {
        return rateControlState;
    }

    public enum RateControlState
    {
        kRcHold,
        kRcIncrease,
        kRcDecrease
    }
}
