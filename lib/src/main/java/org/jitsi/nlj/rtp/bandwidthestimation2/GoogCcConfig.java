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

/**
 * (Deviation: upstream declares this at the top of GoogCcNetworkController.kt; split to its
 * own file since GoogCcFactory references it unqualified.)
 */
public class GoogCcConfig
{
    public final boolean feedbackOnly;
    /* Not in this object in WebRTC: This is a field trial parameter there */
    public final CongestionWindowConfig rateControlSettings;

    public GoogCcConfig(boolean feedbackOnly, CongestionWindowConfig rateControlSettings)
    {
        this.feedbackOnly = feedbackOnly;
        this.rateControlSettings = rateControlSettings;
    }

    public GoogCcConfig(boolean feedbackOnly)
    {
        this(feedbackOnly, new CongestionWindowConfig());
    }

    public GoogCcConfig()
    {
        this(false, new CongestionWindowConfig());
    }
}
