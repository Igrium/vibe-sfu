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

package org.jitsi.rtp.rtcp;

import org.jitsi.rtp.util.BufferPool;

import java.util.ArrayList;
import java.util.List;

public class CompoundRtcpPacket extends RtcpPacket
{
    private List<RtcpPacket> packets;

    public CompoundRtcpPacket(byte[] buffer, int offset, int length)
    {
        super(buffer, offset, length);
    }

    public List<RtcpPacket> getPackets()
    {
        if (packets == null)
        {
            packets = parsePackets(buffer, offset, length);
        }
        return packets;
    }

    /**
     * Named {@code parsePackets} (rather than {@code parse}, as in the Kotlin source's companion object) because a
     * static method cannot hide {@link RtcpPacket#parse(byte[], int, int)} with an incompatible return type in Java.
     */
    public static List<RtcpPacket> parsePackets(byte[] buffer, int offset, int length)
    {
        int bytesRemaining = length;
        int currOffset = offset;
        List<RtcpPacket> rtcpPackets = new ArrayList<>();
        while (bytesRemaining >= RtcpHeader.SIZE_BYTES)
        {
            RtcpPacket rtcpPacket;
            try
            {
                rtcpPacket = RtcpPacket.parse(buffer, currOffset, bytesRemaining);
            }
            catch (InvalidRtcpException e)
            {
                throw new CompoundRtcpContainedInvalidDataException(buffer, offset, length, currOffset, e.getReason());
            }
            rtcpPackets.add(rtcpPacket);
            currOffset += rtcpPacket.getLength();
            bytesRemaining -= rtcpPacket.getLength();
        }
        return rtcpPackets;
    }

    public static CompoundRtcpPacket of(List<? extends RtcpPacket> packets)
    {
        int totalLength = 0;
        for (RtcpPacket packet : packets)
        {
            totalLength += packet.getLength();
        }
        byte[] buf = BufferPool.getArray(totalLength + BYTES_TO_LEAVE_AT_END_OF_PACKET);

        int off = 0;
        for (RtcpPacket packet : packets)
        {
            System.arraycopy(packet.getBuffer(), packet.getOffset(), buf, off, packet.getLength());
            off += packet.getLength();
        }

        return new CompoundRtcpPacket(buf, 0, totalLength);
    }

    /**
     * Create one or more compound RTCP packets from {@code packets}, with each compound packet being
     * no more than {@code mtu} bytes in size (unless an individual packet is bigger than that, in which
     * case it will be in a compound packet on its own).
     */
    public static List<CompoundRtcpPacket> createWithMtu(List<? extends RtcpPacket> packets)
    {
        return createWithMtu(packets, 1500);
    }

    public static List<CompoundRtcpPacket> createWithMtu(List<? extends RtcpPacket> packets, int mtu)
    {
        List<List<RtcpPacket>> chunks = chunkMaxSize(packets, mtu, RtcpPacket::getLength);
        List<CompoundRtcpPacket> result = new ArrayList<>(chunks.size());
        for (List<RtcpPacket> chunk : chunks)
        {
            result.add(CompoundRtcpPacket.of(chunk));
        }
        return result;
    }

    @Override
    public CompoundRtcpPacket clone()
    {
        return new CompoundRtcpPacket(cloneBuffer(0), 0, length);
    }

    /**
     * Return a List of Lists, where sub-list is made up of an ordered list
     * of values pulled from {@code list}, such that the total size of each sub-list
     * is not more than maxSize.  (Sizes are evaluated by {@code evaluate}. If an
     * individual element's size is more than {@code maxSize} it will be returned
     * in a sub-list on its own.)
     */
    private static <T> List<List<T>> chunkMaxSize(List<? extends T> list, int maxSize, java.util.function.ToIntFunction<T> evaluate)
    {
        List<List<T>> chunks = new ArrayList<>();
        if (list.isEmpty())
        {
            return chunks;
        }
        List<T> currentChunk = new ArrayList<>();
        currentChunk.add(list.get(0));
        chunks.add(currentChunk);
        int chunkSize = evaluate.applyAsInt(currentChunk.get(0));
        // Ignore the first value which we already put in the current chunk
        for (int i = 1; i < list.size(); i++)
        {
            T it = list.get(i);
            int size = evaluate.applyAsInt(it);
            if (chunkSize + size > maxSize)
            {
                currentChunk = new ArrayList<>();
                currentChunk.add(it);
                chunks.add(currentChunk);
                chunkSize = size;
            }
            else
            {
                currentChunk.add(it);
                chunkSize += size;
            }
        }
        return chunks;
    }
}
