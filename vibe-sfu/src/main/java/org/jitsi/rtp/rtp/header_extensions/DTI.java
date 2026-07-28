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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Decode target indication */
public enum DTI
{
    NOT_PRESENT(0),
    DISCARDABLE(1),
    SWITCH(2),
    REQUIRED(3);

    private final int dti;

    DTI(int dti)
    {
        this.dti = dti;
    }

    public int getDti()
    {
        return dti;
    }

    private static final Map<Integer, DTI> map = new HashMap<>();

    static
    {
        for (DTI value : DTI.values())
        {
            map.put(value.dti, value);
        }
    }

    public static DTI fromInt(int type)
    {
        DTI value = map.get(type);
        if (value == null)
        {
            throw new IllegalArgumentException("Bad DTI " + type);
        }
        return value;
    }

    public String toShortString()
    {
        switch (this)
        {
        case NOT_PRESENT:
            return "N";
        case DISCARDABLE:
            return "D";
        case SWITCH:
            return "S";
        case REQUIRED:
            return "R";
        default:
            throw new IllegalStateException("Unknown DTI " + this);
        }
    }

    public static String toShortString(List<DTI> dtis)
    {
        StringBuilder sb = new StringBuilder();
        for (DTI dti : dtis)
        {
            sb.append(dti.toShortString());
        }
        return sb.toString();
    }
}
