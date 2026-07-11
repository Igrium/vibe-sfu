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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsTracker.IncomingStatisticsSnapshot;
import org.jitsi.nlj.transform.node.incoming.VideoParser;

/**
 * (Deviation: upstream declares both {@code TransceiverStats} and {@code RtpReceiverStats} in a single
 * {@code TransceiverStats.kt} file; Java requires one public top-level class per file, so this is split out.)
 */
public class RtpReceiverStats
{
    private final IncomingStatisticsSnapshot incomingStats;
    private final PacketStreamStats.Snapshot packetStreamStats;
    private final VideoParser.Stats.Snapshot videoParserStats;

    public RtpReceiverStats(
        IncomingStatisticsSnapshot incomingStats,
        PacketStreamStats.Snapshot packetStreamStats,
        VideoParser.Stats.Snapshot videoParserStats)
    {
        this.incomingStats = incomingStats;
        this.packetStreamStats = packetStreamStats;
        this.videoParserStats = videoParserStats;
    }

    public IncomingStatisticsSnapshot getIncomingStats()
    {
        return incomingStats;
    }

    public PacketStreamStats.Snapshot getPacketStreamStats()
    {
        return packetStreamStats;
    }

    public VideoParser.Stats.Snapshot getVideoParserStats()
    {
        return videoParserStats;
    }

    public ObjectNode toJson()
    {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.set("incoming_stats", incomingStats.toJson());
        node.set("packet_stream_stats", packetStreamStats.toJson());
        node.set("video_parser_stats", videoParserStats.toJson());
        return node;
    }
}
