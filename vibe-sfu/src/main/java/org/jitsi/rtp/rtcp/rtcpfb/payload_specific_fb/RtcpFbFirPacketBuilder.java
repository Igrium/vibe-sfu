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

package org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb;

import org.jitsi.rtp.rtcp.RtcpHeaderBuilder;
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket;
import org.jitsi.rtp.util.BufferPool;
import org.jitsi.rtp.util.RtpUtils;

// TODO: we need an RtcpFbHeader so that we can fill out the mediaSourceSsrc for FB packets
public class RtcpFbFirPacketBuilder
{
    private RtcpHeaderBuilder rtcpHeader = new RtcpHeaderBuilder();
    private long mediaSenderSsrc = -1;
    private int firCommandSeqNum = -1;

    public RtcpFbFirPacketBuilder()
    {
    }

    public RtcpFbFirPacketBuilder(RtcpHeaderBuilder rtcpHeader, long mediaSenderSsrc, int firCommandSeqNum)
    {
        this.rtcpHeader = rtcpHeader;
        this.mediaSenderSsrc = mediaSenderSsrc;
        this.firCommandSeqNum = firCommandSeqNum;
    }

    public RtcpHeaderBuilder getRtcpHeader()
    {
        return rtcpHeader;
    }

    public long getMediaSenderSsrc()
    {
        return mediaSenderSsrc;
    }

    public void setMediaSenderSsrc(long mediaSenderSsrc)
    {
        this.mediaSenderSsrc = mediaSenderSsrc;
    }

    public int getFirCommandSeqNum()
    {
        return firCommandSeqNum;
    }

    public void setFirCommandSeqNum(int firCommandSeqNum)
    {
        this.firCommandSeqNum = firCommandSeqNum;
    }

    public RtcpFbFirPacket build()
    {
        byte[] buf = BufferPool.getArray(RtcpFbFirPacket.SIZE_BYTES);
        writeTo(buf, 0);
        return new RtcpFbFirPacket(buf, 0, RtcpFbFirPacket.SIZE_BYTES);
    }

    public void writeTo(byte[] buf, int offset)
    {
        rtcpHeader.setPacketType(PayloadSpecificRtcpFbPacket.PT)
            .setReportCount(RtcpFbFirPacket.FMT)
            .setLength(RtpUtils.calculateRtcpLengthFieldValue(RtcpFbFirPacket.SIZE_BYTES));
        rtcpHeader.writeTo(buf, offset);
        RtcpFbPacket.setMediaSourceSsrc(buf, offset, 0); // SHALL be 0
        RtcpFbFirPacket.setMediaSenderSsrc(buf, offset, mediaSenderSsrc);
        RtcpFbFirPacket.setSeqNum(buf, offset, firCommandSeqNum);
    }
}
