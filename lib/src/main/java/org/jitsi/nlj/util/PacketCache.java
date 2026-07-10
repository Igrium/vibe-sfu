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

package org.jitsi.nlj.util;

import org.jitsi.nlj.rtp.VideoRtpPacket;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.NodeStatsProducer;
import org.jitsi.rtp.rtp.RtpPacket;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Creates a packet cache for packets by SSRC
 */
public class PacketCache implements NodeStatsProducer
{
    public static final int MAX_CACHE_LIFETIME_MILLIS = 15000;

    /**
     * A predicate which dictates which packets to cache.
     */
    private final Predicate<RtpPacket> packetPredicate;

    /**
     * The max number of packets to cache per SSRC.
     */
    private final int size;

    private final Map<Long, RtpPacketCache> packetCaches = Collections.synchronizedMap(
        // These are the default values of initialCapacity and loadFactor - we have to set them to be able to set
        // accessOrder
        new LinkedHashMap<>(16, 0.75f, true)
    );

    private volatile boolean stopped = false;

    public PacketCache()
    {
        this(packet -> packet instanceof VideoRtpPacket, 500);
    }

    public PacketCache(Predicate<RtpPacket> packetPredicate)
    {
        this(packetPredicate, 500);
    }

    public PacketCache(Predicate<RtpPacket> packetPredicate, int size)
    {
        this.packetPredicate = packetPredicate;
        this.size = size;
    }

    public int getSize()
    {
        return size;
    }

    private RtpPacketCache getCache(long ssrc)
    {
        RtpPacketCache cache = packetCaches.computeIfAbsent(ssrc, k -> new RtpPacketCache(size));
        cache.setLastAccess();
        expireCaches(cache.getLastAccessMillis());
        return cache;
    }

    private void expireCaches(long now)
    {
        synchronized (packetCaches)
        {
            Iterator<Map.Entry<Long, RtpPacketCache>> i = packetCaches.entrySet().iterator();
            while (i.hasNext())
            {
                RtpPacketCache cache = i.next().getValue();
                if (now - cache.getLastAccessMillis() >= MAX_CACHE_LIFETIME_MILLIS)
                {
                    cache.flush();
                    i.remove();
                }
                else
                {
                    break;
                }
            }
        }
    }

    /**
     * Stores a copy of the given packet in the cache.
     */
    public boolean insert(RtpPacket packet)
    {
        return !stopped && packetPredicate.test(packet) && getCache(packet.getSsrc()).insert(packet);
    }

    /**
     * Gets a copy of the packet in the cache with the given SSRC and sequence number, if the cache contains it.
     * The instance is wrapped in an {@link ArrayCache.Container}.
     */
    public ArrayCache.Container<RtpPacket> get(long ssrc, int seqNum)
    {
        return getCache(ssrc).get(seqNum);
    }

    /**
     * Gets copies of the latest packets in the cache. Returns packets which add up to no more than
     * {@code numBytes} bytes.
     */
    public Set<RtpPacket> getMany(long ssrc, int numBytes)
    {
        return getCache(ssrc).getMany(numBytes);
    }

    /**
     * Updates the timestamp of a packet in the cache (if it is in the cache). This is used when we re-transmit a
     * packet in order to update the timestamp without re-adding the packet to the cache (which is expensive).
     */
    public void updateTimestamp(long ssrc, int seqNum, long timeAdded)
    {
        getCache(ssrc).updateTimestamp(seqNum, timeAdded);
    }

    public void stop()
    {
        stopped = true;
        synchronized (packetCaches)
        {
            packetCaches.forEach((ssrc, cache) -> cache.flush());
        }
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = new NodeStatsBlock("PacketCache");
        synchronized (packetCaches)
        {
            packetCaches.values().forEach(cache -> block.aggregate(cache.getNodeStats()));
        }
        return block;
    }

    /**
     * Implements a cache for RTP packets.
     */
    public static class RtpPacketCache extends ArrayCache<RtpPacket>
    {
        private volatile long lastAccessMillis = 0;

        private final RtpSequenceIndexTracker rtpSequenceIndexTracker = new RtpSequenceIndexTracker();

        public RtpPacketCache(int size)
        {
            this(size, true);
        }

        public RtpPacketCache(int size, boolean synchronize)
        {
            super(size, RtpPacket::clone, synchronize);
        }

        public long getLastAccessMillis()
        {
            return lastAccessMillis;
        }

        @Override
        protected void discardItem(RtpPacket item)
        {
            BufferPool.returnBuffer(item.buffer);
        }

        public void setLastAccess()
        {
            if (synchronize)
            {
                synchronized (syncRoot)
                {
                    doSetLastAccess();
                }
            }
            else
            {
                doSetLastAccess();
            }
        }

        private void doSetLastAccess()
        {
            long t = clock.millis();
            if (lastAccessMillis < t)
            {
                lastAccessMillis = t;
            }
        }

        /**
         * Gets a packet with a given RTP sequence number from the cache (clones the packet).
         */
        public Container<RtpPacket> get(int sequenceNumber)
        {
            return doGet(sequenceNumber, true);
        }

        private Container<RtpPacket> doGet(int sequenceNumber, boolean shouldCloneItem)
        {
            // Note that we use interpret() because we don't want the ROC to get out of sync because of funny
            // requests (NACKs)
            long index = rtpSequenceIndexTracker.interpret(sequenceNumber);
            // The RFC3711 tracker may produce negative numbers (example, if it is initialized with 0,
            // then 65535 is interpreted). These are invalid indexes.
            return index < 0 ? null : super.getContainer(index, shouldCloneItem);
        }

        /**
         * Gets a packet with a given RTP sequence number from the cache (does not clone the packet).
         */
        public Container<RtpPacket> peek(int sequenceNumber)
        {
            return doGet(sequenceNumber, false);
        }

        public boolean contains(int sequenceNumber)
        {
            // Note that we use interpret() because we don't want the ROC to get out of sync because of funny
            // requests (NACKs)
            long index = rtpSequenceIndexTracker.interpret(sequenceNumber);
            return super.containsIndex(index);
        }

        public boolean insert(RtpPacket rtpPacket)
        {
            long index = rtpSequenceIndexTracker.update(rtpPacket.getSequenceNumber());
            return super.insertItem(rtpPacket, index);
        }

        public void updateTimestamp(int seqNum, long timeAdded)
        {
            long index = rtpSequenceIndexTracker.interpret(seqNum);
            super.updateTimeAdded(index, timeAdded);
        }

        public Set<RtpPacket> getMany(int numBytes)
        {
            int[] bytesRemaining = { numBytes };
            Set<RtpPacket> packets = new HashSet<>();

            forEachDescending(packet -> {
                if (packet.length <= bytesRemaining[0])
                {
                    packets.add(packet.clone());
                    bytesRemaining[0] -= packet.length;
                    return true;
                }
                else
                {
                    return false;
                }
            });

            return packets;
        }
    }
}
