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
package org.jitsi.nlj.transform.node;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.stats.PacketStreamStats;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.Util;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;

/**
 * A {@link Node} which keeps track of the basic statistics for a stream of packets (packet and bit rates)
 *
 * @author Boris Grozev
 */
public class PacketStreamStatsNode extends ObserverNode
{
    private static final TimeSeriesLogger timeseriesLogger =
        TimeSeriesLogger.getTimeSeriesLogger(PacketStreamStatsNode.class);

    private final DiagnosticContext diagnosticContext;
    private final String direction;
    private final PacketStreamStats packetStreamStats;

    public PacketStreamStatsNode(DiagnosticContext diagnosticContext, String direction)
    {
        this(diagnosticContext, direction, new PacketStreamStats());
    }

    public PacketStreamStatsNode(DiagnosticContext diagnosticContext, String direction, PacketStreamStats packetStreamStats)
    {
        super("PacketStreamStats");
        this.diagnosticContext = diagnosticContext;
        this.direction = direction;
        this.packetStreamStats = packetStreamStats;
    }

    @Override
    protected void observe(PacketInfo packetInfo)
    {
        packetStreamStats.update(packetInfo.getPacket().getLength());
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }

    public PacketStreamStats.Snapshot snapshot()
    {
        PacketStreamStats.Snapshot snapshot = packetStreamStats.snapshot();
        if (timeseriesLogger.isTraceEnabled())
        {
            timeseriesLogger.trace(
                diagnosticContext.makeTimeSeriesPoint(direction + "_packet_stream_stats")
                    .addField("bitrate_bps", snapshot.getBitrateBps())
                    .addField("packet_rate", snapshot.getPacketRate())
            );
        }
        return snapshot;
    }

    public Bandwidth getBitrate()
    {
        return snapshot().getBitrate();
    }

    @Override
    public ObjectNode statsJson()
    {
        return Util.appendAll(super.statsJson(), snapshot().toJson());
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock stats = super.getNodeStats();
        PacketStreamStats.Snapshot snapshot = snapshot();
        stats.addNumber("bitrate_bps", snapshot.getBitrateBps());
        stats.addNumber("packet_rate", snapshot.getPacketRate());
        stats.addNumber("bytes_sent", snapshot.getBytes());
        stats.addNumber("packets_sent", snapshot.getPackets());
        return stats;
    }

    /**
     * Creates a new {@link Node} instance which shares the same {@link #packetStreamStats}. Useful when we want to
     * add nodes to different branches of a {@link Node} tree.
     */
    public PacketStreamStatsNode createNewNode()
    {
        return new PacketStreamStatsNode(diagnosticContext, direction, packetStreamStats);
    }
}
