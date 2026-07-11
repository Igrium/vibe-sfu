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
package org.jitsi.nlj.rtp.bandwidthestimation2;

import org.jitsi.utils.InstantKt;

import java.time.Instant;

/** Sent packet info,
 * based loosely on WebRTC rtc_base/network/sent_packet.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138)
 * stripped down to only the fields needed.
 */
public class SentPacketInfo
{
    public long packetId = -1;
    public Instant sendTime = InstantKt.NEVER;
    public final PacketInfo info;

    public SentPacketInfo()
    {
        this(-1, InstantKt.NEVER, new PacketInfo());
    }

    public SentPacketInfo(long packetId, Instant sendTime, PacketInfo info)
    {
        this.packetId = packetId;
        this.sendTime = sendTime;
        this.info = info;
    }

    // (Deviation: named PacketInfo like upstream; this is a bandwidthestimation2-local type,
    // distinct from org.jitsi.nlj.PacketInfo. Files that need both must fully-qualify one.)
    public static class PacketInfo
    {
        public boolean includedInFeedback = false;
        public boolean includedInAllocation = false;
        public long packetSizeBytes = 0;

        public PacketInfo()
        {
        }

        public PacketInfo(boolean includedInFeedback, boolean includedInAllocation, long packetSizeBytes)
        {
            this.includedInFeedback = includedInFeedback;
            this.includedInAllocation = includedInAllocation;
            this.packetSizeBytes = packetSizeBytes;
        }
    }
}
