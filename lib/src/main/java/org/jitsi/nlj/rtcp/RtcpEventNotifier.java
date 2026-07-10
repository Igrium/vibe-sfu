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
package org.jitsi.nlj.rtcp;

import org.jitsi.rtp.rtcp.RtcpPacket;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A central place to allow the publishing of when RTCP packets are received or sent.  We're
 * interested in both of these scenarios for things like SRs, RRs and for RTT calculations
 */
// TODO(brian): maybe post the notifications to another pool, so we don't hold up the caller?
public class RtcpEventNotifier
{
    /**
     * (Deviation: upstream stores {@code List<Pair<RtcpListener, Boolean>>}; Kotlin's {@code Pair} has no direct
     * Java equivalent used elsewhere in this port, so it becomes this small holder class.)
     */
    private static class ListenerEntry
    {
        final RtcpListener listener;
        final boolean external;

        ListenerEntry(RtcpListener listener, boolean external)
        {
            this.listener = listener;
            this.external = external;
        }
    }

    private final List<ListenerEntry> rtcpListeners = new CopyOnWriteArrayList<>();

    public void addRtcpEventListener(RtcpListener listener)
    {
        addRtcpEventListener(listener, false);
    }

    /**
     * Add an {@link RtcpListener}.  An {@code external} listener will not receive notifications that are themselves
     * marked as external.  This allows notifications to be passed between multiple listeners without
     * creating an infinite loop.
     */
    public void addRtcpEventListener(RtcpListener listener, boolean external)
    {
        rtcpListeners.add(new ListenerEntry(listener, external));
    }

    public void notifyRtcpReceived(RtcpPacket packet, Instant receivedTime)
    {
        notifyRtcpReceived(packet, receivedTime, false);
    }

    public void notifyRtcpReceived(RtcpPacket packet, Instant receivedTime, boolean external)
    {
        for (ListenerEntry entry : rtcpListeners)
        {
            if (!external || !entry.external)
            {
                entry.listener.rtcpPacketReceived(packet, receivedTime);
            }
        }
    }

    public void notifyRtcpSent(RtcpPacket rtcpPacket)
    {
        notifyRtcpSent(rtcpPacket, false);
    }

    public void notifyRtcpSent(RtcpPacket rtcpPacket, boolean external)
    {
        for (ListenerEntry entry : rtcpListeners)
        {
            if (!external || !entry.external)
            {
                entry.listener.rtcpPacketSent(rtcpPacket);
            }
        }
    }
}
