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

package org.jitsi.nlj.util;

import org.jitsi.nlj.rtp.SsrcAssociationType;

public abstract class SsrcAssociation
{
    private final long primarySsrc;
    private final long secondarySsrc;
    private final SsrcAssociationType type;

    protected SsrcAssociation(long primarySsrc, long secondarySsrc, SsrcAssociationType type)
    {
        this.primarySsrc = primarySsrc;
        this.secondarySsrc = secondarySsrc;
        this.type = type;
    }

    public long getPrimarySsrc()
    {
        return primarySsrc;
    }

    public long getSecondarySsrc()
    {
        return secondarySsrc;
    }

    public SsrcAssociationType getType()
    {
        return type;
    }

    @Override
    public String toString()
    {
        return secondarySsrc + " -> " + primarySsrc + " (" + type + ")";
    }
}
