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

import java.util.Objects;

public class SenderInfoBuilder
{
    public static final long JAVA_TO_NTP_EPOCH_OFFSET_SECS = 2208988800L;

    private long ntpTimestampMsw = -1;
    private long ntpTimestampLsw = -1;
    private long rtpTimestamp = -1;
    private long sendersPacketCount = -1;
    private long sendersOctetCount = -1;

    public SenderInfoBuilder()
    {
    }

    public SenderInfoBuilder(
        long ntpTimestampMsw,
        long ntpTimestampLsw,
        long rtpTimestamp,
        long sendersPacketCount,
        long sendersOctetCount
    )
    {
        this.ntpTimestampMsw = ntpTimestampMsw;
        this.ntpTimestampLsw = ntpTimestampLsw;
        this.rtpTimestamp = rtpTimestamp;
        this.sendersPacketCount = sendersPacketCount;
        this.sendersOctetCount = sendersOctetCount;
    }

    public long getNtpTimestampMsw()
    {
        return ntpTimestampMsw;
    }

    public void setNtpTimestampMsw(long ntpTimestampMsw)
    {
        this.ntpTimestampMsw = ntpTimestampMsw;
    }

    public long getNtpTimestampLsw()
    {
        return ntpTimestampLsw;
    }

    public void setNtpTimestampLsw(long ntpTimestampLsw)
    {
        this.ntpTimestampLsw = ntpTimestampLsw;
    }

    public long getRtpTimestamp()
    {
        return rtpTimestamp;
    }

    public void setRtpTimestamp(long rtpTimestamp)
    {
        this.rtpTimestamp = rtpTimestamp;
    }

    public long getSendersPacketCount()
    {
        return sendersPacketCount;
    }

    public void setSendersPacketCount(long sendersPacketCount)
    {
        this.sendersPacketCount = sendersPacketCount;
    }

    public long getSendersOctetCount()
    {
        return sendersOctetCount;
    }

    public void setSendersOctetCount(long sendersOctetCount)
    {
        this.sendersOctetCount = sendersOctetCount;
    }

    public void setNtpFromJavaTime(long javaTime)
    {
        long wallSecs = javaTime / 1000;
        long wallMs = javaTime % 1000;
        ntpTimestampMsw = wallSecs + JAVA_TO_NTP_EPOCH_OFFSET_SECS;
        ntpTimestampLsw = wallMs * (1L << 32) / 1000;
    }

    public void writeTo(byte[] buf, int offset)
    {
        SenderInfoParser.setNtpTimestampMsw(buf, offset, ntpTimestampMsw);
        SenderInfoParser.setNtpTimestampLsw(buf, offset, ntpTimestampLsw);
        SenderInfoParser.setRtpTimestamp(buf, offset, rtpTimestamp);
        SenderInfoParser.setSendersPacketCount(buf, offset, sendersPacketCount);
        SenderInfoParser.setSendersOctetCount(buf, offset, sendersOctetCount);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof SenderInfoBuilder))
        {
            return false;
        }
        SenderInfoBuilder that = (SenderInfoBuilder) o;
        return ntpTimestampMsw == that.ntpTimestampMsw && ntpTimestampLsw == that.ntpTimestampLsw &&
            rtpTimestamp == that.rtpTimestamp && sendersPacketCount == that.sendersPacketCount &&
            sendersOctetCount == that.sendersOctetCount;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(ntpTimestampMsw, ntpTimestampLsw, rtpTimestamp, sendersPacketCount, sendersOctetCount);
    }
}
