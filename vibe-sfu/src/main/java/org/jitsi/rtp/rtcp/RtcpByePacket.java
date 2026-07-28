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

import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.rtp.extensions.unsigned.Unsigned;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@link RtcpByePacket} cannot be changed once it has been created, so we
 * parse the fields once (lazily) and store them.
 * TODO: technically it COULD be changed, since anyone can change the buffer.
 * can we enforce immutability?
 *
 * https://tools.ietf.org/html/rfc3550#section-6.6
 *
 *       0                   1                   2                   3
 *       0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *       |V=2|P|    SC   |   PT=BYE=203  |             length            |
 *       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *       |                           SSRC/CSRC                           |
 *       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *       :                              ...                              :
 *       +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * (opt) |     length    |               reason for leaving            ...
 *       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public class RtcpByePacket extends RtcpPacket
{
    public static final int PT = 203;

    private List<Long> ssrcs;
    private boolean reasonComputed = false;
    private String reason;

    public RtcpByePacket(byte[] buffer, int offset, int length)
    {
        super(buffer, offset, length);
    }

    public List<Long> getSsrcs()
    {
        if (ssrcs == null)
        {
            int ssrcStartOffset = offset + 4;
            List<Long> result = new ArrayList<>(getReportCount());
            for (int i = 0; i < getReportCount(); i++)
            {
                result.add(Unsigned.toPositiveLong(ByteArrayExtensions.getInt(buffer, ssrcStartOffset + i * 4)));
            }
            ssrcs = result;
        }
        return ssrcs;
    }

    public String getReason()
    {
        if (!reasonComputed)
        {
            int headerAndSsrcsLengthBytes = RtcpHeader.SIZE_BYTES + (getReportCount() - 1) * 4;
            boolean hasReason = headerAndSsrcsLengthBytes < length;

            if (hasReason)
            {
                int reasonLengthOffset = offset + headerAndSsrcsLengthBytes;
                int reasonLength = buffer[reasonLengthOffset];
                // Note: matches Kotlin's String(buffer, offset, length), which resolves to the
                // java.lang.String(byte[], int, int) constructor and uses the platform default charset.
                reason = new String(buffer, reasonLengthOffset + 1, reasonLength);
            }
            else
            {
                reason = null;
            }
            reasonComputed = true;
        }
        return reason;
    }

    @Override
    public RtcpByePacket clone()
    {
        return new RtcpByePacket(cloneBuffer(0), 0, length);
    }
}
