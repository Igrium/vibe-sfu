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
package org.jitsi.videobridge.dcsctp;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.dcsctp4j.DcSctpMessage;
import org.jitsi.dcsctp4j.DcSctpOptions;
import org.jitsi.dcsctp4j.DcSctpSocketCallbacks;
import org.jitsi.dcsctp4j.DcSctpSocketFactory;
import org.jitsi.dcsctp4j.DcSctpSocketInterface;
import org.jitsi.dcsctp4j.Metrics;
import org.jitsi.dcsctp4j.SendOptions;
import org.jitsi.dcsctp4j.SendStatus;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.rtp.Packet;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.sctp.SctpConfig;

public class DcSctpTransport
{
    /* Copying value set by Chrome's dcsctp_transport. */
    public static final long DEFAULT_MAX_TIMER_DURATION = 3000L;

    public static final int DEFAULT_SCTP_PORT = 5000;

    private static volatile DcSctpSocketFactory factoryInstance;
    private static final Object factoryLock = new Object();

    private static DcSctpSocketFactory factory()
    {
        DcSctpSocketFactory result = factoryInstance;
        if (result == null)
        {
            synchronized (factoryLock)
            {
                result = factoryInstance;
                if (result == null)
                {
                    if (!SctpConfig.config.enabled())
                    {
                        throw new IllegalStateException("SCTP is disabled in configuration");
                    }
                    result = new DcSctpSocketFactory();
                    factoryInstance = result;
                }
            }
        }
        return result;
    }

    private static volatile DcSctpOptions defaultSocketOptions;
    private static final Object defaultSocketOptionsLock = new Object();

    public static DcSctpOptions getDefaultSocketOptions()
    {
        DcSctpOptions result = defaultSocketOptions;
        if (result == null)
        {
            synchronized (defaultSocketOptionsLock)
            {
                result = defaultSocketOptions;
                if (result == null)
                {
                    if (!SctpConfig.config.enabled())
                    {
                        throw new IllegalStateException("SCTP is disabled in configuration");
                    }
                    result = new DcSctpOptions();
                    result.setMaxTimerBackoffDuration(DEFAULT_MAX_TIMER_DURATION);
                    // Because we're making retransmits faster, we need to allow unlimited retransmits
                    // or SCTP can time out (which we don't handle).  Peer connection timeouts are handled at
                    // a higher layer.
                    result.setMaxRetransmissions(null);
                    result.setMaxInitRetransmits(null);
                    defaultSocketOptions = result;
                }
            }
        }
        return result;
    }

    private static volatile SendOptions defaultSendOptions;
    private static final Object defaultSendOptionsLock = new Object();

    public static SendOptions getDefaultSendOptions()
    {
        SendOptions result = defaultSendOptions;
        if (result == null)
        {
            synchronized (defaultSendOptionsLock)
            {
                result = defaultSendOptions;
                if (result == null)
                {
                    if (!SctpConfig.config.enabled())
                    {
                        throw new IllegalStateException("SCTP is disabled in configuration");
                    }
                    result = new SendOptions();
                    defaultSendOptions = result;
                }
            }
        }
        return result;
    }

    public final String name;
    public final Logger logger;
    private final Object lock = new Object();
    private DcSctpSocketInterface socket;

    public DcSctpTransport(String name, Logger parentLogger)
    {
        this.name = name;
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    public void start(DcSctpSocketCallbacks callbacks)
    {
        start(callbacks, getDefaultSocketOptions());
    }

    public void start(DcSctpSocketCallbacks callbacks, DcSctpOptions options)
    {
        synchronized (lock)
        {
            socket = factory().create(name, callbacks, null, options);
        }
    }

    public void handleIncomingSctp(PacketInfo packetInfo)
    {
        Packet packet = packetInfo.getPacket();
        synchronized (lock)
        {
            if (socket != null)
            {
                socket.receivePacket(packet.getBuffer(), packet.getOffset(), packet.getLength());
            }
        }
    }

    public void stop()
    {
        synchronized (lock)
        {
            if (socket != null)
            {
                socket.close();
            }
            socket = null;
        }
    }

    public void connect()
    {
        synchronized (lock)
        {
            if (socket != null)
            {
                socket.connect();
            }
        }
    }

    public SendStatus send(DcSctpMessage message, SendOptions options)
    {
        synchronized (lock)
        {
            return socket != null ? socket.send(message, options) : SendStatus.kErrorShuttingDown;
        }
    }

    public void handleTimeout(long timeoutId)
    {
        synchronized (lock)
        {
            if (socket != null)
            {
                socket.handleTimeout(timeoutId);
            }
        }
    }

    public ObjectNode getDebugState()
    {
        Metrics metrics;
        synchronized (lock)
        {
            metrics = socket != null ? socket.getMetrics() : null;
        }
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        if (metrics != null)
        {
            o.put("tx_packets_count", metrics.getTxPacketsCount());
            o.put("tx_messages_count", metrics.getTxMessagesCount());
            o.put("rtx_packets_count", metrics.getRtxPacketsCount());
            o.put("rtx_bytes_count", metrics.getRtxBytesCount());
            o.put("cwnd_bytes", metrics.getCwndBytes());
            o.put("srtt_ms", metrics.getSrttMs());
            o.put("unack_data_count", metrics.getUnackDataCount());
            o.put("rx_packets_count", metrics.getRxPacketsCount());
            o.put("rx_messages_count", metrics.getRxMessagesCount());
            o.put("peer_rwnd_bytes", metrics.getPeerRwndBytes());
            o.put("peer_implementation", metrics.getPeerImplementation().name());
            o.put("uses_message_interleaving", metrics.usesMessageInterleaving());
            o.put("uses_zero_checksum", metrics.usesZeroChecksum());
            o.put("negotiated_maximum_incoming_streams", metrics.getNegotiatedMaximumIncomingStreams());
            o.put("negotiated_maximum_outgoing_streams", metrics.getNegotiatedMaximumOutgoingStreams());
        }
        return o;
    }
}
