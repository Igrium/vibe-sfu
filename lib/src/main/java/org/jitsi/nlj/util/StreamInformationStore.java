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

import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.rtp.RtpExtension;
import org.jitsi.utils.MediaType;

/**
 * A writable stream information store
 */
public interface StreamInformationStore extends ReadOnlyStreamInformationStore
{
    void addRtpExtensionMapping(RtpExtension rtpExtension);
    void clearRtpExtensions();

    void addRtpPayloadType(PayloadType payloadType);
    void clearRtpPayloadTypes();

    void setExtmapAllowMixed(boolean allow);

    void addSsrcAssociation(SsrcAssociation ssrcAssociation);

    void addReceiveSsrc(long ssrc, MediaType mediaType);
    void removeReceiveSsrc(long ssrc);
}
