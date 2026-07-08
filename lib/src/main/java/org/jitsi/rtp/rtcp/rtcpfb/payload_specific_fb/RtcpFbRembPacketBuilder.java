/*
 * Copyright @ 2019 - present 8x8, Inc.
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

import java.util.Collections;
import java.util.List;

public class RtcpFbRembPacketBuilder
{
    private final RtcpHeaderBuilder rtcpHeader;
    private final List<Long> ssrcs;
    private final long brBps;
    private final int sizeBytes;

    public RtcpFbRembPacketBuilder(long brBps)
    {
        this(new RtcpHeaderBuilder(), Collections.emptyList(), brBps);
    }

    public RtcpFbRembPacketBuilder(RtcpHeaderBuilder rtcpHeader, List<Long> ssrcs, long brBps)
    {
        this.rtcpHeader = rtcpHeader;
        this.ssrcs = ssrcs;
        this.brBps = brBps;
        this.sizeBytes = RtcpFbPacket.HEADER_SIZE +
            RtcpFbRembPacket.REMB_LEN + RtcpFbRembPacket.NUM_SSRC_LEN + RtcpFbRembPacket.BR_LEN + ssrcs.size() * 4;
    }

    public RtcpHeaderBuilder getRtcpHeader()
    {
        return rtcpHeader;
    }

    public List<Long> getSsrcs()
    {
        return ssrcs;
    }

    public long getBrBps()
    {
        return brBps;
    }

    public RtcpFbRembPacket build()
    {
        byte[] buf = BufferPool.getArray(sizeBytes);
        writeTo(buf, 0);
        return new RtcpFbRembPacket(buf, 0, sizeBytes);
    }

    public void writeTo(byte[] buf, int offset)
    {
        rtcpHeader.setPacketType(PayloadSpecificRtcpFbPacket.PT)
            .setReportCount(RtcpFbRembPacket.FMT)
            .setLength(RtpUtils.calculateRtcpLengthFieldValue(sizeBytes));
        rtcpHeader.writeTo(buf, offset);
        RtcpFbPacket.setMediaSourceSsrc(buf, offset, 0);
        RtcpFbRembPacket.setRemb(buf, offset);
        RtcpFbRembPacket.setNumSsrc(buf, offset, ssrcs.size());

        RtcpFbRembPacket.ExpAndMantissa expAndMantissa = RtcpFbRembPacket.getExpAndMantissa(brBps);
        RtcpFbRembPacket.setBrExp(buf, offset, expAndMantissa.getExp());
        RtcpFbRembPacket.setBrMantissa(buf, offset, expAndMantissa.getMantissa());
        RtcpFbRembPacket.setSsrcs(buf, offset, ssrcs);
    }
}
