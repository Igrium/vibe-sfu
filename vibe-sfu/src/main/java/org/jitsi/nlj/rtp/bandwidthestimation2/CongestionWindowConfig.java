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

/* Congestion window config, from WebRTC rtc_base/experiments/rate_control_settings.{h,cc}
 * stripped down to used fields only.
 *
 * (Deviation: upstream declares this at the bottom of GoogCcNetworkController.kt; split to
 * its own file since GoogCcFactory references it unqualified.)
 */
public class CongestionWindowConfig
{
    private static final int kDefaultAcceptedQueueMs = 350;

    private final Integer queueSizeMs;
    private final Integer minBitrateBps;
    private final boolean dropFrameOnly;

    public CongestionWindowConfig(Integer queueSizeMs, Integer minBitrateBps, boolean dropFrameOnly)
    {
        this.queueSizeMs = queueSizeMs;
        this.minBitrateBps = minBitrateBps;
        this.dropFrameOnly = dropFrameOnly;
    }

    public CongestionWindowConfig()
    {
        this(350, 30000, true);
    }

    public boolean useCongestionWindow()
    {
        return queueSizeMs != null;
    }

    public int getCongestionWindowAdditionalTimeMs()
    {
        return queueSizeMs != null ? queueSizeMs : kDefaultAcceptedQueueMs;
    }

    public boolean useCongestionWindowPushback()
    {
        return queueSizeMs != null && minBitrateBps != null;
    }

    public boolean useCongestionWindowDropFrameOnly()
    {
        return dropFrameOnly;
    }
}
