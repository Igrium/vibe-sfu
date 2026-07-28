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
package org.jitsi.nlj.rtp.bandwidthestimation2;

import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.utils.InstantKt;

import java.time.Duration;
import java.time.Instant;

/**
 * Computes a bayesian estimate of the throughput given acks containing
 * the arrival time and payload size. Samples which are far from the current
 * estimate or are based on few packets are given a smaller weight, as they
 * are considered to be more likely to have been caused by, e.g., delay spikes
 * unrelated to congestion.
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/bitrate_estimator.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class BitrateEstimator
{
    public static final int kInitialRateWindowMs = 500;
    public static final int kRateWindowMs = 150;
    public static final int kMinRateWindowMs = 150;
    public static final int kMaxRateWindowMs = 1000;

    private int sum = 0;
    private final int initialWindowMs = kInitialRateWindowMs;
    private final int noninitialWindowMs = kRateWindowMs;
    private final float uncertaintyScale = 10.0f;
    private final float uncertaintyScaleInAlr = uncertaintyScale;
    private final float smallSampleUncertaintyScale = uncertaintyScale;
    private final DataSize smallSampleThreshold = DataSize.ZERO;
    private final Bandwidth uncertaintySymmetryCap = Bandwidth.ZERO;
    private final Bandwidth estimateFloor = Bandwidth.ZERO;

    private long currentWindowMs = 0;
    private long prevTimeMs = -1;
    private float bitrateEstimateKbps = -1.0f;
    private float bitrateEstimateVar = 50.0f;

    public void update(Instant atTime, DataSize amount, boolean inAlr)
    {
        int rateWindowMs = bitrateEstimateKbps < 0.0f ? initialWindowMs : noninitialWindowMs;
        WindowUpdateResult result =
            updateWindow(InstantKt.toRoundedEpochMilli(atTime), (int) Math.round(amount.getBytes()), rateWindowMs);
        float bitrateSampleKbps = result.bitrateSampleKbps;
        boolean isSmallSample = result.isSmallSample;
        if (bitrateSampleKbps < 0.0f)
        {
            return;
        }
        if (bitrateEstimateKbps < 0.0f)
        {
            // This is the very first sample we get. Use it to initialize the estimate.
            bitrateEstimateKbps = bitrateSampleKbps;
            return;
        }
        // Optionally use higher uncertainty for very small samples to avoid dropping
        // estimate and for samples obtained in ALR.
        float scale;
        if (isSmallSample && bitrateSampleKbps < bitrateEstimateKbps)
        {
            scale = smallSampleUncertaintyScale;
        }
        else if (inAlr && bitrateSampleKbps < bitrateEstimateKbps)
        {
            scale = uncertaintyScaleInAlr;
        }
        else
        {
            scale = uncertaintyScale;
        }
        // Define the sample uncertainty as a function of how far away it is from the
        // current estimate. With low values of uncertaintySymmetryCap we add more
        // uncertainty to increases than to decreases. For higher values we approach
        // symmetry.
        float sampleUncertainty =
            scale * Math.abs(bitrateEstimateKbps - bitrateSampleKbps) /
                (bitrateEstimateKbps + Math.min(bitrateSampleKbps, (float) uncertaintySymmetryCap.getKbps()));

        float sampleVar = sampleUncertainty * sampleUncertainty;
        // Update a bayesian estimate of the rate, weighting it lower if the sample
        // uncertainty is large.
        // The bitrate estimate uncertainty is increased with each update to model
        // that the bitrate changes over time.
        float predBitrateEstimateVar = bitrateEstimateVar + 5.0f;
        bitrateEstimateKbps = (sampleVar * bitrateEstimateKbps + predBitrateEstimateVar * bitrateSampleKbps) /
            (sampleVar + predBitrateEstimateVar);
        bitrateEstimateKbps = Math.max(bitrateEstimateKbps, (float) estimateFloor.getKbps());
        bitrateEstimateVar = sampleVar * predBitrateEstimateVar / (sampleVar + predBitrateEstimateVar);
    }

    private static class WindowUpdateResult
    {
        final float bitrateSampleKbps;
        final boolean isSmallSample;

        WindowUpdateResult(float bitrateSampleKbps, boolean isSmallSample)
        {
            this.bitrateSampleKbps = bitrateSampleKbps;
            this.isSmallSample = isSmallSample;
        }
    }

    private WindowUpdateResult updateWindow(long nowMs, int bytes, int rateWindowMs)
    {
        // Reset if time moves backwards.
        if (nowMs < prevTimeMs)
        {
            prevTimeMs = -1;
            sum = 0;
            currentWindowMs = 0;
        }
        if (prevTimeMs >= 0)
        {
            currentWindowMs += nowMs - prevTimeMs;
            // Reset if nothing has been received for more than a full window.
            if (nowMs - prevTimeMs > rateWindowMs)
            {
                sum = 0;
                currentWindowMs %= rateWindowMs;
            }
        }
        prevTimeMs = nowMs;
        float bitrateSample;
        boolean isSmallSample;
        if (currentWindowMs >= rateWindowMs)
        {
            isSmallSample = sum < smallSampleThreshold.getBytes();
            bitrateSample = 8.0f * sum / (float) rateWindowMs;
            currentWindowMs -= rateWindowMs;
            sum = 0;
        }
        else
        {
            isSmallSample = false;
            bitrateSample = -1.0f;
        }
        sum += bytes;
        return new WindowUpdateResult(bitrateSample, isSmallSample);
    }

    public Bandwidth bitrate()
    {
        return bitrateEstimateKbps < 0.0f ? null : Bandwidth.ofKbps(bitrateEstimateKbps);
    }

    public Bandwidth peekRate()
    {
        return currentWindowMs > 0 ? DataSize.ofBytes(sum).per(Duration.ofMillis(currentWindowMs)) : null;
    }

    public void expectFastRateChange()
    {
        // By setting the bitrate-estimate variance to a higher value we allow the
        // bitrate to change fast for the next few samples.
        bitrateEstimateVar += 200;
    }
}
