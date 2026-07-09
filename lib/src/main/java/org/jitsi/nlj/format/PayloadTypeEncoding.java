/*
 * Copyright @ 2019-Present 8x8, Inc
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
package org.jitsi.nlj.format;

public enum PayloadTypeEncoding
{
    OTHER,
    VP8,
    VP9,
    AV1,
    H264,
    RED,
    RTX,
    OPUS,
    TELEPHONE_EVENT;

    private static final String TEL_EVENT_TEXT = "telephone-event";

    /**
     * {@link #valueOf(String)} does not allow for case-insensitivity and can't be overridden, so this
     * method should be used when creating an instance of this enum from a string
     */
    public static PayloadTypeEncoding createFrom(String value)
    {
        try
        {
            if (value.toLowerCase().equals(TEL_EVENT_TEXT))
            {
                return TELEPHONE_EVENT;
            }
            return valueOf(value.toUpperCase());
        }
        catch (IllegalArgumentException e)
        {
            return OTHER;
        }
    }

    @Override
    public String toString()
    {
        if (this == TELEPHONE_EVENT)
        {
            return TEL_EVENT_TEXT;
        }
        return super.toString();
    }
}
