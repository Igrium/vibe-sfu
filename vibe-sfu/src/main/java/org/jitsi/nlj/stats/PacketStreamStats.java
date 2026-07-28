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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.utils.stats.RateTracker;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps tracks of the basic statistics for a stream of packets.
 *
 * @author Boris Grozev
 */
public class PacketStreamStats
{
    /**
     * TODO(port): upstream sizes these trackers via the not-yet-ported {@code BitrateCalculator.createBitrateTracker()}
     * / {@code createRateTracker()}, which in turn depend on the BWE engine config (see the same note in
     * {@link org.jitsi.nlj.RtpLayerDesc}). Hardcode the GoogleCc2 defaults from {@code reference.conf} (1s window,
     * 20ms bucket) here instead, until {@code BitrateCalculator} is ported.
     */
    private static final Duration DEFAULT_WINDOW_SIZE = Duration.ofSeconds(1);
    private static final Duration DEFAULT_BUCKET_SIZE = Duration.ofMillis(20);

    /**
     * The bitrate in bits per second.
     */
    private final org.jitsi.nlj.util.BitrateTracker bitrate =
        new org.jitsi.nlj.util.BitrateTracker(DEFAULT_WINDOW_SIZE, DEFAULT_BUCKET_SIZE);

    /**
     * The packet rate in packets per second.
     */
    private final RateTracker packetRate = new RateTracker(DEFAULT_WINDOW_SIZE, DEFAULT_BUCKET_SIZE);

    /**
     * Total total number of bytes.
     */
    private final AtomicLong bytes = new AtomicLong();

    /**
     * Total total number of packets.
     */
    private final AtomicLong packets = new AtomicLong();

    public void update(int lengthInBytes)
    {
        long now = System.currentTimeMillis();

        bitrate.update(DataSize.ofBytes(lengthInBytes), now);
        bytes.addAndGet(lengthInBytes);

        packetRate.update(1, now);
        packets.incrementAndGet();
    }

    public Snapshot snapshot()
    {
        long now = System.currentTimeMillis();
        return new Snapshot(bitrate.getRate(now), packetRate.getRate(now), bytes.get(), packets.get());
    }

    public static class Snapshot
    {
        /**
         * The current bitrate in bits per second.
         */
        private final Bandwidth bitrate;

        /**
         * The current packet rate in packets per second.
         */
        private final long packetRate;

        /**
         * Total number of bytes.
         */
        private final long bytes;

        /**
         * Total number of packets.
         */
        private final long packets;

        public Snapshot(Bandwidth bitrate, long packetRate, long bytes, long packets)
        {
            this.bitrate = bitrate;
            this.packetRate = packetRate;
            this.bytes = bytes;
            this.packets = packets;
        }

        public Bandwidth getBitrate()
        {
            return bitrate;
        }

        public long getPacketRate()
        {
            return packetRate;
        }

        public long getBytes()
        {
            return bytes;
        }

        public long getPackets()
        {
            return packets;
        }

        public com.fasterxml.jackson.databind.node.ObjectNode toJson()
        {
            com.fasterxml.jackson.databind.node.ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("bitrate_bps", bitrate.getBps());
            node.put("packetrate", packetRate);
            node.put("total_bytes", bytes);
            node.put("total_packets", packets);
            return node;
        }

        /**
         * Expose {@link #bitrate} as a {@code long} (mirrors upstream's Java-friendly accessor, since
         * {@code Bandwidth} was an inline class in Kotlin).
         */
        public long getBitrateBps()
        {
            return bitrate.getBps();
        }
    }
}
