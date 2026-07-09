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
import org.jitsi.srtp.SrtcpCryptoContext;
import org.jitsi.srtp.SrtpContextFactory;
import org.jitsi.srtp.SrtpErrorStatus;
import org.jitsi.utils.logging2.Logger;

import java.security.GeneralSecurityException;

/**
 * A transformer which decrypts SRTCP packets.
 */
public class SrtcpDecryptTransformer extends SrtcpTransformer
{
    public SrtcpDecryptTransformer(SrtpContextFactory contextFactory, Logger parentLogger)
    {
        super(contextFactory, parentLogger);
    }

    @Override
    protected SrtpErrorStatus transform(PacketInfo packetInfo, SrtcpCryptoContext context) throws GeneralSecurityException
    {
        SrtpErrorStatus status = context.reverseTransformPacket(packetInfo.getPacket());
        packetInfo.resetPayloadVerification();
        return status;
    }
}
