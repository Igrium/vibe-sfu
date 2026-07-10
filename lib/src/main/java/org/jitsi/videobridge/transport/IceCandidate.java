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

import com.google.common.net.InetAddresses;
import org.ice4j.ice.CandidateType;

/**
 * A plain, programmatic replacement for the jingle {@code IceCandidatePacketExtension} /
 * {@code CandidatePacketExtension} model. Describes a single ICE candidate for the library's
 * programmatic signalling API (as opposed to XMPP/Jingle signalling used upstream).
 */
public class IceCandidate implements Comparable<IceCandidate>
{
    public int component;
    public String foundation;
    public int generation;
    public String id;
    public int network;
    public long priority;
    public String protocol;
    public CandidateType type;
    public String ip;
    public int port;
    public String relAddr;
    public int relPort = -1;

    public IceCandidate()
    {
    }

    /**
     * Returns whether this candidate's IP address is not a literal IP address (and therefore
     * would need to be resolved).
     */
    public boolean ipNeedsResolution()
    {
        return !InetAddresses.isInetAddress(ip);
    }

    private static int typeOrdinal(CandidateType type)
    {
        if (type == null)
        {
            return 4;
        }
        if (type == CandidateType.HOST_CANDIDATE)
        {
            return 0;
        }
        else if (type == CandidateType.SERVER_REFLEXIVE_CANDIDATE)
        {
            return 1;
        }
        else if (type == CandidateType.PEER_REFLEXIVE_CANDIDATE)
        {
            return 2;
        }
        else if (type == CandidateType.RELAYED_CANDIDATE)
        {
            return 3;
        }
        return 4;
    }

    @Override
    public int compareTo(IceCandidate o)
    {
        return Integer.compare(typeOrdinal(this.type), typeOrdinal(o.type));
    }
}
