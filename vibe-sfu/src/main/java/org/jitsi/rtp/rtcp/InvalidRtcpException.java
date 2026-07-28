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

import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;

public class InvalidRtcpException extends Exception
{
    private final String reason;

    public InvalidRtcpException(byte[] buf, int offset, String reason)
    {
        super("Invalid RTCP packet: " + reason + ": " + ByteArrayExtensions.toHex(buf, offset, RtcpHeader.SIZE_BYTES));
        this.reason = reason;
    }

    public String getReason()
    {
        return reason;
    }
}
