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
package org.jitsi.nlj.transform.node;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.debug.PayloadVerificationPlugin;
import org.jitsi.nlj.util.BufferPool;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.jitsi.nlj.util.NodeStatsBlockExtensions.addMbps;
import static org.jitsi.nlj.util.NodeStatsBlockExtensions.addRatio;

/**
 * A {@link Node} which keeps track of some basic statistics, such as number of packets and bytes that passed through
 * it, and the processing time. In order to accurately compute the processing time, this class depends on its
 * subclasses calling {@link #doneProcessing(PacketInfo)} when they finish their own processing of the packet, but
 * before they pass the packet to any children. The intention is for this class to not be subclassed directly,
 * except from classes defined alongside it (but making it 'private' doesn't seem possible).
 */
public abstract class StatsKeepingNode extends Node
{
    private static final Map<String, NodeStatsBlock> globalStats = new ConcurrentHashMap<>();

    public static boolean enableStatistics = true;

    /**
     * Gets the aggregated statistics for all classes as a JSON map.
     */
    public static ObjectNode getStatsJson()
    {
        ObjectNode jsonObject = JsonNodeFactory.instance.objectNode();
        globalStats.forEach((className, stats) -> jsonObject.set(className, stats.toJson()));
        jsonObject.put("num_payload_verification_failures", PayloadVerificationPlugin.numFailures.get());
        return jsonObject;
    }

    /**
     * The time at which processing of the currently processed packet started (in nanos).
     */
    private long startTime = 0;

    /**
     * The time (in nanos) that {@link #processPacket(PacketInfo)} was first called.
     */
    private long firstPacketTime = -1;

    /**
     * The time (in nanos) that {@link #processPacket(PacketInfo)} was last called.
     */
    private long lastPacketTime = -1;

    /**
     * Keeps stats for this {@link Node}
     */
    private final NodeStats stats = new NodeStats();

    /**
     * Avoid stopping more than once.
     */
    private boolean stopped = false;

    /**
     * The key used to aggregate stats per-class. Subclasses may override this in their constructor (after calling
     * {@code super(name)}) to aggregate by instance name instead of by class name.
     */
    protected String aggregationKey;

    protected StatsKeepingNode(String name)
    {
        super(name);
        this.aggregationKey = getClass().getSimpleName();
    }

    /**
     * The function that all subclasses should implement to do the actual
     * packet processing.  A protected method is used for this so we can
     * guarantee all packets pass through this base for stat-tracking
     * purposes.
     */
    protected abstract void doProcessPacket(PacketInfo packetInfo);

    @Override
    public void processPacket(PacketInfo packetInfo)
    {
        onEntry(packetInfo);
        if (TRACE_ENABLED)
        {
            trace(() -> doProcessPacket(packetInfo));
        }
        else
        {
            doProcessPacket(packetInfo);
        }
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = new NodeStatsBlock("Node " + name + " " + hashCode());
        stats.appendTo(block);
        long numBytes = stats.numInputBytes;

        Duration duration = Duration.ofNanos(lastPacketTime - firstPacketTime);
        block.addNumber("num_input_bytes", numBytes);
        block.addNumber("duration_ms", duration.toMillis());
        addMbps(block, "throughput_mbps", "num_input_bytes", "duration_ms");
        return block;
    }

    /**
     * Get the stats that should be aggregated per-class.
     */
    protected NodeStatsBlock getNodeStatsToAggregate()
    {
        return getNodeStats();
    }

    private void onEntry(PacketInfo packetInfo)
    {
        if (enableStatistics)
        {
            startTime = System.nanoTime();
            if (firstPacketTime == -1L)
            {
                firstPacketTime = startTime;
            }

            stats.numInputPackets++;
            stats.numInputBytes += packetInfo.getPacket().getLength();

            packetInfo.addEvent(nodeEntryString);
            lastPacketTime = startTime;
        }
    }

    /**
     * Should be called by sub classes when they finish processing of the input packet, but before they call into any
     * other nodes, so that {@link Node} can keep track of its statistics.
     */
    protected void doneProcessing(PacketInfo packetInfo)
    {
        if (enableStatistics)
        {
            long processingDuration = System.nanoTime() - startTime;
            stats.totalProcessingDurationNs += processingDuration;
            stats.maxProcessingDurationNs = Math.max(stats.maxProcessingDurationNs, processingDuration);

            if (packetInfo != null)
            {
                stats.numOutputPackets++;
                packetInfo.addEvent(nodeExitString);
            }
        }
    }

    /**
     * Should be called by sub classes when they finish processing of the input packet, but before they call into any
     * other nodes, so that {@link Node} can keep track of its statistics.
     */
    protected void doneProcessing(List<PacketInfo> packetInfos)
    {
        if (enableStatistics)
        {
            long processingDuration = System.nanoTime() - startTime;
            stats.totalProcessingDurationNs += processingDuration;
            stats.maxProcessingDurationNs = Math.max(stats.maxProcessingDurationNs, processingDuration);

            stats.numOutputPackets += packetInfos.size();
            for (PacketInfo packetInfo : packetInfos)
            {
                packetInfo.addEvent(nodeExitString);
            }
        }
    }

    protected void packetDiscarded(PacketInfo packetInfo)
    {
        stats.numDiscardedPackets++;
        BufferPool.returnBuffer(packetInfo.getPacket().getBuffer());
    }

    @Override
    public void stop()
    {
        if (stopped)
        {
            return;
        }
        stopped = true;

        if (enableStatistics && stats.numInputPackets > 0)
        {
            synchronized (globalStats)
            {
                NodeStatsBlock classStats = globalStats.computeIfAbsent(aggregationKey, NodeStatsBlock::new);
                classStats.aggregate(getNodeStatsToAggregate());
            }
        }
    }

    /**
     * This just holds the stats kept by {@link StatsKeepingNode} itself.
     */
    public static class NodeStats
    {
        /**
         * Total nanoseconds spent processing packets in this node.
         */
        public long totalProcessingDurationNs = 0;
        public long numInputPackets = 0;
        public long numOutputPackets = 0;
        public long numInputBytes = 0;
        public long numDiscardedPackets = 0;
        /**
         * The longest time it took to process a single packet.
         */
        public long maxProcessingDurationNs = 0;

        private double getMaxProcessingDurationMs()
        {
            return maxProcessingDurationNs / 1_000_000.0;
        }

        public void appendTo(NodeStatsBlock block)
        {
            block.addNumber("num_input_packets", numInputPackets);
            block.addNumber("num_output_packets", numOutputPackets);
            block.addNumber("num_discarded_packets", numDiscardedPackets);
            block.addNumber("total_time_spent_ns", totalProcessingDurationNs);
            block.addCompoundValue(
                "total_time_spent_ms",
                it -> Duration.ofNanos(it.getNumberOrDefault("total_time_spent_ns", 0).longValue()).toMillis()
            );
            addRatio(block, "average_time_per_packet_ns", "total_time_spent_ns", "num_input_packets");
            addMbps(block, "processing_throughput_mbps", "num_input_bytes", "total_time_spent_ms");
            block.addNumber("max_packet_process_time_ms", getMaxProcessingDurationMs());
        }

        public void appendTo(ObjectNode json)
        {
            json.put("num_input_packets", numInputPackets);
            json.put("num_output_packets", numOutputPackets);
            json.put("num_discarded_packets", numDiscardedPackets);
            json.put("total_time_spent_ns", totalProcessingDurationNs);
            json.put("max_packet_process_time_ms", getMaxProcessingDurationMs());
        }
    }
}
