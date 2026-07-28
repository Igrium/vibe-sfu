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

import java.util.ArrayList;
import java.util.List;

/**
 * https://tools.ietf.org/html/rfc3550#section-6.5
 *        0                   1                   2                   3
 *        0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * header |V=2|P|    SC   |  PT=SDES=202  |             length            |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * chunk  |                          SSRC/CSRC_1                          |
 *   1    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                           SDES items                          |
 *        |                              ...                              |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * chunk  |                          SSRC/CSRC_2                          |
 *   2    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                           SDES items                          |
 *        |                              ...                              |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 *
 *
 */
public class RtcpSdesPacket extends RtcpPacket
{
    public static final int PT = 202;
    public static final int CHUNKS_OFFSET = 4;

    private List<SdesChunk> sdesChunks;

    public RtcpSdesPacket(byte[] buffer, int offset, int length)
    {
        super(buffer, offset, length);
    }

    public List<SdesChunk> getSdesChunks()
    {
        if (sdesChunks == null)
        {
            sdesChunks = getSdesChunks(buffer, offset, length);
        }
        return sdesChunks;
    }

    @Override
    public RtcpSdesPacket clone()
    {
        return new RtcpSdesPacket(cloneBuffer(0), 0, length);
    }

    public static List<SdesChunk> getSdesChunks(byte[] buf, int baseOffset, int length)
    {
        int currOffset = baseOffset + CHUNKS_OFFSET;
        List<SdesChunk> sdesChunks = new ArrayList<>();
        while (currOffset < length)
        {
            SdesChunk sdesChunk = new SdesChunk(buf, currOffset);
            sdesChunks.add(sdesChunk);
            currOffset += sdesChunk.getSizeBytes();
        }
        return sdesChunks;
    }
}
