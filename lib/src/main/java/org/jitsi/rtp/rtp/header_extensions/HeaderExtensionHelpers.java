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

import org.jitsi.rtp.util.FieldParsers;

public class HeaderExtensionHelpers
{
    // The size of the header extension block header
    public static final int TOP_LEVEL_EXT_HEADER_SIZE_BYTES = 4;

    private static final HeaderExtensionParser[] headerExtensionParsers = new HeaderExtensionParser[]
        {
            OneByteHeaderExtensionParser.INSTANCE, TwoByteHeaderExtensionParser.INSTANCE
        };

    /**
     * Return the "defined by profile" header extension type field, as an integer.
     *
     * {@code offset} points to the start of the header extensions block.  Should
     * only be called if it's been verified that the header held in {@code buf}
     * actually contains extensions
     */
    public static int getExtensionsProfileType(byte[] buf, int offset)
    {
        return FieldParsers.getShortAsInt(buf, offset);
    }

    /**
     * Return the length of the entire header extensions block, including
     * the header, in bytes.
     *
     * {@code offset} points to the start of the header extensions block.  Should
     * only be called if it's been verified that the header held in {@code buf}
     * actually contains extensions
     */
    public static int getExtensionsTotalLength(byte[] buf, int offset)
    {
        return TOP_LEVEL_EXT_HEADER_SIZE_BYTES + FieldParsers.getShortAsInt(buf, offset + 2) * 4;
    }

    /**
     * Return a header extension parser for header extensions with the given "defined by profile" field
     * value, or null if none match (i.e. either cryptex-encrypted or proprietary header extensions).
     * Does the right thing for the invalid value -1 returned by {@link org.jitsi.rtp.rtp.RtpHeader#getExtensionsProfileType}
     * when no extensions are present.
     */
    public static HeaderExtensionParser getHeaderExtensionParser(int profileField)
    {
        for (HeaderExtensionParser parser : headerExtensionParsers)
        {
            if (parser.isMatchingType(profileField))
            {
                return parser;
            }
        }
        return null;
    }
}
