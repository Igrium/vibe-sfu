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

import org.jitsi.nlj.util.DataSize;

/** Congestion window pushback controller,
 * based on WebRTC modules/congestion_controller/goog_cc/congestion_window_pushback_controller.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Field trial settings have been generally removed, set to their default settings.
 */
public class CongestionWindowPushbackController
{
    public void updateOutstandingData(long outstandingBytes)
    {
        this.outstandingBytes = outstandingBytes;
    }

    public void updatePacingQueue(long pacingBytes)
    {
        this.pacingBytes = pacingBytes;
    }

    public int updateTargetBitrate(int bitrateBps)
    {
        if (currentDataWindow == null || currentDataWindow.equals(DataSize.ZERO))
        {
            return bitrateBps;
        }
        long totalBytes = outstandingBytes;
        if (addPacing)
        {
            totalBytes += pacingBytes;
        }
        long fillRatio = totalBytes / (long) currentDataWindow.getBytes();
        if (fillRatio > 1.5)
        {
            encodingRateRatio *= 0.9;
        }
        else if (fillRatio > 1.0)
        {
            encodingRateRatio *= 0.95;
        }
        else if (fillRatio < 0.1)
        {
            encodingRateRatio = 1.0;
        }
        else
        {
            encodingRateRatio *= 1.05;
            encodingRateRatio = Math.min(encodingRateRatio, 1.0);
        }
        int adjustedTargetBitrateBps = (int) (bitrateBps * encodingRateRatio);

        // Do not adjust below the minimum pushback bitrate but do obey if the
        // original estimate is below it.
        if (adjustedTargetBitrateBps < minPushbackTargetBitrateBps)
        {
            return Math.min(bitrateBps, minPushbackTargetBitrateBps);
        }
        else
        {
            return adjustedTargetBitrateBps;
        }
    }

    public void setDataWindow(DataSize dataWindow)
    {
        currentDataWindow = dataWindow;
    }

    private final boolean addPacing = false;

    private final int minPushbackTargetBitrateBps = kDefaultMinPushbackTargetBitrateBps;

    private DataSize currentDataWindow = null;

    private long outstandingBytes = 0L;

    private long pacingBytes = 0L;

    private double encodingRateRatio = 1.0;

    /** From rtc_base/experiments/rate_control_settings.cc */
    private static final int kDefaultMinPushbackTargetBitrateBps = 30000;
}
