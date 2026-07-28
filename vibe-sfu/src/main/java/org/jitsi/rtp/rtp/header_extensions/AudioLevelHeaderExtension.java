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

package org.jitsi.rtp.rtp.header_extensions;

import org.jitsi.rtp.extensions.unsigned.Unsigned;
import org.jitsi.rtp.rtp.RtpPacket;

/**
 * https://tools.ietf.org/html/rfc6464#section-3
 * TODO: this can be held as either 1 byte or 2 byte. (though webrtc clients appear to all use 1 byte)
 *
 *  0                   1
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  ID   | len=0 |V| level       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public final class AudioLevelHeaderExtension
{
    private AudioLevelHeaderExtension()
    {
    }

    private static final byte AUDIO_LEVEL_MASK = (byte) 0x7F;

    public static int getAudioLevel(RtpPacket.HeaderExtension ext)
    {
        return getAudioLevel(ext.getBuffer(), ext.getDataOffset());
    }

    private static int getAudioLevel(byte[] buf, int offset)
    {
        return Unsigned.toPositiveInt((byte) (buf[offset] & AUDIO_LEVEL_MASK));
    }

    public static boolean getVad(RtpPacket.HeaderExtension ext)
    {
        return getVad(ext.getBuffer(), ext.getDataOffset());
    }

    private static boolean getVad(byte[] buf, int offset)
    {
        return (buf[offset] & 0x80) != 0;
    }
}
