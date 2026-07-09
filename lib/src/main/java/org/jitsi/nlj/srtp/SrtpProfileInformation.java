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
package org.jitsi.nlj.srtp;

import java.util.Objects;

public class SrtpProfileInformation
{
    private final int cipherKeyLength;
    private final int cipherSaltLength;
    private final int cipherName;
    private final int authFunctionName;
    private final int authKeyLength;
    private final int rtcpAuthTagLength;
    private final int rtpAuthTagLength;

    public SrtpProfileInformation(
        int cipherKeyLength,
        int cipherSaltLength,
        int cipherName,
        int authFunctionName,
        int authKeyLength,
        int rtcpAuthTagLength,
        int rtpAuthTagLength)
    {
        this.cipherKeyLength = cipherKeyLength;
        this.cipherSaltLength = cipherSaltLength;
        this.cipherName = cipherName;
        this.authFunctionName = authFunctionName;
        this.authKeyLength = authKeyLength;
        this.rtcpAuthTagLength = rtcpAuthTagLength;
        this.rtpAuthTagLength = rtpAuthTagLength;
    }

    public int getCipherKeyLength()
    {
        return cipherKeyLength;
    }

    public int getCipherSaltLength()
    {
        return cipherSaltLength;
    }

    public int getCipherName()
    {
        return cipherName;
    }

    public int getAuthFunctionName()
    {
        return authFunctionName;
    }

    public int getAuthKeyLength()
    {
        return authKeyLength;
    }

    public int getRtcpAuthTagLength()
    {
        return rtcpAuthTagLength;
    }

    public int getRtpAuthTagLength()
    {
        return rtpAuthTagLength;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof SrtpProfileInformation))
        {
            return false;
        }
        SrtpProfileInformation that = (SrtpProfileInformation) o;
        return cipherKeyLength == that.cipherKeyLength &&
            cipherSaltLength == that.cipherSaltLength &&
            cipherName == that.cipherName &&
            authFunctionName == that.authFunctionName &&
            authKeyLength == that.authKeyLength &&
            rtcpAuthTagLength == that.rtcpAuthTagLength &&
            rtpAuthTagLength == that.rtpAuthTagLength;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(
            cipherKeyLength, cipherSaltLength, cipherName, authFunctionName, authKeyLength, rtcpAuthTagLength,
            rtpAuthTagLength
        );
    }
}
