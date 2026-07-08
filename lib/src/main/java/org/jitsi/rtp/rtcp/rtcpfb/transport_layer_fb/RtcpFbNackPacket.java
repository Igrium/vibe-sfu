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

import java.util.SortedSet;
import java.util.TreeSet;

/**
 * https://tools.ietf.org/html/rfc4585#section-6.2.1
 *
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|   FMT   |       PT      |          length               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of packet sender                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of media source                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            PID                |             BLP               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | (optional) PID                |             BLP               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 */
public class RtcpFbNackPacket extends TransportLayerRtcpFbPacket
{
    public static final int FMT = 1;
    public static final int NACK_BLOCK_OFFSET = HEADER_SIZE;

    private final int numNackBlocks;
    private SortedSet<Integer> missingSeqNums;

    public RtcpFbNackPacket(byte[] buffer, int offset, int length)
    {
        super(buffer, offset, length);
        this.numNackBlocks = (getPacketLength() - HEADER_SIZE) / NackBlock.SIZE_BYTES;
    }

    public SortedSet<Integer> getMissingSeqNums()
    {
        if (missingSeqNums == null)
        {
            SortedSet<Integer> result = new TreeSet<>();
            for (int i = 0; i < numNackBlocks; i++)
            {
                result.addAll(
                    NackBlock.getMissingSeqNums(buffer, offset + NACK_BLOCK_OFFSET + i * NackBlock.SIZE_BYTES)
                );
            }
            missingSeqNums = result;
        }
        return missingSeqNums;
    }

    @Override
    public RtcpFbNackPacket clone()
    {
        return new RtcpFbNackPacket(cloneBuffer(0), 0, length);
    }
}
