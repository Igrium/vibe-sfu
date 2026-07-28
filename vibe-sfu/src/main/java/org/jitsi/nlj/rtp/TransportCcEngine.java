/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.nlj.rtp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jetbrains.annotations.Nullable;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.rtcp.RtcpListener;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.rtp.rtcp.RtcpPacket;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public abstract class TransportCcEngine implements RtcpListener
{
    protected final List<LossListener> lossListeners = new ArrayList<>();

    /**
     * Called when an RTP sender has a new round-trip time estimate.
     */
    public abstract void onRttUpdate(Duration rtt);

    @Override
    public abstract void rtcpPacketReceived(RtcpPacket rtcpPacket, @Nullable Instant receivedTime);

    /** This is called when a tccSeqNum is first assigned to a packet, i.e. the soonest the packet can meaningfully
     * be described to the engine. */
    public abstract void mediaPacketTagged(PacketInfo packetInfo, long tccSeqNum);

    public abstract void mediaPacketSent(PacketInfo packetInfo, long tccSeqNum);

    public abstract StatisticsSnapshot getStatistics();

    /**
     * Adds a loss listener to be notified about packet arrival and loss reports.
     * @param listener
     */
    public synchronized void addLossListener(LossListener listener)
    {
        lossListeners.add(listener);
    }

    /**
     * Removes a loss listener.
     * @param listener
     */
    public synchronized void removeLossListener(LossListener listener)
    {
        lossListeners.remove(listener);
    }

    public abstract void addBandwidthListener(BandwidthListener listener);

    public abstract void removeBandwidthListener(BandwidthListener listener);

    public abstract void start();

    public abstract void stop();

    public abstract static class StatisticsSnapshot
    {
        public abstract ObjectNode toJson();
    }

    public interface BandwidthListener
    {
        void bandwidthEstimationChanged(Bandwidth newValue);
    }
}
