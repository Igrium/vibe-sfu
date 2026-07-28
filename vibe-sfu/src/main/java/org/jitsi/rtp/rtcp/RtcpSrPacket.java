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

import org.jitsi.rtp.extensions.unsigned.Unsigned;

import java.util.ArrayList;
import java.util.List;

/**
 *        0                   1                   2                   3
 *        0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * header |V=2|P|    RC   |   PT=SR=200   |             length            |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                         SSRC of sender                        |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * sender |              NTP timestamp, most significant word             |
 * info   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |             NTP timestamp, least significant word             |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                         RTP timestamp                         |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                     sender's packet count                     |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                      sender's octet count                     |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * report |                 SSRC_1 (SSRC of first source)                 |
 * block  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * 1      | fraction lost |       cumulative number of packets lost       |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |           extended highest sequence number received           |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                      interarrival jitter                      |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                         last SR (LSR)                         |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                   delay since last SR (DLSR)                  |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * report |                 SSRC_2 (SSRC of second source)                |
 * block  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * 2      :                               ...                             :
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 *        |                  profile-specific extensions                  |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * https://tools.ietf.org/html/rfc3550#section-6.4.1
 */
public class RtcpSrPacket extends RtcpPacket
{
    public static final int PT = 200;
    public static final int SENDER_INFO_OFFSET = RtcpHeader.SIZE_BYTES;
    public static final int REPORT_BLOCKS_OFFSET = SENDER_INFO_OFFSET + SenderInfoParser.SIZE_BYTES;

    private SenderInfo senderInfo;
    private List<RtcpReportBlock> reportBlocks;

    public RtcpSrPacket(byte[] buffer, int offset, int length)
    {
        super(buffer, offset, length);
    }

    public SenderInfo getSenderInfo()
    {
        if (senderInfo == null)
        {
            senderInfo = new SenderInfo();
        }
        return senderInfo;
    }

    public List<RtcpReportBlock> getReportBlocks()
    {
        if (reportBlocks == null)
        {
            List<RtcpReportBlock> result = new ArrayList<>(getReportCount());
            for (int i = 0; i < getReportCount(); i++)
            {
                result.add(RtcpReportBlock.fromBuffer(buffer, offset + REPORT_BLOCKS_OFFSET + i * RtcpReportBlock.SIZE_BYTES));
            }
            reportBlocks = result;
        }
        return reportBlocks;
    }

    @Override
    public RtcpSrPacket clone()
    {
        return new RtcpSrPacket(cloneBuffer(0), 0, length);
    }

    public RtcpSrPacket cloneWithoutReportBlocks()
    {
        RtcpHeaderBuilder header = new RtcpHeaderBuilder()
            .setReportCount(0)
            .setSenderSsrc(this.getSenderSsrc());
        SenderInfoBuilder senderInfoBuilder = new SenderInfoBuilder(
            this.getSenderInfo().getNtpTimestampMsw(),
            this.getSenderInfo().getNtpTimestampLsw(),
            this.getSenderInfo().getRtpTimestamp(),
            this.getSenderInfo().getSendersPacketCount(),
            this.getSenderInfo().getSendersOctetCount()
        );
        return new RtcpSrPacketBuilder(header, senderInfoBuilder, new ArrayList<>()).build();
    }

    // SenderInfo is defined differently so that we can scope these variables under a the 'senderInfo'
    // member here.  Is there a better way?
    public class SenderInfo
    {
        private final long ntpTimestampMsw = SenderInfoParser.getNtpTimestampMsw(buffer, offset + SENDER_INFO_OFFSET);
        private final long ntpTimestampLsw = SenderInfoParser.getNtpTimestampLsw(buffer, offset + SENDER_INFO_OFFSET);
        private Long compactedNtpTimestamp;

        public long getNtpTimestampMsw()
        {
            return ntpTimestampMsw;
        }

        public long getNtpTimestampLsw()
        {
            return ntpTimestampLsw;
        }

        public long getRtpTimestamp()
        {
            return SenderInfoParser.getRtpTimestamp(buffer, offset + SENDER_INFO_OFFSET);
        }

        public void setRtpTimestamp(long value)
        {
            SenderInfoParser.setRtpTimestamp(buffer, offset + SENDER_INFO_OFFSET, value);
        }

        public long getSendersPacketCount()
        {
            return SenderInfoParser.getSendersPacketCount(buffer, offset + SENDER_INFO_OFFSET);
        }

        public void setSendersPacketCount(long value)
        {
            SenderInfoParser.setSendersPacketCount(buffer, offset + SENDER_INFO_OFFSET, value);
        }

        public long getSendersOctetCount()
        {
            return SenderInfoParser.getSendersOctetCount(buffer, offset + SENDER_INFO_OFFSET);
        }

        public void setSendersOctetCount(long value)
        {
            SenderInfoParser.setSendersOctetCount(buffer, offset + SENDER_INFO_OFFSET, value);
        }

        /**
         * https://tools.ietf.org/html/rfc3550#section-4
         * In some fields where a more compact representation is
         * appropriate, only the middle 32 bits are used; that is, the low 16
         * bits of the integer part and the high 16 bits of the fractional part.
         * The high 16 bits of the integer part must be determined
         * independently.
         */
        public long getCompactedNtpTimestamp()
        {
            if (compactedNtpTimestamp == null)
            {
                compactedNtpTimestamp = Unsigned.toPositiveLong(
                    (int) (((ntpTimestampMsw & 0xFFFF) << 16) | (ntpTimestampLsw >>> 16))
                );
            }
            return compactedNtpTimestamp;
        }
    }
}
