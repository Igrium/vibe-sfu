/*
 * Copyright @ 2019 - present 8x8, Inc.
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

package org.jitsi.rtp.extensions;

import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.utils.ByteArrayBuffer;

/**
 * Translation of {@code extensions/ByteArrayBuffer.kt}.
 */
public final class ByteArrayBufferExtensions
{
    private ByteArrayBufferExtensions()
    {
    }

    public static String toHex(ByteArrayBuffer bab)
    {
        return toHex(bab, Integer.MAX_VALUE);
    }

    public static String toHex(ByteArrayBuffer bab, int maxBytes)
    {
        return ByteArrayExtensions.toHex(bab.getBuffer(), bab.getOffset(), Math.min(maxBytes, bab.getLength()));
    }
}
