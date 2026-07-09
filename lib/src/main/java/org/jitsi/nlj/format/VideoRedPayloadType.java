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

public class VideoRedPayloadType extends VideoPayloadType
{
    public VideoRedPayloadType(byte pt)
    {
        this(pt, DEFAULT_CLOCK_RATE, new ConcurrentHashMap<>(), Collections.emptySet());
    }

    public VideoRedPayloadType(byte pt, int clockRate)
    {
        this(pt, clockRate, new ConcurrentHashMap<>(), Collections.emptySet());
    }

    public VideoRedPayloadType(byte pt, int clockRate, Map<String, String> parameters)
    {
        this(pt, clockRate, parameters, Collections.emptySet());
    }

    public VideoRedPayloadType(byte pt, int clockRate, Map<String, String> parameters, Set<String> rtcpFeedbackSet)
    {
        super(pt, PayloadTypeEncoding.RED, clockRate, parameters, rtcpFeedbackSet);
    }
}
