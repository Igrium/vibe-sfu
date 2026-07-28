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

import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.utils.logging2.Logger;

import java.util.ArrayDeque;

/**
 * Trendline-based delay increase detector
 * *
 * Based on WebRTC modules/congestion_controller/goog_cc/trendline_estimator.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class TrendlineEstimator implements DelayIncreaseDetectorInterface
{
    public static final double kDefaultTrendlineSmoothingCoeff = 0.9;
    public static final double kDefaultTrendlineThresholdGain = 4.0;

    public static final double kMaxAdaptOffsetMs = 15.0;
    public static final double kOverusingTimeThreshold = 10.0;
    public static final int kMinNumDeltas = 60;
    public static final int kDeltaCounterMax = 1000;

    private static final TimeSeriesLogger timeSeriesLogger =
        TimeSeriesLogger.getTimeSeriesLogger(TrendlineEstimator.class);

    private final DiagnosticContext diagnosticContext;

    private final TrendlineEstimatorSettings settings = new TrendlineEstimatorSettings();

    // Parameters
    private final double smoothingCoef = kDefaultTrendlineSmoothingCoeff;
    private final double thresholdGain = kDefaultTrendlineThresholdGain;

    // Used by the existing threshold.
    private int numOfDeltas = 0;

    // Keep the arrival times small by using the change from the first packet
    private long firstArrivalTimeMs = -1;

    // Exponential backoff filtering.
    private double accumulatedDelay = 0.0;
    private double smoothedDelay = 0.0;

    // Linear least squares regression
    private final ArrayDeque<PacketTiming> delayHist = new ArrayDeque<>();

    private final double kUp = 0.0087;
    private final double kDown = 0.039;
    private final double overusingTimeThreshold = kOverusingTimeThreshold;
    private double threshold = 12.5;

    private double prevModifiedTrend = Double.NaN;

    private long lastUpdateMs = -1L;
    private double prevTrend = 0.0;
    private double timeOverUsing = -1.0;
    private int overuseCounter = 0;
    private BandwidthUsage hypothesis = BandwidthUsage.kBwNormal;

    // Only used with networkStatePredictor
    // private var hypothesisPredicted = BandwidthUsage.kBwNormal

    // private var networkStatePredictor: NetworkStatePredictor? = null

    public TrendlineEstimator(Logger parentLogger, DiagnosticContext diagnosticContext)
    {
        this.diagnosticContext = diagnosticContext;
    }

    public double getThreshold()
    {
        return threshold;
    }

    public double getPrevModifiedTrend()
    {
        return prevModifiedTrend;
    }

    public double getPrevTrend()
    {
        return prevTrend;
    }

    public void updateTrendline(
        double recvDeltaMs,
        double sendDeltaMs,
        long sendTimeMs,
        long arrivalTimeMs,
        long packetSize)
    {
        double deltaMs = recvDeltaMs - sendDeltaMs;
        ++numOfDeltas;
        numOfDeltas = Math.min(numOfDeltas, kDeltaCounterMax);
        if (firstArrivalTimeMs == -1L)
        {
            firstArrivalTimeMs = arrivalTimeMs;
        }

        // Exponential backoff filter.
        accumulatedDelay += deltaMs;
        smoothedDelay = smoothingCoef * smoothedDelay + (1 - smoothingCoef) * accumulatedDelay;

        // Maintain packet window
        delayHist.addLast(
            new PacketTiming((double) (arrivalTimeMs - firstArrivalTimeMs), smoothedDelay, accumulatedDelay)
        );
        if (settings.enableSort)
        {
            // TODO - not currently enabled
        }
        if (delayHist.size() > settings.windowSize)
        {
            delayHist.removeFirst();
        }

        // Simple linear regression.
        double trend = prevTrend;
        if (delayHist.size() == settings.windowSize)
        {
            // Update trend_ if it is possible to fit a line to the data. The delay
            // trend can be seen as an estimate of (send_rate - capacity)/capacity.
            // 0 < trend < 1   ->  the delay increases, queues are filling up
            //   trend == 0    ->  the delay does not change
            //   trend < 0     ->  the delay decreases, queues are being emptied
            Double slope = linearFitSlope(delayHist);
            trend = slope != null ? slope : trend;
            if (settings.enableCap)
            {
                Double cap = computeSlopeCap(delayHist, settings);
                // We only use the cap to filter out overuse detections, not
                // to detect additional underuses.
                if (trend >= 0 && cap != null && trend > cap)
                {
                    trend = cap;
                }
            }
        }
        timeSeriesLogger.trace(() ->
            diagnosticContext.makeTimeSeriesPoint("trendline_updated")
                .addField("accumulated_delay_ms", accumulatedDelay)
                .addField("smoothed_delay_ms", smoothedDelay));

        detect(trend, sendDeltaMs, arrivalTimeMs);
    }

    @Override
    public void update(
        double recvDeltaMs,
        double sendDeltaMs,
        long sendTimeMs,
        long arrivalTimeMs,
        long packetSize,
        boolean calculatedDeltas)
    {
        if (calculatedDeltas)
        {
            updateTrendline(recvDeltaMs, sendDeltaMs, sendTimeMs, arrivalTimeMs, packetSize);
        }
        /* if (networkStatePredictor != null) {
               hypothesisPredicted = networkStatePredictor.update(sendTimeMs, arrivalTimeMs, hypothesis)
           }
         */
    }

    @Override
    public BandwidthUsage state()
    {
        /* if (networkStatePredictor != null) {
              return hypothesisPredicted
         */
        return hypothesis;
    }

    private void detect(double trend, double tsDelta, long nowMs)
    {
        if (numOfDeltas < 2)
        {
            hypothesis = BandwidthUsage.kBwNormal;
            return;
        }
        double modifiedTrend = Math.min(numOfDeltas, kMinNumDeltas) * trend * thresholdGain;
        prevModifiedTrend = modifiedTrend;
        timeSeriesLogger.trace(() ->
            diagnosticContext.makeTimeSeriesPoint("trendline_detect", nowMs)
                .addField("trend", modifiedTrend)
                .addField("threshold", threshold));
        if (modifiedTrend > threshold)
        {
            if (timeOverUsing == -1.0)
            {
                // Initialize the timer. Assume that we've been
                // over-using half of the time since the previous
                // sample.
                timeOverUsing = tsDelta / 2;
            }
            else
            {
                // Increment timer
                timeOverUsing += tsDelta;
            }
            overuseCounter++;
            if (timeOverUsing > overusingTimeThreshold && overuseCounter > 1)
            {
                if (trend >= prevTrend)
                {
                    timeOverUsing = 0.0;
                    overuseCounter = 0;
                    hypothesis = BandwidthUsage.kBwOverusing;
                }
            }
        }
        else if (modifiedTrend < -threshold)
        {
            timeOverUsing = -1.0;
            overuseCounter = 0;
            hypothesis = BandwidthUsage.kBwUnderusing;
        }
        else
        {
            timeOverUsing = -1.0;
            overuseCounter = 0;
            hypothesis = BandwidthUsage.kBwNormal;
        }
        prevTrend = trend;
        updateThreshold(modifiedTrend, nowMs);
    }

    private void updateThreshold(double modifiedTrend, long nowMs)
    {
        if (lastUpdateMs == -1L)
        {
            lastUpdateMs = nowMs;
        }

        if (Math.abs(modifiedTrend) > threshold + kMaxAdaptOffsetMs)
        {
            // Avoid adapting the threshold to big latency spikes, caused e.g.,
            // by a sudden capacity drop.
            lastUpdateMs = nowMs;
            return;
        }

        double k;
        if (Math.abs(modifiedTrend) < threshold)
        {
            k = kDown;
        }
        else
        {
            k = kUp;
        }
        final long kMaxTimeDeltaMs = 100L;
        long timeDeltaMs = Math.min(nowMs - lastUpdateMs, kMaxTimeDeltaMs);
        threshold += k * (Math.abs(modifiedTrend) - threshold) * timeDeltaMs;
        threshold = Math.min(Math.max(threshold, 6.0), 600.0);
        lastUpdateMs = nowMs;
    }

    /* TODO: this class is redundant if we don't have field trial settings - remove it? */
    private static class TrendlineEstimatorSettings
    {
        static final int kDefaultTrendlineWindowSize = 20;

        // Sort the packets in the window. Should be redundant,
        // but then almost no cost.
        final boolean enableSort = false;

        // Cap the trendline slope based on the minimum delay seen
        // in the beginning_packets and end_packets respectively.
        final boolean enableCap = false;

        final int beginningPackets = 7;
        final int endPackets = 7;
        final double capUncertainty = 0.0;

        final int windowSize = kDefaultTrendlineWindowSize;
    }

    private static class PacketTiming
    {
        final double arrivalTimeMs;
        final double smoothedDelayMs;
        final double rawDelayMs;

        PacketTiming(double arrivalTimeMs, double smoothedDelayMs, double rawDelayMs)
        {
            this.arrivalTimeMs = arrivalTimeMs;
            this.smoothedDelayMs = smoothedDelayMs;
            this.rawDelayMs = rawDelayMs;
        }
    }

    private static Double linearFitSlope(ArrayDeque<PacketTiming> packets)
    {
        if (packets.size() < 2)
        {
            throw new IllegalStateException("Check failed: packets.size >= 2");
        }
        // Compute the "center of mass"
        double sumX = 0.0;
        double sumY = 0.0;
        for (PacketTiming packet : packets)
        {
            sumX += packet.arrivalTimeMs;
            sumY += packet.smoothedDelayMs;
        }
        double xAvg = sumX / packets.size();
        double yAvg = sumY / packets.size();
        // Compute the slope k = \sum (x_i-x_avg)(y_i-y_avg) / \sum (x_i-x_avg)^2
        double numerator = 0.0;
        double denominator = 0.0;
        for (PacketTiming packet : packets)
        {
            double x = packet.arrivalTimeMs;
            double y = packet.smoothedDelayMs;
            numerator += (x - xAvg) * (y - yAvg);
            denominator += (x - xAvg) * (x - xAvg);
        }
        if (denominator == 0.0)
        {
            return null;
        }
        return numerator / denominator;
    }

    private static Double computeSlopeCap(ArrayDeque<PacketTiming> packets, TrendlineEstimatorSettings settings)
    {
        // (Deviation: upstream indexes the ArrayDeque directly; Java's ArrayDeque has no
        // indexed access, so we copy to an array first. Only called when settings.enableCap,
        // which is currently always false.)
        PacketTiming[] p = packets.toArray(new PacketTiming[0]);
        if (!(1 <= settings.beginningPackets && settings.beginningPackets < p.length))
        {
            throw new IllegalStateException("Check failed: 1 <= beginningPackets < packets.size");
        }
        if (!(1 <= settings.endPackets && settings.endPackets < p.length))
        {
            throw new IllegalStateException("Check failed: 1 <= endPackets < packets.size");
        }
        if (!(settings.beginningPackets + settings.endPackets <= p.length))
        {
            throw new IllegalStateException("Check failed: beginningPackets + endPackets <= packets.size");
        }
        PacketTiming early = p[0];
        for (int i = 1; i < settings.beginningPackets; i++)
        {
            if (p[i].rawDelayMs < early.rawDelayMs)
            {
                early = p[i];
            }
        }
        int lateStart = p.length - settings.endPackets;
        PacketTiming late = p[lateStart];
        for (int i = lateStart + 1; i < p.length; i++)
        {
            if (p[i].rawDelayMs < late.rawDelayMs)
            {
                late = p[i];
            }
        }
        if (late.arrivalTimeMs - early.arrivalTimeMs < 1)
        {
            return null;
        }
        return (late.rawDelayMs - early.rawDelayMs) / (late.arrivalTimeMs - early.arrivalTimeMs)
            + settings.capUncertainty;
    }
}
