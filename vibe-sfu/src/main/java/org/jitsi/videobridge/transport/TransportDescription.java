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

package org.jitsi.videobridge.transport;

import java.util.ArrayList;
import java.util.List;

/**
 * A plain, programmatic replacement for the jingle {@code IceUdpTransportPacketExtension}. This is
 * the bidirectional container of ICE + DTLS transport parameters used by the library's programmatic
 * signalling API. It is written by {@code describe()} methods (to be sent to the remote side) and
 * read by {@code startConnectivityEstablishment()} / {@code setRemoteFingerprints()} (parameters
 * received from the remote side).
 */
public class TransportDescription
{
    public String ufrag;
    public String password;
    public boolean rtcpMux = true;
    public List<IceCandidate> candidates = new ArrayList<>();
    public List<DtlsFingerprint> fingerprints = new ArrayList<>();

    public TransportDescription()
    {
    }
}
