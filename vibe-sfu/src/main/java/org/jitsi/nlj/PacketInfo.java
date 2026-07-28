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

import org.jitsi.nlj.format.PayloadType;
import org.jitsi.rtp.Packet;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * {@link PacketInfo} is a wrapper around a {@link Packet} instance to be passed through
 * a pipeline.  Since the {@link Packet} can change as it moves through the pipeline
 * (as it is parsed into different types), the wrapping {@link PacketInfo} stays consistent
 * and allows for metadata to be passed along with a packet.
 */
public class PacketInfo
{
    private static final Logger logger = new LoggerImpl(PacketInfo.class.getName());

    /**
     * Whether the packet timeline is enabled. Config default: {@code jmt.debug.packet-timeline.enabled = false}.
     */
    private static final boolean enableTimeline = false;

    /**
     * If this is enabled all Nodes will verify that the payload didn't unexpectedly change. This is expensive.
     * Config default: {@code jmt.debug.payload-verification.enabled = false}.
     */
    public static boolean enablePayloadVerification = false;

    static
    {
        if (enableTimeline)
        {
            logger.info("Packet timeline is enabled.");
        }
        if (enablePayloadVerification)
        {
            logger.info("Payload verification is enabled.");
        }
    }

    private Packet packet;

    /** The original length of the packet, i.e. before decryption.  Stays unchanged even if the packet is updated. */
    private final int originalLength;

    private final EventTimeline timeline;

    /**
     * An explicit tag for when this packet was originally received (assuming it
     * was an incoming packet and not one created by jvb itself).
     */
    private Instant receivedTime;

    /** Whether the packet originally had cryptex RTP header extensions. */
    private boolean originalHadCryptex = false;

    /**
     * Whether this packet has been recognized to contain only shouldDiscard.
     */
    private boolean shouldDiscard = false;

    /**
     * The ID of the endpoint associated with this packet (i.e. the source endpoint).
     */
    private String endpointId;

    /**
     * Whether this packet indicates a point in which its stream's layering changed, in
     * a way that indicates that bitrate allocation may need to be recomputed.
     */
    private boolean layeringChanged = false;

    private PayloadType payloadType;

    /**
     * The payload verification string for the packet, or 'null' if payload verification is disabled. Calculating
     * it is expensive, thus we only do it when the flag is enabled.
     */
    private String payloadVerification;

    /**
     * Information about whether this packet is used for probing by the transport-cc engine.
     * The type is internal to that object.
     */
    private Object probingInfo;

    /**
     * The origin of the packet, used for tracking the sources of media being routed.
     */
    private PacketOrigin packetOrigin = PacketOrigin.Misc;

    /**
     * The list of pending actions, or {@code null} if none.
     */
    private ArrayList<Consumer<PacketInfo>> onSentActions;

    public PacketInfo(Packet packet)
    {
        this(packet, packet.getLength(), enableTimeline ? new EventTimeline() : null);
    }

    public PacketInfo(Packet packet, int originalLength)
    {
        this(packet, originalLength, enableTimeline ? new EventTimeline() : null);
    }

    public PacketInfo(Packet packet, int originalLength, EventTimeline timeline)
    {
        this.packet = packet;
        this.originalLength = originalLength;
        this.timeline = timeline;
        this.payloadVerification = enablePayloadVerification ? packet.getPayloadVerification() : null;
    }

    public Packet getPacket()
    {
        return packet;
    }

    public void setPacket(Packet packet)
    {
        this.packet = packet;
    }

    public int getOriginalLength()
    {
        return originalLength;
    }

    public EventTimeline getTimeline()
    {
        return timeline;
    }

    public Instant getReceivedTime()
    {
        return receivedTime;
    }

    public void setReceivedTime(Instant receivedTime)
    {
        this.receivedTime = receivedTime;
        if (timeline != null && timeline.getReferenceTime() == null)
        {
            timeline.setReferenceTime(receivedTime);
        }
    }

    public boolean isOriginalHadCryptex()
    {
        return originalHadCryptex;
    }

    public void setOriginalHadCryptex(boolean originalHadCryptex)
    {
        this.originalHadCryptex = originalHadCryptex;
    }

    public boolean isShouldDiscard()
    {
        return shouldDiscard;
    }

    public void setShouldDiscard(boolean shouldDiscard)
    {
        this.shouldDiscard = shouldDiscard;
    }

    public String getEndpointId()
    {
        return endpointId;
    }

    public void setEndpointId(String endpointId)
    {
        this.endpointId = endpointId;
    }

