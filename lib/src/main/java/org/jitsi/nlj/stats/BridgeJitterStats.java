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

package org.jitsi.nlj.stats;

import org.jitsi.nlj.PacketInfo;

import java.time.Clock;
import java.time.Instant;

/**
 * Tracks the jitter of packets *within* the bridge (not over the network)
 */
public class BridgeJitterStats extends JitterStats
{
    private final Clock clock;

    public BridgeJitterStats()
    {
        this(Clock.systemUTC());
    }

    public BridgeJitterStats(Clock clock)
    {
        this.clock = clock;
    }

    public void packetSent(PacketInfo packetInfo)
    {
        Instant receivedTime = packetInfo.getReceivedTime();
        if (receivedTime != null)
        {
            super.addPacket(receivedTime, clock.instant());
        }
    }
}
