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

package org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb;

/**
 * Translation of the Kotlin {@code sealed class PacketInfo} from {@code RtcpFbCcfbPacket.kt}.
 */
public abstract class PacketInfo
{
    private final long ssrc;
    private final int sequenceNumber;

    protected PacketInfo(long ssrc, int sequenceNumber)
    {
        this.ssrc = ssrc;
        this.sequenceNumber = sequenceNumber;
    }

    protected PacketInfo()
    {
        this(0, 0);
    }

    public long getSsrc()
    {
        return ssrc;
    }

    public int getSequenceNumber()
    {
        return sequenceNumber;
    }

    @Override
    public String toString()
    {
        return "ssrc=" + ssrc + ", sequenceNumber=" + sequenceNumber;
    }
}
