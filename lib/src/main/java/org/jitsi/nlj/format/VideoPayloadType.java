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

import org.jitsi.utils.MediaType;

import java.util.Map;
import java.util.Set;

public abstract class VideoPayloadType extends PayloadType
{
    public static final int DEFAULT_CLOCK_RATE = 90000;

    protected VideoPayloadType(
        byte pt,
        PayloadTypeEncoding encoding,
        int clockRate,
        Map<String, String> parameters,
        Set<String> rtcpFeedbackSet)
    {
        super(pt, encoding, MediaType.VIDEO, clockRate, parameters, rtcpFeedbackSet);
    }
}
