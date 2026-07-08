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

public class RtcpFbPliPacketBuilder
{
    private RtcpHeaderBuilder rtcpHeader = new RtcpHeaderBuilder();
    private long mediaSourceSsrc = -1;

    public RtcpFbPliPacketBuilder()
    {
    }

    public RtcpFbPliPacketBuilder(RtcpHeaderBuilder rtcpHeader, long mediaSourceSsrc)
    {
        this.rtcpHeader = rtcpHeader;
        this.mediaSourceSsrc = mediaSourceSsrc;
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

    public RtcpFbPliPacket build()
    {
        byte[] buf = BufferPool.getArray(RtcpFbPliPacket.SIZE_BYTES);
        writeTo(buf, 0);
        return new RtcpFbPliPacket(buf, 0, RtcpFbPliPacket.SIZE_BYTES);
    }

    public void writeTo(byte[] buf, int offset)
    {
        rtcpHeader.setPacketType(PayloadSpecificRtcpFbPacket.PT)
            .setReportCount(RtcpFbPliPacket.FMT)
            .setLength(RtpUtils.calculateRtcpLengthFieldValue(RtcpFbPliPacket.SIZE_BYTES));
        rtcpHeader.writeTo(buf, offset);
        RtcpFbPacket.setMediaSourceSsrc(buf, offset, mediaSourceSsrc);
    }
}
