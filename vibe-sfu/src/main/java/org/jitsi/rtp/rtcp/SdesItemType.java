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

import java.util.HashMap;
import java.util.Map;

public enum SdesItemType
{
    EMPTY(0),
    UNKNOWN(-1),
    CNAME(1);

    private final int value;

    SdesItemType(int value)
    {
        this.value = value;
    }

    public int getValue()
    {
        return value;
    }

    private static final Map<Integer, SdesItemType> map = new HashMap<>();

    static
    {
        for (SdesItemType type : values())
        {
            map.put(type.value, type);
        }
    }

    public static SdesItemType fromInt(int type)
    {
        return map.getOrDefault(type, UNKNOWN);
    }
}
