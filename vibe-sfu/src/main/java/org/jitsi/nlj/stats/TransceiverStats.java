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
import org.jitsi.nlj.rtp.TransportCcEngine;
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsTracker.OutgoingStatisticsSnapshot;

public class TransceiverStats
{
    private final EndpointConnectionStats.Snapshot endpointConnectionStats;
    private final RtpReceiverStats rtpReceiverStats;
    private final OutgoingStatisticsSnapshot outgoingStats;
    private final PacketStreamStats.Snapshot outgoingPacketStreamStats;
    private final TransportCcEngine.StatisticsSnapshot tccEngineStats;

    public TransceiverStats(
        EndpointConnectionStats.Snapshot endpointConnectionStats,
        RtpReceiverStats rtpReceiverStats,
        OutgoingStatisticsSnapshot outgoingStats,
        PacketStreamStats.Snapshot outgoingPacketStreamStats,
        TransportCcEngine.StatisticsSnapshot tccEngineStats)
    {
        this.endpointConnectionStats = endpointConnectionStats;
        this.rtpReceiverStats = rtpReceiverStats;
        this.outgoingStats = outgoingStats;
        this.outgoingPacketStreamStats = outgoingPacketStreamStats;
        this.tccEngineStats = tccEngineStats;
    }

    public EndpointConnectionStats.Snapshot getEndpointConnectionStats()
    {
        return endpointConnectionStats;
    }

    public RtpReceiverStats getRtpReceiverStats()
    {
        return rtpReceiverStats;
    }

    public OutgoingStatisticsSnapshot getOutgoingStats()
    {
        return outgoingStats;
    }

    public PacketStreamStats.Snapshot getOutgoingPacketStreamStats()
    {
        return outgoingPacketStreamStats;
    }

    public TransportCcEngine.StatisticsSnapshot getTccEngineStats()
    {
        return tccEngineStats;
    }

    public ObjectNode toJson()
    {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.set("endpoint_connection_stats", endpointConnectionStats.toJson());
        node.set("rtp_receiver_stats", rtpReceiverStats.toJson());
        node.set("outgoing_stats", outgoingStats.toJson());
        node.set("outgoing_packet_stream_stats", outgoingPacketStreamStats.toJson());
        node.set("tcc_engine_stats", tccEngineStats.toJson());
        return node;
    }
}