    public boolean isLayeringChanged()
    {
        return layeringChanged;
    }

    public void setLayeringChanged(boolean layeringChanged)
    {
        this.layeringChanged = layeringChanged;
    }

    public PayloadType getPayloadType()
    {
        return payloadType;
    }

    public void setPayloadType(PayloadType payloadType)
    {
        this.payloadType = payloadType;
    }

    public String getPayloadVerification()
    {
        return payloadVerification;
    }

    public void setPayloadVerification(String payloadVerification)
    {
        this.payloadVerification = payloadVerification;
    }

    public Object getProbingInfo()
    {
        return probingInfo;
    }

    public void setProbingInfo(Object probingInfo)
    {
        this.probingInfo = probingInfo;
    }

    public PacketOrigin getPacketOrigin()
    {
        return packetOrigin;
    }

    public void setPacketOrigin(PacketOrigin packetOrigin)
    {
        this.packetOrigin = packetOrigin;
    }

    /**
     * Re-calculates the expected payload verification string. This should be called any time that the code
     * intentionally modifies the packet in a way that could change the verification string (for example, re-creates
     * it with a new type (parsing), or intentionally modifies the payload (SRTP)).
     */
    public void resetPayloadVerification()
    {
        payloadVerification = enablePayloadVerification ? packet.getPayloadVerification() : null;
    }

    /**
     * Get the contained packet cast to {@code ExpectedPacketType}
     */
    @SuppressWarnings("unchecked")
    public <ExpectedPacketType extends Packet> ExpectedPacketType packetAs()
    {
        return (ExpectedPacketType) packet;
    }

    /**
     * Create a deep clone of this PacketInfo (both the contained packet and the metadata map
     * will be copied for the cloned PacketInfo).
     */
    @SuppressWarnings("unchecked")
    public PacketInfo clone()
    {
        PacketInfo clone = new PacketInfo(packet.clone(), originalLength, timeline != null ? timeline.clone() : null);
        clone.receivedTime = receivedTime;
        clone.originalHadCryptex = originalHadCryptex;
        clone.shouldDiscard = shouldDiscard;
        clone.endpointId = endpointId;
        clone.payloadType = payloadType;
        clone.layeringChanged = layeringChanged;
        clone.payloadVerification = payloadVerification;
        clone.probingInfo = probingInfo;
        clone.packetOrigin = packetOrigin;
        clone.onSentActions = onSentActions != null ? (ArrayList<Consumer<PacketInfo>>) onSentActions.clone() : null;
        return clone;
    }

    public void addEvent(String desc)
    {
        if (timeline != null)
        {
            timeline.addEvent(desc);
        }
    }

    /**
     * Add an action to be performed when the packet is sent (i.e. when this packet's
     * {@link #sent()} method is called).
     *
     * If this {@link PacketInfo} object is cloned, the action will be called for every
     * cloned instance.  If packet is dropped (i.e. {@link #sent()} is never called), the
     * action will not be called.
     */
    public void onSent(Consumer<PacketInfo> action)
    {
        synchronized (this)
        {
            if (onSentActions == null)
            {
                onSentActions = new ArrayList<>(1);
            }
            onSentActions.add(action);
        }
    }

    /**
     * Invoke any actions previously registered with this {@link PacketInfo}'s {@link #onSent} method.  This should
     * be called just before, or after, this packet is sent.
     */
    public void sent()
    {
        List<Consumer<PacketInfo>> actions = Collections.emptyList();
        synchronized (this)
        {
            if (onSentActions != null)
            {
                actions = onSentActions;
                onSentActions = null;
            }
            else
            {
                return;
            }
        }
        for (Consumer<PacketInfo> action : actions)
        {
            action.accept(this);
        }
    }

    /**
     * This is a specialization which makes it easier to operate on lists of {@link PacketInfo} when the caller
     * wants to treat the contained {@link Packet} as a specific packet type.  This method iterates over the
     * iterable of {@link PacketInfo}s and calls the given consumer with the {@link PacketInfo} instance and the
     * contained {@link Packet} instance, cast as {@code ExpectedPacketType}.  This will throw if the cast attempt
     * is unsuccessful.
     */
    @SuppressWarnings("unchecked")
    public static <ExpectedPacketType> void forEachAs(Iterable<PacketInfo> packetInfos, BiConsumer<PacketInfo, ExpectedPacketType> action)
    {
        for (PacketInfo element : packetInfos)
        {
            action.accept(element, (ExpectedPacketType) element.packet);
        }
    }
}
