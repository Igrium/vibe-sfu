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

package org.jitsi.rtp.rtp;

/**
 * Package-private, matching the Kotlin {@code internal} visibility modifier on the original class.
 */
class RtpRedPacket extends RtpPacket
{
    static final RedPacketParser<RtpPacket> parser = new RedPacketParser<>(RtpPacket::new);
    static final RedPacketBuilder<RtpRedPacket> builder = new RedPacketBuilder<>(RtpRedPacket::new);

    RtpRedPacket(byte[] buffer, int offset, int length)
    {
        super(buffer, offset, length);
    }

    @Override
    public RtpRedPacket clone()
    {
        RtpRedPacket clone = new RtpRedPacket(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length
        );
        postClone(clone);
        return clone;
    }

    java.util.List<RtpPacket> decapsulate(boolean parseRedundancy)
    {
        return parser.decapsulate(this, parseRedundancy);
    }
}
