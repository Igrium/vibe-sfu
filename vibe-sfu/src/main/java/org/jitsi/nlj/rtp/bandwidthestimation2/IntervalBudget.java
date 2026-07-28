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

/** Interval budget,
 * based on WebRTC modules/pacing/interval_budget.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class IntervalBudget
{
    private static final long kWindowMs = 500L;

    private final boolean canBuildUpUnderuse;

    private int targetRateKbps = 0;
    private long maxBytesInBudget = 0;
    private long bytesRemaining = 0;

    public IntervalBudget(int initialTargetRateKbps, boolean canBuildUpUnderuse)
    {
        this.canBuildUpUnderuse = canBuildUpUnderuse;
        setTargetRateKbps(initialTargetRateKbps);
    }

    public IntervalBudget(int initialTargetRateKbps)
    {
        this(initialTargetRateKbps, false);
    }

    public void setTargetRateKbps(int targetRateKbps)
    {
        this.targetRateKbps = targetRateKbps;
        maxBytesInBudget = (kWindowMs * targetRateKbps) / 8;
        bytesRemaining = Math.min(Math.max(-maxBytesInBudget, bytesRemaining), maxBytesInBudget);
    }

    // TODO(tschumim): Unify IncreaseBudget and UseBudget to one function.
    public void increaseBudget(long deltaTimeMs)
    {
        long bytes = targetRateKbps * deltaTimeMs / 8;
        if (bytesRemaining < 0 || canBuildUpUnderuse)
        {
            // We overused last interval, compensate this interval.
            bytesRemaining = Math.min(bytesRemaining + bytes, maxBytesInBudget);
        }
        else
        {
            // If we underused last interval we can't use it this interval.
            bytesRemaining = Math.min(bytes, maxBytesInBudget);
        }
    }

    public void useBudget(long bytes)
    {
        bytesRemaining = Math.max(bytesRemaining - bytes, -maxBytesInBudget);
    }

    public long bytesRemaining()
    {
        return Math.max(0, bytesRemaining);
    }

    public double budgetRatio()
    {
        if (maxBytesInBudget == 0L)
        {
            return 0.0;
        }
        return (double) bytesRemaining / maxBytesInBudget;
    }

    public int targetRateKbps()
    {
        return targetRateKbps;
    }
}
