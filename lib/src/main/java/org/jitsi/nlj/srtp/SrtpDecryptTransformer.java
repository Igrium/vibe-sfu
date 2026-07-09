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

import org.jitsi.nlj.PacketInfo;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.srtp.SrtpContextFactory;
import org.jitsi.srtp.SrtpCryptoContext;
import org.jitsi.srtp.SrtpErrorStatus;
import org.jitsi.utils.logging2.Logger;

import java.security.GeneralSecurityException;

/**
 * A transformer which decrypts SRTP packets. Note that it expects the {@code Packet} to have already been parsed as
 * {@link RtpPacket}.
 */
public class SrtpDecryptTransformer extends SrtpTransformer
{
    public int earlyDiscardedPacketsSinceLastSuccess = 0;
    private final boolean alwaysProcess = SrtpConfig.maxConsecutivePacketsDiscardedEarly <= 0;

    public SrtpDecryptTransformer(SrtpContextFactory contextFactory, Logger parentLogger)
    {
        super(contextFactory, parentLogger);
    }

    @Override
    protected SrtpErrorStatus transform(PacketInfo packetInfo, SrtpCryptoContext context) throws GeneralSecurityException
    {
        // We want to avoid authenticating and decrypting packets that we are going to discarded (e.g. silence). We
        // can not just discard them without passing them to the SRTP stack, because this will eventually break the
        // ROC. Here we bypass the SRTP stack for packets marked to be discarded, but make sure that we haven't
        // dropped too many consecutive packets.
        if (packetInfo.isShouldDiscard())
        {
            if (alwaysProcess || earlyDiscardedPacketsSinceLastSuccess++ > SrtpConfig.maxConsecutivePacketsDiscardedEarly)
            {
                return doTransform(packetInfo, context);
            }
            else
            {
                // Bypass the SRTP stack. The packet is already marked to be discarded, so there's no error
                // condition.
                return SrtpErrorStatus.OK;
            }
        }

        return doTransform(packetInfo, context);
    }

    private SrtpErrorStatus doTransform(PacketInfo packetInfo, SrtpCryptoContext context) throws GeneralSecurityException
    {
        SrtpErrorStatus status = context.reverseTransformPacket(packetInfo.<RtpPacket>packetAs(), packetInfo.isShouldDiscard());
        packetInfo.resetPayloadVerification();
        if (status == SrtpErrorStatus.OK)
        {
            earlyDiscardedPacketsSinceLastSuccess = 0;
        }
        return status;
    }
}
