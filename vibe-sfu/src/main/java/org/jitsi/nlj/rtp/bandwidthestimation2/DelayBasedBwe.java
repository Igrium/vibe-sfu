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
import java.util.List;

/** Delay-based bandwidth estimation,
 * based on WebRTC modules/congestion_controller/goog_cc/delay_based_bwe.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Field trial settings have been generally removed, set to their default settings, and APIs that aren't
 * used by Chrome have also been removed.
 */
public class DelayBasedBwe
{
    public static final Duration kStreamTimeOut = Duration.ofSeconds(2);
    public static final Duration kSendTimeGroupLength = Duration.ofMillis(5);

    private static final TimeSeriesLogger timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(DelayBasedBwe.class);

    private final DiagnosticContext diagnosticContext;

    private final Logger logger;

    private InterArrivalDelta interArrivalDelta = null;
    public final DelayIncreaseDetectorInterface delayDetector;

    private Instant lastSeenPacket = InstantKt.NEVER;

    /* private var umaRecorded: Boolean = false */

    public final AimdRateControl rateControl = new AimdRateControl(true);
    private Bandwidth prevBitrate = Bandwidth.ZERO;
    private BandwidthUsage prevState = BandwidthUsage.kBwNormal;

    public DelayBasedBwe(Logger parentLogger, DiagnosticContext diagnosticContext)
    {
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.diagnosticContext = diagnosticContext;
        this.delayDetector = new TrendlineEstimator(logger, diagnosticContext);
    }

    public Result incomingPacketFeedbackVector(
        TransportPacketsFeedback msg,
        Bandwidth ackedBitrate,
        Bandwidth probeBitrate,
        /* networkEstimate: NetworkStateEstimate?, */
        boolean inAlr)
    {
        List<PacketResult> packetFeedbackVector = msg.sortedByReceiveTime();
        if (packetFeedbackVector.isEmpty())
        {
            // TODO(holmer): An empty feedback vector here likely means that
            // all acks were too late and that the send time history had
            // timed out. We should reduce the rate when this occurs.
            logger.warn("Very late feedback received.");
            return new Result();
        }

        /* Skipping uma_recorded, it's just for histograms. */
        boolean delayedFeedback = true;
        boolean recoveredFromOveruse = false;

        BandwidthUsage prevDetectorState = delayDetector.state();
        for (PacketResult packetFeedback : packetFeedbackVector)
        {
            delayedFeedback = false;
            incomingPacketFeedback(packetFeedback, msg.feedbackTime);
            if (prevDetectorState == BandwidthUsage.kBwUnderusing &&
                delayDetector.state() == BandwidthUsage.kBwNormal)
            {
                recoveredFromOveruse = true;
            }
            prevDetectorState = delayDetector.state();
        }

        if (delayedFeedback)
        {
            // TODO(bugs.webrtc.org/10125): Design a better mechanism to safe-guard
            // against building very large network queues.
            return new Result();
        }

        rateControl.inAlr = inAlr;
        // rateControl.networkStateEstimate = networkStateEstimate
        return maybeUpdateEstimate(
            ackedBitrate,
            probeBitrate,
            /* networkEstimate, */
            recoveredFromOveruse,
            inAlr,
            msg.feedbackTime
        );
    }

    private void incomingPacketFeedback(PacketResult packetFeedback, Instant atTime)
    {
        // Reset if the stream has timed out.
        if (lastSeenPacket.equals(InstantKt.NEVER)
            || Duration.between(lastSeenPacket, atTime).compareTo(kStreamTimeOut) > 0)
        {
            interArrivalDelta = new InterArrivalDelta(kSendTimeGroupLength);
        }
        lastSeenPacket = atTime;
        DataSize packetSize = packetFeedback.sentPacket.size;

        InterArrivalDelta.ComputeDeltasResult calculatedDeltas = interArrivalDelta.computeDeltas(
            packetFeedback.sentPacket.sendTime,
            packetFeedback.receiveTime,
            atTime,
            (long) packetSize.getBytes()
        );

        delayDetector.update(
            DurationKt.toDoubleMillis(calculatedDeltas.arrivalTimeDelta),
            DurationKt.toDoubleMillis(calculatedDeltas.sendTimeDelta),
            InstantKt.toRoundedEpochMilli(packetFeedback.sentPacket.sendTime),
            InstantKt.toRoundedEpochMilli(packetFeedback.receiveTime),
            (long) packetSize.getBytes(),
            calculatedDeltas.computed
        );
    }

