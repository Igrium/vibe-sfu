/*
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

package com.igrium.sfu;

import org.jitsi.videobridge.datachannel.protocol.DataChannelProtocolConstants;

/**
 * Options for creating a WebRTC data channel, mirroring the browser
 * {@code RTCDataChannelInit} dictionary. At most one of
 * {@link #maxRetransmits(int)} and {@link #maxPacketLifeTimeMs(int)} may be set.
 */
public final class DataChannelOptions
{
    private boolean ordered = true;
    private int maxRetransmits = -1;
    private int maxPacketLifeTimeMs = -1;
    private int priority = 0;

    /**
     * Whether messages are delivered in order (default {@code true}).
     */
    public DataChannelOptions ordered(boolean ordered)
    {
        this.ordered = ordered;
        return this;
    }

    /**
     * Limits the number of times the channel retransmits an undelivered message
     * (partial reliability). Mutually exclusive with {@link #maxPacketLifeTimeMs(int)}.
     */
    public DataChannelOptions maxRetransmits(int maxRetransmits)
    {
        this.maxRetransmits = maxRetransmits;
        return this;
    }

    /**
     * Limits the time (in milliseconds) during which the channel retransmits an
     * undelivered message (partial reliability). Mutually exclusive with
     * {@link #maxRetransmits(int)}.
     */
    public DataChannelOptions maxPacketLifeTimeMs(int maxPacketLifeTimeMs)
    {
        this.maxPacketLifeTimeMs = maxPacketLifeTimeMs;
        return this;
    }

    /**
     * The DCEP priority of the channel (default 0).
     */
    public DataChannelOptions priority(int priority)
    {
        this.priority = priority;
        return this;
    }

    public boolean isOrdered()
    {
        return ordered;
    }

    public int getMaxRetransmits()
    {
        return maxRetransmits;
    }

    public int getMaxPacketLifeTimeMs()
    {
        return maxPacketLifeTimeMs;
    }

    public int getPriority()
    {
        return priority;
    }

    /**
     * The DCEP channel-type byte (RFC 8832) implied by these options.
     */
    int channelType()
    {
        if (maxRetransmits >= 0 && maxPacketLifeTimeMs >= 0)
        {
            throw new IllegalStateException("maxRetransmits and maxPacketLifeTimeMs are mutually exclusive");
        }
        int base;
        if (maxRetransmits >= 0)
        {
            base = DataChannelProtocolConstants.PARTIAL_RELIABLE_REXMIT;
        }
        else if (maxPacketLifeTimeMs >= 0)
        {
            base = DataChannelProtocolConstants.PARTIAL_RELIABLE_TIMED;
        }
        else
        {
            base = DataChannelProtocolConstants.RELIABLE;
        }
        return ordered ? base : (base | 0x80);
    }

    /**
     * The DCEP reliability parameter (RFC 8832) implied by these options.
     */
    long reliability()
    {
        if (maxRetransmits >= 0)
        {
            return maxRetransmits;
        }
        if (maxPacketLifeTimeMs >= 0)
        {
            return maxPacketLifeTimeMs;
        }
        return 0;
    }
}
