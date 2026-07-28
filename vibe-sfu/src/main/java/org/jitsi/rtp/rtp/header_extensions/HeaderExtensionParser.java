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

/** An abstract parser for header extensions. */
public abstract class HeaderExtensionParser
{
    public abstract int getHeaderExtensionLabel();

    public abstract int getExtHeaderSizeBytes();

    public abstract int getMinimumExtSizeBytes();

    /** Determine whether this header extension parser can parse header extensions with the given
     * "defined by profile" field.
     */
    public abstract boolean isMatchingType(int profileField);

    public abstract int getId(byte[] buf, int offset);

    /**
     * Write the given ID and length to {@code buf} at {@code offset}
     */
    public abstract void writeIdAndLength(int id, int dataLength, byte[] buf, int offset);

    /**
     * Return the entire size, in bytes, of the extension in {@code buf} whose header
     * starts at {@code offset}
     */
    public int getEntireLengthBytes(byte[] buf, int offset)
    {
        return getDataLengthBytes(buf, offset) + getExtHeaderSizeBytes();
    }

    /**
     * Return the data size, in bytes, of the extension in {@code buf} whose header
     * starts at {@code offset}.
     */
    public abstract int getDataLengthBytes(byte[] buf, int offset);
}
