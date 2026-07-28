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

package org.jitsi.videobridge.util;

/**
 * Packet inspection helpers.
 *
 * (Ported from the upstream Kotlin file {@code PacketUtils.kt}.)
 */
public final class PacketUtils
{
    private static final int DTLS_RANGE_MIN = 20;
    private static final int DTLS_RANGE_MAX = 63;

    private PacketUtils()
    {
    }

    // TODO(brian): move this to RTP, and have the Packet extension
    // function leverage it
    public static boolean looksLikeDtls(byte[] buf, int off, int len)
    {
        if (len < 1)
        {
            return false;
        }
        int b = buf[off] & 0xFF;
        return b >= DTLS_RANGE_MIN && b <= DTLS_RANGE_MAX;
    }
}
