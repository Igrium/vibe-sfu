/*
 * Copyright @ 2021 - present 8x8, Inc.
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

import org.jitsi.rtp.rtp.RtpPacket;

import java.nio.charset.StandardCharsets;

/**
 * https://datatracker.ietf.org/doc/html/rfc7941#section-4.1.1
 * Note: this is only the One-Byte Format, because we don't support Two-Byte yet.
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  ID   |  len  | SDES item text value ...                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public final class SdesHeaderExtension
{
    private SdesHeaderExtension()
    {
    }

    public static String getTextValue(RtpPacket.HeaderExtension ext)
    {
        return getTextValue(ext.getBuffer(), ext.getDataOffset(), ext.getDataLengthBytes());
    }

    public static void setTextValue(RtpPacket.HeaderExtension ext, String sdesValue)
    {
        assert ext.getDataLengthBytes() == sdesValue.length() : "buffer size doesn't match SDES value length";
        setTextValue(ext.getBuffer(), ext.getDataOffset(), sdesValue);
    }

    private static String getTextValue(byte[] buf, int offset, int dataLength)
    {
        /* RFC 7941 says the value in RTP is UTF-8. But we use this for MID and RID values
         * which are define for SDP in RFC 5888 and RFC 4566 as ASCII only. Thus we don't
         * support UTF-8 to keep things simpler. */
        return new String(buf, offset, dataLength, StandardCharsets.US_ASCII);
    }

    private static void setTextValue(byte[] buf, int offset, String sdesValue)
    {
        System.arraycopy(
            sdesValue.getBytes(StandardCharsets.US_ASCII),
            0,
            buf,
            offset,
            sdesValue.length()
        );
    }
}
