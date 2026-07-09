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

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Vp8PayloadType extends VideoPayloadType
{
    public Vp8PayloadType(byte pt)
    {
        this(pt, new ConcurrentHashMap<>(), Collections.emptySet());
    }

    public Vp8PayloadType(byte pt, Map<String, String> parameters)
    {
        this(pt, parameters, Collections.emptySet());
    }

    public Vp8PayloadType(byte pt, Map<String, String> parameters, Set<String> rtcpFeedbackSet)
    {
        super(pt, PayloadTypeEncoding.VP8, DEFAULT_CLOCK_RATE, parameters, rtcpFeedbackSet);
    }
}
