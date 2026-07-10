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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.srtp.AbstractSrtpTransformer;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.srtp.SrtpErrorStatus;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class SrtpTransformerNode extends MultipleOutputTransformerNode
{
    /**
     * The transformer to use to protect or unprotect a single SRT(C)P packet.
     */
    private volatile AbstractSrtpTransformer<?> transformer;

    /**
     * We'll cache all packets that come through before {@link #transformer} gets set so that we don't lose any
     * packets at the beginning (likely a keyframe)
     */
    private final List<PacketInfo> cachedPackets = new ArrayList<>();

    private long firstPacketReceivedTimestamp = -1L;
    private long firstPacketForwardedTimestamp = -1L;

    /**
     * How many packets, total, we put into the cache while waiting for the transformer
     * (this includes packets which may have been dropped due to the cache filling up)
     */
    private int numCachedPackets = 0;

    protected SrtpTransformerNode(String name)
    {
        super(name);
    }

    public AbstractSrtpTransformer<?> getTransformer()
    {
        return transformer;
    }

    public void setTransformer(AbstractSrtpTransformer<?> transformer)
    {
        this.transformer = transformer;
    }

    /**
     * (Deviation: upstream's {@code AbstractSrtpTransformer.transform(PacketInfo)} declares {@code throws
     * GeneralSecurityException} in this port (see {@code AbstractSrtpTransformer.java}); Kotlin ignores checked
     * exceptions entirely, so upstream calls it with no try/catch. Wrap in a {@code RuntimeException} here to
     * preserve that behavior -- this path is not expected to be hit in practice.)
     */
    private static SrtpErrorStatus doTransform(AbstractSrtpTransformer<?> transformer, PacketInfo packetInfo)
    {
        try
        {
            return transformer.transform(packetInfo);
        }
        catch (GeneralSecurityException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Transforms a list of packets using {@code transformer}.
     *
     * We pass it as an arg (rather than referencing it from the field) so that a single read of the (volatile)
     * field is used consistently, mirroring the upstream Kotlin smart-cast.
     */
    private List<PacketInfo> transformList(List<PacketInfo> packetInfos, AbstractSrtpTransformer<?> transformer)
    {
        List<PacketInfo> transformedPackets = new ArrayList<>();
        for (PacketInfo packetInfo : packetInfos)
        {
            SrtpErrorStatus err = doTransform(transformer, packetInfo);
            if (err == SrtpErrorStatus.OK)
            {
                transformedPackets.add(packetInfo);
            }
            else
            {
                packetDiscarded(packetInfo);
            }
            countErrorStatus(err);
        }
        return transformedPackets;
    }

    @Override
    protected List<PacketInfo> transform(PacketInfo packetInfo)
    {
        if (firstPacketReceivedTimestamp == -1L)
        {
            firstPacketReceivedTimestamp = System.currentTimeMillis();
        }
        AbstractSrtpTransformer<?> transformer = this.transformer;
        if (transformer != null)
        {
            if (firstPacketForwardedTimestamp == -1L)
            {
                firstPacketForwardedTimestamp = System.currentTimeMillis();
            }
            List<PacketInfo> outPackets;
            synchronized (cachedPackets)
            {
                if (!cachedPackets.isEmpty())
                {
                    cachedPackets.add(packetInfo);
                    outPackets = transformList(cachedPackets, transformer);
                    cachedPackets.clear();
                }
                else
                {
                    SrtpErrorStatus err = doTransform(transformer, packetInfo);
                    countErrorStatus(err);
                    if (err == SrtpErrorStatus.OK)
                    {
                        outPackets = Collections.singletonList(packetInfo);
                    }
                    else
                    {
                        packetDiscarded(packetInfo);
                        outPackets = Collections.emptyList();
                    }
                }
            }
            return outPackets;
        }
        else
        {
            numCachedPackets++;
            synchronized (cachedPackets)
            {
                cachedPackets.add(packetInfo);
                while (cachedPackets.size() > 1024)
                {
                    packetDiscarded(cachedPackets.remove(0));
                }
            }
            return Collections.emptyList();
        }
    }

    private int numSrtpProcessed = 0;
    private int numSrtpFail = 0;
    private int numSrtpAuthFail = 0;
    private int numSrtpReplayFail = 0;
    private int numSrtpReplayOld = 0;
    private int numSrtpInvalidPacket = 0;

    private void countErrorStatus(SrtpErrorStatus err)
    {
        switch (err)
        {
            case OK:
                numSrtpProcessed++;
                break;
            case FAIL:
                numSrtpFail++;
                break;
            case AUTH_FAIL:
                numSrtpAuthFail++;
                break;
            case REPLAY_FAIL:
                numSrtpReplayFail++;
                break;
            case REPLAY_OLD:
                numSrtpReplayOld++;
                break;
            case INVALID_PACKET:
                numSrtpInvalidPacket++;
                break;
        }
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock stats = super.getNodeStats();
        stats.addNumber("num_cached_packets", numCachedPackets);
        if (firstPacketReceivedTimestamp != -1L && firstPacketForwardedTimestamp != -1L)
        {
            long timeBetweenReceivedAndForwarded = firstPacketForwardedTimestamp - firstPacketReceivedTimestamp;
            stats.addNumber("time_initial_hold_ms", timeBetweenReceivedAndForwarded);
        }
        else
        {
            stats.addString("state", "hold_for_transformer");
        }
        stats.addNumber("num_srtp_processed", numSrtpProcessed);
        stats.addNumber("num_srtp_fail", numSrtpFail);
        stats.addNumber("num_srtp_auth_fail", numSrtpAuthFail);
        stats.addNumber("num_srtp_replay_fail", numSrtpReplayFail);
        stats.addNumber("num_srtp_replay_old", numSrtpReplayOld);
        stats.addNumber("num_srtp_invalid_packet", numSrtpInvalidPacket);
        return stats;
    }

    @Override
    public ObjectNode statsJson()
    {
        ObjectNode json = super.statsJson();
        json.put("num_srtp_processed", numSrtpProcessed);
        json.put("num_srtp_fail", numSrtpFail);
        json.put("num_srtp_auth_fail", numSrtpAuthFail);
        json.put("num_srtp_replay_fail", numSrtpReplayFail);
        json.put("num_srtp_replay_old", numSrtpReplayOld);
        json.put("num_srtp_invalid_packet", numSrtpInvalidPacket);
        return json;
    }

    @Override
    public void stop()
    {
        super.stop();
        synchronized (cachedPackets)
        {
            cachedPackets.forEach(this::packetDiscarded);
            cachedPackets.clear();
        }
    }
}
