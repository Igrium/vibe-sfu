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

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AudioPayloadType extends PayloadType
{
    public static final int DEFAULT_CLOCK_RATE = 48000;

    /**
     * The number of channels
     */
    private final int channels;

    protected AudioPayloadType(byte pt, PayloadTypeEncoding encoding, int clockRate, int channels, Map<String, String> parameters)
    {
        super(pt, encoding, MediaType.AUDIO, clockRate, parameters, Collections.emptySet());
        this.channels = channels;
    }

    protected AudioPayloadType(byte pt, PayloadTypeEncoding encoding)
    {
        this(pt, encoding, DEFAULT_CLOCK_RATE, 1, new ConcurrentHashMap<>());
    }

    public int getChannels()
    {
        return channels;
    }

    @Override
    public String channelsString()
    {
        return channels > 1 ? "/" + channels : "";
    }
}
