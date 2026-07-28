/*
 * Copyright @ 2019 - present 8x8 Inc
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
package org.jitsi.nlj.transform.node;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.PacketOrigin;
import org.jitsi.nlj.format.AudioRedPayloadType;
import org.jitsi.nlj.rtp.AudioRtpPacket;
import org.jitsi.nlj.rtp.RedAudioRtpPacket;
import org.jitsi.nlj.rtp.RtpExtensionType;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.util.ArrayCache;
import org.jitsi.nlj.util.BufferPool;
import org.jitsi.nlj.util.PacketCache;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.extensions.ByteArrayBufferExtensions;
import org.jitsi.rtp.rtp.RedundancyBlockHeader;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.rtp.header_extensions.AudioLevelHeaderExtension;
import org.jitsi.rtp.util.RtpUtils;
import org.jitsi.utils.logging2.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AudioRedHandler extends MultipleOutputTransformerNode
{
    private static final Config config = new Config();

    private final Logger logger;
    private final Stats stats = new Stats();

    private volatile Integer audioLevelExtId;
    private volatile Integer redPayloadType;

    private final Map<Long, SsrcRedHandler> ssrcRedHandlers = new HashMap<>();

    public AudioRedHandler(ReadOnlyStreamInformationStore streamInformationStore, Logger parentLogger)
    {
        super("RedHandler");
        this.logger = parentLogger.createChildLogger(getClass().getName());

        streamInformationStore.onRtpPayloadTypesChanged(payloadTypes -> {
            redPayloadType = payloadTypes.values().stream()
                .filter(pt -> pt instanceof AudioRedPayloadType)
                .map(pt -> (int) pt.getPt())
                .findFirst()
                .orElse(null);
        });
        streamInformationStore.onRtpExtensionMapping(RtpExtensionType.SSRC_AUDIO_LEVEL, id -> audioLevelExtId = id);
    }

    @Override
    protected List<PacketInfo> transform(PacketInfo packetInfo)
    {
        if (!(packetInfo.getPacket() instanceof AudioRtpPacket))
        {
            return Collections.singletonList(packetInfo);
        }
        AudioRtpPacket audioPacket = (AudioRtpPacket) packetInfo.getPacket();

        SsrcRedHandler ssrcHandler = ssrcRedHandlers.computeIfAbsent(audioPacket.getSsrc(), k -> new SsrcRedHandler());

        if (audioPacket instanceof RedAudioRtpPacket)
        {
            return ssrcHandler.transformRed(packetInfo);
        }
        else
        {
            return Collections.singletonList(ssrcHandler.transformAudio(packetInfo));
        }
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock nodeStats = super.getNodeStats();
        nodeStats.addString("red_payload_type", redPayloadType == null ? "null" : redPayloadType.toString());
        nodeStats.addString("audio_level_ext_id", audioLevelExtId == null ? "null" : audioLevelExtId.toString());
        nodeStats.addString("policy", config.policy.toString());
        nodeStats.addString("distance", config.distance.toString());
        nodeStats.addBoolean("vad_only", config.vadOnly);

        nodeStats.addNumber("red_packets_decapsulated", stats.redPacketsDecapsulated);
        nodeStats.addNumber("red_packets_forwarded", stats.redPacketsForwarded);
        nodeStats.addNumber("audio_packets_encapsulated", stats.audioPacketsEncapsulated);
        nodeStats.addNumber("audio_packets_forwarded", stats.audioPacketsForwarded);
        nodeStats.addNumber("lost_packets_recovered", stats.lostPacketsRecovered);
        nodeStats.addNumber("redundancy_packets_added", stats.redundancyPacketsAdded);
        nodeStats.addNumber("invalid_red_packets_dropped", stats.invalidRedPacketsDropped);
        return nodeStats;
    }

    @Override
    public ObjectNode statsJson()
    {
        ObjectNode json = super.statsJson();
        json.put("red_packets_decapsulated", stats.redPacketsDecapsulated);
        json.put("red_packets_forwarded", stats.redPacketsForwarded);
        json.put("audio_packets_encapsulated", stats.audioPacketsEncapsulated);
        json.put("audio_packets_forwarded", stats.audioPacketsForwarded);
        json.put("lost_packets_recovered", stats.lostPacketsRecovered);
        json.put("redundancy_packets_added", stats.redundancyPacketsAdded);
        json.put("invalid_red_packets_dropped", stats.invalidRedPacketsDropped);
        return json;
    }

    @Override
    public void stop()
    {
        super.stop();
        ssrcRedHandlers.values().forEach(SsrcRedHandler::stop);
        ssrcRedHandlers.clear();
    }

    /**
     * Handler for a specific stream (SSRC)
     */
    private final class SsrcRedHandler
    {
        /**
         * Saves audio (non-RED) packets that we've sent. Packets are cloned on insert and the inserted copies are
         * owned by the cache, meaning that the cache is responsible for returning their buffers to the pool.
         * It needs to be synchronized because {@link #stop} and {@link #transformAudio} may run in different
         * threads.
         */
        private final PacketCache.RtpPacketCache sentAudioCache = new PacketCache.RtpPacketCache(20, true);

        /**
         * Process an incoming audio packet. It is either forwarded as it is, or encapsulated in RED with previous
         * packets optionally added as redundancy.
         */
        PacketInfo transformAudio(PacketInfo packetInfo)
        {
            // Whether we need to add RED encapsulation
            boolean encapsulate = config.policy == RedPolicy.PROTECT_ALL;

            Integer redPt = redPayloadType;
            if (redPt == null || !encapsulate)
            {
                stats.audioPacketForwarded();
                return packetInfo;
            }

            AudioRtpPacket audioRtpPacket = packetInfo.packetAs();
            sentAudioCache.insert(audioRtpPacket);

            List<RtpPacket> redundancy = new ArrayList<>();
            int seq = audioRtpPacket.getSequenceNumber();

            if (config.distance == RedDistance.ONE)
            {
                RtpPacket secondary = getPacketToProtect(
                    RtpUtils.applySequenceNumberDelta(seq, -1),
                    audioRtpPacket.getTimestamp(),
                    config.vadOnly);
                if (secondary != null)
                {
                    redundancy.add(secondary);
                    stats.redundancyPacketAdded();
                }
            }
            else if (config.distance == RedDistance.TWO)
            {
                RtpPacket secondary = getPacketToProtect(
                    RtpUtils.applySequenceNumberDelta(seq, -1),
                    audioRtpPacket.getTimestamp(),
                    false);
                if (secondary != null)
                {
                    // With distance 2 we only add the tertiary packet when there is a secondary available
                    // (regardless of secondary's VAD). This guarantees that the sequence numbers of the
                    // redundancy packets always directly proceed the primary packet, i.e. that we don't encode
                    // a packet with primary seq=N and a single redundancy with seq=N-2. This is be important
                    // when the receiver of the RED stream is another jitsi-videobridge instance (via Octo),
                    // which makes that assumption about the stream it receives.
                    RtpPacket tertiary = getPacketToProtect(
                        RtpUtils.applySequenceNumberDelta(seq, -2),
                        audioRtpPacket.getTimestamp(),
                        config.vadOnly);
                    if (tertiary != null)
                    {
                        redundancy.add(tertiary);
                        stats.redundancyPacketAdded();
                        redundancy.add(secondary);
                        stats.redundancyPacketAdded();
                    }
                    else if (!config.vadOnly || hasVad(secondary))
                    {
                        // If there's no tertiary encode the secondary alone, but this time check its VAD.
                        redundancy.add(secondary);
                        stats.redundancyPacketAdded();
                    }
                }
            }

            RedAudioRtpPacket redPacket = RedAudioRtpPacket.builder.build(redPt, audioRtpPacket, redundancy);
            packetInfo.setPacket(redPacket);

            // We replaced packetInfo.packet with our newly allocated packet, so the original can now be returned.
            // We do not return the redundancy packets, because we only peek()ed at them from the cache.
            BufferPool.returnBuffer(audioRtpPacket.buffer);

            stats.audioPacketEncapsulated();
            return packetInfo;
        }

        private RtpPacket getPacketToProtect(int seq, long primaryTimestamp, boolean vadOnly)
        {
            // All of the transform pipeline runs in a single thread, and we only use the packet momentarily to make a
            // copy into a new RED packet, so it's safe to just peek() at it.
            ArrayCache.Container<RtpPacket> container = sentAudioCache.peek(seq);
            if (container != null && container.item != null)
            {
                RtpPacket candidate = container.item;
                // In vad-only mode, we only add redundancy for packets that have an audio level extension with the
                // VAD bit set.
                if (!vadOnly || hasVad(candidate))
                {
                    // Don't attempt to encode packets with timestamp diff that's too large to encode (happens with
                    // 400ms opus frames e.g. when DTX is used)
                    if (RtpUtils.getTimestampDiffAsInt(primaryTimestamp, candidate.getTimestamp()) <=
                        RedundancyBlockHeader.MAX_TIMESTAMP_OFFSET)
                    {
                        return candidate;
                    }
                }
            }
            return null;
        }

        private boolean hasVad(RtpPacket packet)
        {
            Integer extId = audioLevelExtId;
            if (extId == null)
            {
                return false;
            }
            RtpPacket.HeaderExtension ext = packet.getHeaderExtension(extId);
            return ext != null && AudioLevelHeaderExtension.getVad(ext);
        }

        void stop()
        {
            sentAudioCache.flush();
        }

        /**
         * Process an incoming RED packet. Depending on the configured policy and whether the receiver supports the
         * RED format, it is either forwarded as it is or it is "stripped" to its primary encoding, with redundancy
         * blocks being read if there are non-received packets.
         */
        List<PacketInfo> transformRed(PacketInfo packetInfo)
        {
            // Whether we need to strip the RED encapsulation
            boolean strip = redPayloadType == null || config.policy == RedPolicy.STRIP;

            if (!strip)
            {
                stats.redPacketForwarded();
                return Collections.singletonList(packetInfo);
            }

            List<PacketInfo> result = new ArrayList<>();
            RedAudioRtpPacket redPacket = packetInfo.packetAs();

            int seq = redPacket.getSequenceNumber();
            int prev = RtpUtils.applySequenceNumberDelta(seq, -1);
            int prev2 = RtpUtils.applySequenceNumberDelta(seq, -2);
            boolean prevMissing = !sentAudioCache.contains(prev);
            boolean prev2Missing = !sentAudioCache.contains(prev2);

            try
            {
                if (prevMissing || prev2Missing)
                {
                    for (AudioRtpPacket it : redPacket.removeRedAndGetRedundancyPackets())
                    {
                        if ((it.getSequenceNumber() == prev && prevMissing) ||
                            (it.getSequenceNumber() == prev2 && prev2Missing))
                        {
                            PacketInfo recovered = new PacketInfo(it);
                            recovered.setPacketOrigin(PacketOrigin.Synthesized);
                            result.add(recovered);
                            stats.lostPacketRecovered();
                        }
                        sentAudioCache.insert(it);
                    }
                }
                else
                {
                    redPacket.removeRed();
                }
            }
            catch (IllegalArgumentException e)
            {
                logger.warn(
                    "Dropping invalid RED packet from ep=" + packetInfo.getEndpointId() + " (" + e.getMessage() +
                        "): " + redPacket + ". Contents (50B): " + ByteArrayBufferExtensions.toHex(redPacket, 50));
                stats.invalidRedPacketDropped();
                return result;
            }

            stats.redPacketDecapsulated();
            packetInfo.setPacket(redPacket.toOtherType(AudioRtpPacket::new));

            // It's possible we already forwarded the primary packet if we recovered it from a previously received
            // packet.
            if (!sentAudioCache.contains(seq))
            {
                sentAudioCache.insert(packetInfo.packetAs());
                result.add(packetInfo);
            }

            return result;
        }
    }

    /**
     * (Deviation: upstream's top-level {@code data class Stats} is nested here as a plain mutable-counter class,
     * to avoid introducing a generically-named top-level {@code Stats} class in this package.)
     */
    private static final class Stats
    {
        int redPacketsDecapsulated = 0;
        int redPacketsForwarded = 0;
        int invalidRedPacketsDropped = 0;
        int audioPacketsEncapsulated = 0;
        int audioPacketsForwarded = 0;
        int lostPacketsRecovered = 0;
        int redundancyPacketsAdded = 0;

        void redPacketDecapsulated()
        {
            redPacketsDecapsulated++;
        }

        void redPacketForwarded()
        {
            redPacketsForwarded++;
        }

        void invalidRedPacketDropped()
        {
            invalidRedPacketsDropped++;
        }

        void audioPacketEncapsulated()
        {
            audioPacketsEncapsulated++;
        }

        void audioPacketForwarded()
        {
            audioPacketsForwarded++;
        }

        void lostPacketRecovered()
        {
            lostPacketsRecovered++;
        }

        void redundancyPacketAdded()
        {
            redundancyPacketsAdded++;
        }
    }

    /**
     * Replaces the upstream {@code jitsi-metaconfig}-based config. Since this port does not use
     * {@code jitsi-metaconfig}/HOCON, this hardcodes the upstream defaults (see {@code reference.conf}'s
     * {@code jmt.audio.red} section).
     */
    private static final class Config
    {
        final RedPolicy policy = RedPolicy.NOOP;
        final RedDistance distance = RedDistance.TWO;
        final boolean vadOnly = true;
    }
}
