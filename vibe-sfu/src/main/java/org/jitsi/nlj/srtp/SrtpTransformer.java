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
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.srtp.SrtpContextFactory;
import org.jitsi.srtp.SrtpCryptoContext;
import org.jitsi.utils.logging2.Logger;

import java.security.GeneralSecurityException;

/**
 * Implements methods common for the two SRTP transformer implementations.
 */
public abstract class SrtpTransformer extends AbstractSrtpTransformer<SrtpCryptoContext>
{
    protected SrtpTransformer(SrtpContextFactory contextFactory, Logger logger)
    {
        super(contextFactory, logger);
    }

    @Override
    protected SrtpCryptoContext deriveContext(long ssrc, long index) throws GeneralSecurityException
    {
        return contextFactory.deriveContext((int) ssrc, 0);
    }

    @Override
    protected SrtpCryptoContext getContext(PacketInfo packetInfo) throws GeneralSecurityException
    {
        Packet packet = packetInfo.getPacket();
        if (!(packet instanceof RtpPacket))
        {
            logger.warn(() -> "Can not handle non-RTP packet: " + packet.getClass());
            return null;
        }
        RtpPacket rtpPacket = (RtpPacket) packet;
        return getContext(rtpPacket.getSsrc(), rtpPacket.getSequenceNumber());
    }
}
