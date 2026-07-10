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

import org.jitsi.nlj.util.InstantUtils;
import org.jitsi.nlj.util.ThreadSafeVetoable;
import org.jitsi.utils.InstantKt;

import java.time.Instant;

public class PacketIOActivity
{
    /**
     * The last time an RTP or RTCP packet was received.
     */
    private final ThreadSafeVetoable<Instant> lastRtpPacketReceivedInstant =
        new ThreadSafeVetoable<>(InstantKt.NEVER, (oldValue, newValue) -> newValue.isAfter(oldValue));

    /**
     * The last time an RTP or RTCP packet was sent.
     */
    private final ThreadSafeVetoable<Instant> lastRtpPacketSentInstant =
        new ThreadSafeVetoable<>(InstantKt.NEVER, (oldValue, newValue) -> newValue.isAfter(oldValue));

    /**
     * The last time ICE consent was refreshed.
     */
    private final ThreadSafeVetoable<Instant> lastIceActivityInstant =
        new ThreadSafeVetoable<>(InstantKt.NEVER, (oldValue, newValue) -> newValue.isAfter(oldValue));

    public Instant getLastRtpPacketReceivedInstant()
    {
        return lastRtpPacketReceivedInstant.get();
    }

    public void setLastRtpPacketReceivedInstant(Instant instant)
    {
        lastRtpPacketReceivedInstant.set(instant);
    }

    public Instant getLastRtpPacketSentInstant()
    {
        return lastRtpPacketSentInstant.get();
    }

    public void setLastRtpPacketSentInstant(Instant instant)
    {
        lastRtpPacketSentInstant.set(instant);
    }

    public Instant getLastIceActivityInstant()
    {
        return lastIceActivityInstant.get();
    }

    public void setLastIceActivityInstant(Instant instant)
    {
        lastIceActivityInstant.set(instant);
    }

    /**
     * The last time an RTP or RTCP packet was sent or received.
     */
    public Instant getLastRtpActivityInstant()
    {
        return InstantUtils.latest(getLastRtpPacketReceivedInstant(), getLastRtpPacketSentInstant());
    }

    /**
     * The last time a packet was received (RTP, RTCP or ICE consent).
     */
    public Instant getLastIncomingActivityInstant()
    {
        return InstantUtils.latest(getLastRtpPacketReceivedInstant(), getLastIceActivityInstant());
    }

    /**
     * The last time a packet was sent or received.
     */
    public Instant getLastActivityInstant()
    {
        return InstantUtils.latest(
            getLastRtpPacketReceivedInstant(),
            getLastRtpPacketSentInstant(),
            getLastIceActivityInstant()
        );
    }
}
