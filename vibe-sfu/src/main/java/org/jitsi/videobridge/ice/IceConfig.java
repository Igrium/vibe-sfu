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

package org.jitsi.videobridge.ice;

import org.ice4j.ice.KeepAliveStrategy;
import org.ice4j.ice.NominationStrategy;

/**
 * Plain Java replacement for the upstream metaconfig-backed {@code IceConfig}. Holds the
 * upstream default values as final fields (this library has no HOCON configuration system).
 */
public class IceConfig
{
    @SuppressWarnings("checkstyle:VisibilityModifier")
    public static final IceConfig config = new IceConfig();

    /**
     * The ICE UDP port.
     */
    public final int port = 10000;

    /**
     * The prefix to STUN username fragments we generate.
     */
    public final String ufragPrefix = null;

    public final KeepAliveStrategy keepAliveStrategy = KeepAliveStrategy.fromString("selected_only");

    public final boolean resolveRemoteCandidates = false;

    /**
     * The ice4j nomination strategy policy.
     */
    public final NominationStrategy nominationStrategy =
        NominationStrategy.fromString("NominateFirstHostOrReflexiveValid");

    /**
     * Whether to advertise ICE candidates with private IP addresses (RFC1918 IPv4 addresses and
     * fec0::/10 or fc00::/7 IPv6 addresses) even to endpoints that have not signaled support for
     * private addresses.
     */
    public final boolean advertisePrivateCandidates = true;

    private IceConfig()
    {
    }
}
