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
package org.jitsi.nlj;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.rtp.LossListener;
import org.jitsi.nlj.rtp.RtpExtensionType;
import org.jitsi.nlj.rtp.TransportCcEngine;
import org.jitsi.nlj.srtp.SrtpTransformers;
import org.jitsi.nlj.stats.EndpointConnectionStats;
import org.jitsi.nlj.stats.PacketStreamStats;
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsTracker.OutgoingStatisticsSnapshot;

import java.util.function.Function;

/**
 * Not an 'RtpSender' in the sense that it sends only RTP (and not
 * RTCP) but in the sense of a webrtc 'RTCRTPSender' which handles
 * all RTP and RTP control packets.
 */
public abstract class RtpSender
    extends StatsKeepingPacketHandler
    implements EventHandler, Stoppable, EndpointConnectionStats.EndpointConnectionStatsListener
{
    public abstract int sendProbing(java.util.Collection<Long> mediaSsrcs, int numBytes);
    public abstract void onOutgoingPacket(PacketHandler handler);
    public abstract void setSrtpTransformers(SrtpTransformers srtpTransformers);
    public abstract OutgoingStatisticsSnapshot getStreamStats();
    public abstract PacketStreamStats.Snapshot getPacketStreamStats();
    public abstract void addBandwidthListener(TransportCcEngine.BandwidthListener listener);
    public abstract void removeBandwidthListener(TransportCcEngine.BandwidthListener listener);
    public abstract TransportCcEngine.StatisticsSnapshot getTransportCcEngineStats();

    public void requestKeyframe(String requesterID)
    {
        requestKeyframe(requesterID, null);
    }

    public abstract void requestKeyframe(String requesterID, Long mediaSsrc);
    public abstract void addLossListener(LossListener lossListener);
    public abstract void setFeature(Features feature, boolean enabled);
    public abstract boolean isFeatureEnabled(Features feature);
    public abstract void tearDown();
    public abstract void addRtpExtensionToRetain(RtpExtensionType extensionType);

    /**
     * An optional function to be executed for each RTP packet, as the first step of the send pipeline.
     */
    private Function<PacketInfo, PacketInfo> preProcesor = null;

    public Function<PacketInfo, PacketInfo> getPreProcesor()
    {
        return preProcesor;
    }

    public void setPreProcesor(Function<PacketInfo, PacketInfo> preProcesor)
    {
        this.preProcesor = preProcesor;
    }

    public abstract ObjectNode debugState(DebugStateMode mode);
}
