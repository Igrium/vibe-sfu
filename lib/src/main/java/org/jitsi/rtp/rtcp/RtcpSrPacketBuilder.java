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
import org.jitsi.rtp.util.RtpUtils;

import java.util.Collections;
import java.util.List;

public class RtcpSrPacketBuilder
{
    private RtcpHeaderBuilder rtcpHeader = new RtcpHeaderBuilder();
    private SenderInfoBuilder senderInfo = new SenderInfoBuilder();
    private List<RtcpReportBlock> reportBlocks = Collections.emptyList();

    public RtcpSrPacketBuilder()
    {
    }

    public RtcpSrPacketBuilder(RtcpHeaderBuilder rtcpHeader, SenderInfoBuilder senderInfo, List<RtcpReportBlock> reportBlocks)
    {
        this.rtcpHeader = rtcpHeader;
        this.senderInfo = senderInfo;
        this.reportBlocks = reportBlocks;
        if (reportBlocks.size() > 31)
        {
            throw new IllegalArgumentException("Too many report blocks " + reportBlocks.size() + ": SR can contain at most 31");
        }
    }

    public RtcpHeaderBuilder getRtcpHeader()
    {
        return rtcpHeader;
    }

    public void setRtcpHeader(RtcpHeaderBuilder rtcpHeader)
    {
        this.rtcpHeader = rtcpHeader;
    }

    public SenderInfoBuilder getSenderInfo()
    {
        return senderInfo;
    }

    public void setSenderInfo(SenderInfoBuilder senderInfo)
    {
        this.senderInfo = senderInfo;
    }

    public List<RtcpReportBlock> getReportBlocks()
    {
        return reportBlocks;
    }

    private int getSizeBytes()
    {
        return RtcpHeader.SIZE_BYTES + SenderInfoParser.SIZE_BYTES + reportBlocks.size() * RtcpReportBlock.SIZE_BYTES;
    }

    public RtcpSrPacket build()
    {
        byte[] buf = BufferPool.getArray(getSizeBytes());
        writeTo(buf, 0);
        return new RtcpSrPacket(buf, 0, getSizeBytes());
    }

    public void writeTo(byte[] buf, int offset)
    {
        rtcpHeader.setPacketType(RtcpSrPacket.PT)
            .setReportCount(reportBlocks.size())
            .setLength(RtpUtils.calculateRtcpLengthFieldValue(getSizeBytes()))
            .writeTo(buf, offset);
        senderInfo.writeTo(buf, offset + RtcpSrPacket.SENDER_INFO_OFFSET);
        for (int index = 0; index < reportBlocks.size(); index++)
        {
            reportBlocks.get(index).writeTo(
                buf, offset + RtcpSrPacket.REPORT_BLOCKS_OFFSET + index * RtcpReportBlock.SIZE_BYTES
            );
        }
    }
}
