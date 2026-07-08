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

public final class OneByteHeaderExtensionParser extends HeaderExtensionParser
{
    public static final OneByteHeaderExtensionParser INSTANCE = new OneByteHeaderExtensionParser();

    private OneByteHeaderExtensionParser()
    {
    }

    @Override
    public int getHeaderExtensionLabel()
    {
        return 0xBEDE;
    }

    @Override
    public int getExtHeaderSizeBytes()
    {
        return 1;
    }

    @Override
    public int getMinimumExtSizeBytes()
    {
        return 2;
    }

    @Override
    public boolean isMatchingType(int profileField)
    {
        return profileField == getHeaderExtensionLabel();
    }

    @Override
    public int getId(byte[] buf, int offset)
    {
        return (buf[offset] >>> 4) & 0x0F;
    }

    @Override
    public void writeIdAndLength(int id, int dataLength, byte[] buf, int offset)
    {
        if (!(id >= 1 && id <= 14))
        {
            throw new IllegalArgumentException("id " + id + " out of range 1..14");
        }
        if (!(dataLength >= 1 && dataLength <= 16))
        {
            throw new IllegalArgumentException("dataLength " + dataLength + " out of range 1..16");
        }
        buf[offset] = (byte) (((id & 0x0F) << 4) | ((dataLength - 1) & 0x0F));
    }

    @Override
    public int getDataLengthBytes(byte[] buf, int offset)
    {
        return Unsigned.toPositiveInt((byte) (buf[offset] & 0x0F)) + 1;
    }
}
