/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.nlj.srtp;

import org.bouncycastle.tls.SRTPProtectionProfile;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Replaces the upstream {@code jitsi-metaconfig}-based {@code SrtpConfig}. Since this port does not use
 * {@code jitsi-metaconfig}/HOCON, this class simply hardcodes the upstream default values (see
 * {@code reference.conf}'s {@code jmt.srtp} section).
 *
 * Note: the protection profile defaults are expressed directly via {@link SRTPProtectionProfile} constants
 * (rather than routed through {@link SrtpUtil#getSrtpProtectionProfileFromName(String)}, as upstream's
 * {@code convertFrom} does) to avoid a static-initialization ordering cycle between this class and
 * {@link SrtpUtil} (whose own static initializer reads {@link #factoryClass}). The resulting values are
 * identical.
 */
public class SrtpConfig
{
    /**
     * {@code jmt.srtp.max-consecutive-packets-discarded-early} default.
     */
    public static final int maxConsecutivePacketsDiscardedEarly = -1;

    /**
     * {@code jmt.srtp.protection-profiles} default: {@code ["SRTP_AEAD_AES_128_GCM", "SRTP_AES128_CM_HMAC_SHA1_80"]}.
     */
    public static final List<Integer> protectionProfiles = Collections.unmodifiableList(Arrays.asList(
        SRTPProtectionProfile.SRTP_AEAD_AES_128_GCM,
        SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80
    ));

    /**
     * {@code jmt.srtp.factory-class} default.
     */
    public static final String factoryClass = "OpenSSL";
}
