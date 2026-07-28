/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.videobridge.cc.config;

/**
 * (Deviation: upstream backs this with jitsi-metaconfig/{@code JitsiConfig}, which this library strips. Plain Java
 * holder with the upstream {@code reference.conf} default ({@code videobridge.cc.padding-period = 15ms}) instead.)
 */
public class BandwidthProbingConfig
{
    /**
     * How often we check to send probing data.
     */
    public final long paddingPeriodMs = 15;

    @SuppressWarnings("checkstyle:VisibilityModifier")
    public static final BandwidthProbingConfig config = new BandwidthProbingConfig();

    private BandwidthProbingConfig()
    {
    }
}
