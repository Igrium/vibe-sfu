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
import org.jitsi.rtp.rtcp.RtcpHeader;
import org.jitsi.srtp.SrtcpCryptoContext;
import org.jitsi.srtp.SrtpContextFactory;
import org.jitsi.utils.logging2.Logger;

import java.security.GeneralSecurityException;

/**
 * Implements methods common for the two SRTCP transformer implementations.
 */
public abstract class SrtcpTransformer extends AbstractSrtpTransformer<SrtcpCryptoContext>
{
    protected SrtcpTransformer(SrtpContextFactory contextFactory, Logger logger)
    {
        super(contextFactory, logger);
    }

    @Override
    protected SrtcpCryptoContext deriveContext(long ssrc, long index) throws GeneralSecurityException
    {
        return contextFactory.deriveControlContext((int) ssrc);
    }

    @Override
    protected SrtcpCryptoContext getContext(PacketInfo packetInfo) throws GeneralSecurityException
    {
        // Contrary to RTP packets, RTCP packets do not get parsed before they are
        // decrypted. So (if this is a decrypting transformer) we are working with
        // an UnparsedPacket here and need to read the SSRC manually.
        long senderSsrc = RtcpHeader.getSenderSsrc(packetInfo.getPacket().getBuffer(), packetInfo.getPacket().getOffset());
        return getContext(senderSsrc, 0);
    }
}
