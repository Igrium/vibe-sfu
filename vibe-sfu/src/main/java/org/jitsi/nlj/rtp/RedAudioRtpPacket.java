/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.nlj.rtp;

import org.jitsi.rtp.rtp.RedPacketBuilder;
import org.jitsi.rtp.rtp.RedPacketParser;

import java.util.List;

public class RedAudioRtpPacket extends AudioRtpPacket
{
    public static final RedPacketParser<AudioRtpPacket> parser = new RedPacketParser<>(AudioRtpPacket::new);
    public static final RedPacketBuilder<RedAudioRtpPacket> builder = new RedPacketBuilder<>(RedAudioRtpPacket::new);

    private boolean removed = false;

    public RedAudioRtpPacket(byte[] buffer, int offset, int length)
    {
        super(buffer, offset, length);
    }

    public void removeRed()
    {
        if (removed)
        {
            throw new IllegalStateException("RED encapsulation already removed.");
        }
        parser.decapsulate(this, false);
        removed = true;
    }

    public List<AudioRtpPacket> removeRedAndGetRedundancyPackets()
    {
        if (removed)
        {
            throw new IllegalStateException("RED encapsulation already removed.");
        }
        List<AudioRtpPacket> redundancy = parser.decapsulate(this, true);
        removed = true;
        return redundancy;
    }

    @Override
    public RedAudioRtpPacket clone()
    {
        RedAudioRtpPacket clone = new RedAudioRtpPacket(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length
        );
        postClone(clone);
        return clone;
    }
}
