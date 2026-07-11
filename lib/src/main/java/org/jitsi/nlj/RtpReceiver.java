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
import org.jitsi.nlj.srtp.SrtpTransformers;
import org.jitsi.nlj.stats.EndpointConnectionStats;
import org.jitsi.nlj.stats.RtpReceiverStats;

public abstract class RtpReceiver
    extends StatsKeepingPacketHandler
    implements EventHandler, Stoppable, EndpointConnectionStats.EndpointConnectionStatsListener
{
    /**
     * The handler which will be invoked for each RTP/RTCP packet received
     * by this receiver (after it has gone through the receiver's
     * input chain).
     */
    public abstract PacketHandler getPacketHandler();

    public abstract void setPacketHandler(PacketHandler packetHandler);

    /**
     * Enqueue an incoming packet to be processed
     */
    public abstract void enqueuePacket(PacketInfo p);

    public abstract ObjectNode debugState(DebugStateMode mode);

    /**
     * Set the SRTP transformers to be used for RTP/RTCP encryption and decryption
     */
    public abstract void setSrtpTransformers(SrtpTransformers srtpTransformers);

    public abstract RtpReceiverStats getStats();

    public abstract void tearDown();

    public abstract boolean isReceivingAudio();
    public abstract boolean isReceivingVideo();

    public abstract void addLossListener(LossListener lossListener);

    public abstract void setFeature(Features feature, boolean enabled);
    public abstract boolean isFeatureEnabled(Features feature);

    /**
     * Forcibly mute or unmute the incoming audio stream
     */
    public abstract void forceMuteAudio(boolean shouldMute);

    public abstract void forceMuteVideo(boolean shouldMute);
}
