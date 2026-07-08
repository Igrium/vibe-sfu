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

package org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb;

import org.jitsi.rtp.rtcp.RtcpHeaderBuilder;
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket;
import org.jitsi.rtp.util.BufferPool;
import org.jitsi.rtp.util.RtpUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

public class RtcpFbNackPacketBuilder
{
    private final RtcpHeaderBuilder rtcpHeader;
    private long mediaSourceSsrc;
    private final SortedSet<Integer> missingSeqNums;

    private final List<NackBlock> nackBlocks;
    private final int sizeBytes;

    public RtcpFbNackPacketBuilder()
    {
        this(new RtcpHeaderBuilder(), -1, new TreeSet<>());
    }

    public RtcpFbNackPacketBuilder(RtcpHeaderBuilder rtcpHeader, long mediaSourceSsrc, SortedSet<Integer> missingSeqNums)
    {
        this.rtcpHeader = rtcpHeader;
        this.mediaSourceSsrc = mediaSourceSsrc;
        this.missingSeqNums = missingSeqNums;

        List<NackBlock> blocks = new ArrayList<>();
        for (List<Integer> chunk : chunkMaxDifference(new ArrayList<>(missingSeqNums), 16))
        {
            blocks.add(new NackBlock(new TreeSet<>(chunk)));
        }
        this.nackBlocks = blocks;
        this.sizeBytes = RtcpFbPacket.HEADER_SIZE + nackBlocks.size() * NackBlock.SIZE_BYTES;
    }

    public RtcpHeaderBuilder getRtcpHeader()
    {
        return rtcpHeader;
    }

    public long getMediaSourceSsrc()
    {
        return mediaSourceSsrc;
    }

    public void setMediaSourceSsrc(long mediaSourceSsrc)
    {
        this.mediaSourceSsrc = mediaSourceSsrc;
    }

    public SortedSet<Integer> getMissingSeqNums()
    {
        return missingSeqNums;
    }

    public RtcpFbNackPacket build()
    {
        byte[] buf = BufferPool.getArray(sizeBytes);
        writeTo(buf, 0);
        return new RtcpFbNackPacket(buf, 0, sizeBytes);
    }

    public void writeTo(byte[] buf, int offset)
    {
        rtcpHeader.setPacketType(TransportLayerRtcpFbPacket.PT)
            .setReportCount(RtcpFbNackPacket.FMT)
            .setLength(RtpUtils.calculateRtcpLengthFieldValue(sizeBytes));
        rtcpHeader.writeTo(buf, offset);
        RtcpFbPacket.setMediaSourceSsrc(buf, offset, mediaSourceSsrc);
        for (int index = 0; index < nackBlocks.size(); index++)
        {
            nackBlocks.get(index).writeTo(
                buf, offset + RtcpFbNackPacket.NACK_BLOCK_OFFSET + index * NackBlock.SIZE_BYTES
            );
        }
    }

    /**
     * Return a List of Lists, where sub-list is made up of an ordered list
     * of values pulled from {@code list}, such that the difference between the
     * first element and the last element is not more than {@code maxDifference}
     */
    private static List<List<Integer>> chunkMaxDifference(List<Integer> list, int maxDifference)
    {
        List<List<Integer>> chunks = new ArrayList<>();
        if (list.isEmpty())
        {
            return chunks;
        }
        List<Integer> currentChunk = new ArrayList<>(Collections.singletonList(list.get(0)));
        chunks.add(currentChunk);
        // Ignore the first value which we already put in the current chunk
        for (int i = 1; i < list.size(); i++)
        {
            int it = list.get(i);
            if (it - currentChunk.get(0) > maxDifference)
            {
                currentChunk = new ArrayList<>();
                currentChunk.add(it);
                chunks.add(currentChunk);
            }
            else
            {
                currentChunk.add(it);
            }
        }
        return chunks;
    }
}
