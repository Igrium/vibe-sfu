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

public final class TwoByteHeaderExtensionParser extends HeaderExtensionParser
{
    public static final TwoByteHeaderExtensionParser INSTANCE = new TwoByteHeaderExtensionParser();

    /* We don't support "value 256", in the low four bits of the "defined by profile" field. */
    private static final int HEADER_EXTENSION_MASK = 0xFFF0;

    private TwoByteHeaderExtensionParser()
    {
    }

    @Override
    public int getHeaderExtensionLabel()
    {
        return 0x1000;
    }

    @Override
    public int getExtHeaderSizeBytes()
    {
        return 2;
    }

    @Override
    public int getMinimumExtSizeBytes()
    {
        return 2;
    }

    @Override
    public boolean isMatchingType(int profileField)
    {
        return (profileField & HEADER_EXTENSION_MASK) == getHeaderExtensionLabel();
    }

    @Override
    public int getId(byte[] buf, int offset)
    {
        return buf[offset];
    }

    @Override
    public void writeIdAndLength(int id, int dataLength, byte[] buf, int offset)
    {
        if (!(id >= 1 && id <= 255))
        {
            throw new IllegalArgumentException("id " + id + " out of range 1..255");
        }
        if (!(dataLength >= 0 && dataLength <= 255))
        {
            throw new IllegalArgumentException("dataLength " + dataLength + " out of range 0..255");
        }

        buf[offset] = (byte) id;
        buf[offset + 1] = (byte) dataLength;
    }

    @Override
    public int getDataLengthBytes(byte[] buf, int offset)
    {
        return Unsigned.toPositiveInt(buf[offset + 1]);
    }
}
