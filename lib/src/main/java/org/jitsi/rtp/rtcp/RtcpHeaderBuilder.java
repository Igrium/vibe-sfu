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

public class RtcpHeaderBuilder
{
    private int version = 2;
    private boolean hasPadding = false;
    private int reportCount = -1;
    private int packetType = -1;
    private int length = -1;
    private long senderSsrc = 0;

    public RtcpHeaderBuilder()
    {
    }

    public RtcpHeaderBuilder(int version, boolean hasPadding, int reportCount, int packetType, int length, long senderSsrc)
    {
        this.version = version;
        this.hasPadding = hasPadding;
        this.reportCount = reportCount;
        this.packetType = packetType;
        this.length = length;
        this.senderSsrc = senderSsrc;
    }

    public int getVersion()
    {
        return version;
    }

    public RtcpHeaderBuilder setVersion(int version)
    {
        this.version = version;
        return this;
    }

    public boolean getHasPadding()
    {
        return hasPadding;
    }

    public RtcpHeaderBuilder setHasPadding(boolean hasPadding)
    {
        this.hasPadding = hasPadding;
        return this;
    }

    public int getReportCount()
    {
        return reportCount;
    }

    public RtcpHeaderBuilder setReportCount(int reportCount)
    {
        this.reportCount = reportCount;
        return this;
    }

    public int getPacketType()
    {
        return packetType;
    }

    public RtcpHeaderBuilder setPacketType(int packetType)
    {
        this.packetType = packetType;
        return this;
    }

    public int getLength()
    {
        return length;
    }

    public RtcpHeaderBuilder setLength(int length)
    {
        this.length = length;
        return this;
    }

    public long getSenderSsrc()
    {
        return senderSsrc;
    }

    public RtcpHeaderBuilder setSenderSsrc(long senderSsrc)
    {
        this.senderSsrc = senderSsrc;
        return this;
    }

    public byte[] build()
    {
        byte[] buf = new byte[RtcpHeader.SIZE_BYTES];
        writeTo(buf, 0);
        return buf;
    }

    public RtcpHeaderBuilder writeTo(byte[] buf, int offset)
    {
        RtcpHeader.setVersion(buf, offset, version);
        RtcpHeader.setPadding(buf, offset, hasPadding);
        RtcpHeader.setReportCount(buf, offset, reportCount);
        RtcpHeader.setPacketType(buf, offset, packetType);
        RtcpHeader.setLength(buf, offset, length);
        RtcpHeader.setSenderSsrc(buf, offset, senderSsrc);
        return this;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof RtcpHeaderBuilder))
        {
            return false;
        }
        RtcpHeaderBuilder that = (RtcpHeaderBuilder) o;
        return version == that.version && hasPadding == that.hasPadding && reportCount == that.reportCount &&
            packetType == that.packetType && length == that.length && senderSsrc == that.senderSsrc;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(version, hasPadding, reportCount, packetType, length, senderSsrc);
    }

    @Override
    public String toString()
    {
        return "RtcpHeaderBuilder(version=" + version + ", hasPadding=" + hasPadding + ", reportCount=" + reportCount +
            ", packetType=" + packetType + ", length=" + length + ", senderSsrc=" + senderSsrc + ")";
    }
}
