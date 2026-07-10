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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.DebugStateMode;
import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.rtp.RtpExtension;
import org.jitsi.nlj.rtp.RtpExtensionType;
import org.jitsi.nlj.rtp.SsrcAssociationType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Makes information about stream metadata (RTP extensions, payload types,
 * etc.) available and allows interested parties to add handlers for when certain
 * information is available.
 *
 * (Deviation: upstream's {@code RtpExtensionHandler}/{@code RtpPayloadTypesChangedHandler}/
 * {@code ExtmapAllowMixedChangedHandler} typealiases become plain {@link Consumer} parameter types below, since
 * Java has no typealiases and {@code Consumer} is already the convention used elsewhere in this port.)
 */
public interface ReadOnlyStreamInformationStore
{
    List<RtpExtension> getRtpExtensions();

    void onRtpExtensionMapping(RtpExtensionType rtpExtensionType, Consumer<Integer> handler);

    Map<Byte, PayloadType> getRtpPayloadTypes();

    void onRtpPayloadTypesChanged(Consumer<Map<Byte, PayloadType>> handler);

    boolean getExtmapAllowMixed();

    void onExtmapAllowMixedChanged(Consumer<Boolean> handler);

    Long getLocalPrimarySsrc(long secondarySsrc);

    Long getRemoteSecondarySsrc(long primarySsrc, SsrcAssociationType associationType);

    ObjectNode debugState(DebugStateMode mode);

    /**
     * All signaled receive SSRCs
     */
    Set<Long> getReceiveSsrcs();

    /**
     * A list of all primary media (audio and video) SSRCs for which the
     * endpoint associated with this stream information store sends video
     * (does not include things like RTX)
     */
    Set<Long> getPrimaryMediaSsrcs();

    boolean getSupportsPli();
    boolean getSupportsFir();
    boolean getSupportsRemb();
    boolean getSupportsTcc();
}
