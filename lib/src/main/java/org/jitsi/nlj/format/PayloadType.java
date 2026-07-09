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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Represents an RTP payload type.
 *
 * @author Boris Grozev
 * @author Brian Baldino
 */
public abstract class PayloadType
{
    /**
     *  The 7-bit RTP payload type number.
     */
    private final byte pt;

    /**
     * The encoding name.
     */
    private final PayloadTypeEncoding encoding;

    /**
     * The media type (audio or video).
     */
    private final MediaType mediaType;

    /**
     * The RTP clock rate.
     */
    private final int clockRate;

    /**
     * Additional parameters associated with the payload type (e.g. the "apt" used for RTX).
     */
    private final Map<String, String> parameters;

    /**
     * The rtcp feedback messages associated with the payload type (e.g. nack, nack pli, transport-cc, goog-remb,
     * ccm fir, etc).
     */
    private final Set<String> rtcpFeedbackSet;

    protected PayloadType(
        byte pt,
        PayloadTypeEncoding encoding,
        MediaType mediaType,
        int clockRate,
        Map<String, String> parameters,
        Set<String> rtcpFeedbackSet)
    {
        this.pt = pt;
        this.encoding = encoding;
        this.mediaType = mediaType;
        this.clockRate = clockRate;
        this.parameters = parameters;
        this.rtcpFeedbackSet = new CopyOnWriteArraySet<>(rtcpFeedbackSet);
    }

    protected PayloadType(byte pt, PayloadTypeEncoding encoding, MediaType mediaType, int clockRate)
    {
        this(pt, encoding, mediaType, clockRate, new ConcurrentHashMap<>(), Collections.emptySet());
    }

    public byte getPt()
    {
        return pt;
    }

    public PayloadTypeEncoding getEncoding()
    {
        return encoding;
    }

    public MediaType getMediaType()
    {
        return mediaType;
    }

    public int getClockRate()
    {
        return clockRate;
    }

    public Map<String, String> getParameters()
    {
        return parameters;
    }

    public Set<String> getRtcpFeedbackSet()
    {
        return rtcpFeedbackSet;
    }

    @Override
    public String toString()
    {
        StringBuffer sb = new StringBuffer();
        sb.append(pt).append(" -> ").append(encodingName())
            .append(" (").append(clockRate)
            .append(channelsString())
            .append("): ").append(parameters);
        return sb.toString();
    }

    public String encodingName()
    {
        return encoding.name().toLowerCase();
    }

    public String channelsString()
    {
        return "";
    }

    public static boolean supportsPli(Set<String> rtcpFeedbackSet)
    {
        return rtcpFeedbackSet.contains("nack pli");
    }

    public static boolean supportsFir(Set<String> rtcpFeedbackSet)
    {
        return rtcpFeedbackSet.contains("ccm fir");
    }

    public static boolean supportsRemb(Set<String> rtcpFeedbackSet)
    {
        return rtcpFeedbackSet.contains("goog-remb");
    }

    public static boolean supportsTcc(Set<String> rtcpFeedbackSet)
    {
        return rtcpFeedbackSet.contains("transport-cc");
    }
}