    public Bandwidth triggerOveruse(Instant atTime, Bandwidth linkCapacity)
    {
        RateControlInput input = new RateControlInput(BandwidthUsage.kBwOverusing, linkCapacity);
        return rateControl.update(input, atTime);
    }

    private Result maybeUpdateEstimate(
        Bandwidth ackedBitrate,
        Bandwidth probeBitrate,
        /* networkEstimate: NetworkStateEstimate?, */
        boolean recoveredFromOveruse,
        boolean inAlr,
        Instant atTime)
    {
        Result result;
        // Currently overusing the bandwidth.
        if (delayDetector.state() == BandwidthUsage.kBwOverusing)
        {
            if (ackedBitrate != null && rateControl.timeToReduceFurther(atTime, ackedBitrate))
            {
                Bandwidth targetBitrate = updateEstimate(atTime, ackedBitrate);
                if (targetBitrate != null)
                {
                    result = new Result(true, false, targetBitrate, false);
                }
                else
                {
                    result = new Result();
                }
            }
            else if (ackedBitrate == null && rateControl.validEstimate() &&
                rateControl.initialTimeToReduceFurther(atTime))
            {
                rateControl.setEstimate(rateControl.latestEstimate().div(2), atTime);
                result = new Result(true, false, rateControl.latestEstimate(), false);
            }
            else
            {
                result = new Result();
            }
        }
        else
        {
            if (probeBitrate != null)
            {
                rateControl.setEstimate(probeBitrate, atTime);
                result = new Result(true, true, rateControl.latestEstimate(), false);
            }
            else
            {
                Bandwidth targetBitrate = updateEstimate(atTime, ackedBitrate);
                if (targetBitrate != null)
                {
                    result = new Result(true, false, targetBitrate, recoveredFromOveruse);
                }
                else
                {
                    result = new Result(false, false, Bandwidth.ofBps(0), recoveredFromOveruse);
                }
            }
        }
        BandwidthUsage detectorState = delayDetector.state();
        if ((result.updated && !prevBitrate.equals(result.targetBitrate)) ||
            detectorState != prevState)
        {
            Bandwidth bitrate;
            if (result.updated)
            {
                bitrate = result.targetBitrate;
            }
            else
            {
                bitrate = prevBitrate;
            }
            final Bandwidth bitrateForTrace = bitrate;
            timeSeriesLogger.trace(() ->
                diagnosticContext.makeTimeSeriesPoint("bwe_update_delay_based", atTime)
                    .addField("bitrate_bps", bitrateForTrace.getBps())
                    .addField("detector_state", detectorState.name()));

            prevBitrate = bitrate;
            prevState = detectorState;
        }
        result.delayDetectorState = detectorState;
        return result;
    }

    private Bandwidth updateEstimate(Instant atTime, Bandwidth ackedBitrate)
    {
        RateControlInput input = new RateControlInput(delayDetector.state(), ackedBitrate);
        Bandwidth targetRate = rateControl.update(input, atTime);
        if (rateControl.validEstimate())
        {
            return targetRate;
        }
        else
        {
            return null;
        }
    }

    public void onRttUpdate(Duration avgRtt)
    {
        rateControl.rtt = avgRtt;
    }

    public Bandwidth latestEstimate()
    {
        if (!rateControl.validEstimate())
        {
            return null;
        }
        return rateControl.latestEstimate();
    }

    public void setStartBitrate(Bandwidth startBitrate)
    {
        logger.info("BWE setting start bitrate to " + startBitrate);
        rateControl.setStartBitrate(startBitrate);
    }

    public void setMinBitrate(Bandwidth minBitrate)
    {
        rateControl.setMinBitrate(minBitrate);
    }

    public Duration getExpectedBwePeriod()
    {
        return rateControl.getExpectedBandwidthPeriod();
    }

    public Bandwidth lastEstimate()
    {
        return prevBitrate;
    }

    public BandwidthUsage lastState()
    {
        return prevState;
    }

    public static class Result
    {
        public final boolean updated;
        public final boolean probe;
        public final Bandwidth targetBitrate;
        public final boolean recoveredFromOveruse;
        public BandwidthUsage delayDetectorState = BandwidthUsage.kBwNormal;

        public Result(boolean updated, boolean probe, Bandwidth targetBitrate, boolean recoveredFromOveruse)
        {
            this.updated = updated;
            this.probe = probe;
            this.targetBitrate = targetBitrate;
            this.recoveredFromOveruse = recoveredFromOveruse;
        }

        public Result()
        {
            this(false, false, Bandwidth.ofBps(0), false);
        }
    }
}
