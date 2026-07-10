/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.nlj.rtp;

import org.jitsi.nlj.RtpEncodingDesc;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.rtp.rtp.RtpPacket;

import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * A packet which we know contains video, but we've not yet
 * parsed (i.e. we don't know information gained from
 * parsing codec-specific data).
 */
public class VideoRtpPacket extends RtpPacket
{
    /** The encoding ID of this packet. */
    private int encodingId;

    public VideoRtpPacket(byte[] buffer, int offset, int length)
    {
        this(buffer, offset, length, RtpLayerDesc.SUSPENDED_ENCODING_ID);
    }

    public VideoRtpPacket(byte[] buffer, int offset, int length, int encodingId)
    {
        super(buffer, offset, length);
        this.encodingId = encodingId;
    }

    public int getEncodingId()
    {
        return encodingId;
    }

    public void setEncodingId(int encodingId)
    {
        this.encodingId = encodingId;
    }

    public Collection<Integer> getLayerIds()
    {
        return Collections.singletonList(0);
    }

    /**
     * (Library note: ported from the top-level extension function {@code VideoRtpPacket.getEncodingIds()} defined
     * in upstream {@code RtpEncodingDesc.kt}; relocated here onto its receiver type since Java has no top-level
     * extension functions.)
     */
    public Collection<Long> getEncodingIds()
    {
        return getLayerIds().stream()
            .map(layerId -> RtpEncodingDesc.calcEncodingId(getSsrc(), layerId))
            .collect(Collectors.toList());
    }

    @Override
    public String toString()
    {
        return super.toString() + ", EncID=" + encodingId;
    }

    @Override
    public VideoRtpPacket clone()
    {
        VideoRtpPacket clone = new VideoRtpPacket(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length,
            encodingId
        );
        postClone(clone);
        return clone;
    }
}
