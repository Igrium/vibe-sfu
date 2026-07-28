/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
import org.jitsi.srtp.SrtpContextFactory;
import org.jitsi.srtp.SrtpPolicy;
import org.jitsi.srtp.crypto.Aes;
import org.jitsi.utils.logging2.Logger;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

public class SrtpUtil
{
    static
    {
        if (SrtpConfig.factoryClass != null)
        {
            Aes.setFactoryClassName(SrtpConfig.factoryClass);
        }
    }

    public static int getSrtpProtectionProfileFromName(String profileName)
    {
        switch (profileName)
        {
            case "SRTP_AES128_CM_HMAC_SHA1_80":
                return SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80;
            case "SRTP_AES128_CM_HMAC_SHA1_32":
                return SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_32;
            case "SRTP_NULL_HMAC_SHA1_32":
                return SRTPProtectionProfile.SRTP_NULL_HMAC_SHA1_32;
            case "SRTP_NULL_HMAC_SHA1_80":
                return SRTPProtectionProfile.SRTP_NULL_HMAC_SHA1_80;
            case "SRTP_AEAD_AES_128_GCM":
                return SRTPProtectionProfile.SRTP_AEAD_AES_128_GCM;
            case "SRTP_AEAD_AES_256_GCM":
                return SRTPProtectionProfile.SRTP_AEAD_AES_256_GCM;
            default:
                throw new IllegalArgumentException("Unsupported SRTP protection profile: " + profileName);
        }
    }

    public static SrtpProfileInformation getSrtpProfileInformationFromSrtpProtectionProfile(int srtpProtectionProfile)
    {
        switch (srtpProtectionProfile)
        {
            case SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_32:
                return new SrtpProfileInformation(
                    128 / 8,
                    112 / 8,
                    SrtpPolicy.AESCM_ENCRYPTION,
                    SrtpPolicy.HMACSHA1_AUTHENTICATION,
                    160 / 8,
                    80 / 8,
                    32 / 8
                );
            case SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80:
                return new SrtpProfileInformation(
                    128 / 8,
                    112 / 8,
                    SrtpPolicy.AESCM_ENCRYPTION,
                    SrtpPolicy.HMACSHA1_AUTHENTICATION,
                    160 / 8,
                    80 / 8,
                    80 / 8
                );
            case SRTPProtectionProfile.SRTP_NULL_HMAC_SHA1_32:
                return new SrtpProfileInformation(
                    0,
                    0,
                    SrtpPolicy.NULL_ENCRYPTION,
                    SrtpPolicy.HMACSHA1_AUTHENTICATION,
                    160 / 8,
                    80 / 8,
                    32 / 8
                );
            case SRTPProtectionProfile.SRTP_NULL_HMAC_SHA1_80:
                return new SrtpProfileInformation(
                    0,
                    0,
                    SrtpPolicy.NULL_ENCRYPTION,
                    SrtpPolicy.HMACSHA1_AUTHENTICATION,
                    160 / 8,
                    80 / 8,
                    80 / 8
                );
            case SRTPProtectionProfile.SRTP_AEAD_AES_128_GCM:
                return new SrtpProfileInformation(
                    128 / 8,
                    96 / 8,
                    SrtpPolicy.AESGCM_ENCRYPTION,
                    SrtpPolicy.NULL_AUTHENTICATION,
                    0,
                    128 / 8,
                    128 / 8
                );
            case SRTPProtectionProfile.SRTP_AEAD_AES_256_GCM:
                return new SrtpProfileInformation(
                    256 / 8,
                    96 / 8,
                    SrtpPolicy.AESGCM_ENCRYPTION,
                    SrtpPolicy.NULL_AUTHENTICATION,
                    0,
                    128 / 8,
                    128 / 8
                );
            default:
                throw new IllegalArgumentException("Unsupported SRTP protection profile: " + srtpProtectionProfile);
        }
    }

    public static SrtpTransformers initializeTransformer(
        SrtpProfileInformation srtpProfileInformation,
        byte[] keyingMaterial,
        TlsRole tlsRole,
        boolean cryptex,
        Logger parentLogger) throws GeneralSecurityException
    {
        byte[] clientWriteSrtpMasterKey = new byte[srtpProfileInformation.getCipherKeyLength()];
        byte[] serverWriteSrtpMasterKey = new byte[srtpProfileInformation.getCipherKeyLength()];
        byte[] clientWriterSrtpMasterSalt = new byte[srtpProfileInformation.getCipherSaltLength()];
        byte[] serverWriterSrtpMasterSalt = new byte[srtpProfileInformation.getCipherSaltLength()];
        List<byte[]> keyingMaterialValues = Arrays.asList(
            clientWriteSrtpMasterKey,
            serverWriteSrtpMasterKey,
            clientWriterSrtpMasterSalt,
            serverWriterSrtpMasterSalt
        );

        int keyingMaterialOffset = 0;
        for (byte[] keyingMaterialValue : keyingMaterialValues)
        {
            System.arraycopy(
                keyingMaterial,
                keyingMaterialOffset,
                keyingMaterialValue,
                0,
                keyingMaterialValue.length
            );
            keyingMaterialOffset += keyingMaterialValue.length;
        }

        SrtpPolicy srtcpPolicy = new SrtpPolicy(
            srtpProfileInformation.getCipherName(),
            srtpProfileInformation.getCipherKeyLength(),
            srtpProfileInformation.getAuthFunctionName(),
            srtpProfileInformation.getAuthKeyLength(),
            srtpProfileInformation.getRtcpAuthTagLength(),
            srtpProfileInformation.getCipherSaltLength()
        );
        SrtpPolicy srtpPolicy = new SrtpPolicy(
            srtpProfileInformation.getCipherName(),
            srtpProfileInformation.getCipherKeyLength(),
            srtpProfileInformation.getAuthFunctionName(),
            srtpProfileInformation.getAuthKeyLength(),
            srtpProfileInformation.getRtpAuthTagLength(),
            srtpProfileInformation.getCipherSaltLength()
        );

        /* To support RetransmissionSender.retransmitPlain, we need to disable
           send-side SRTP replay protection. */
        /* TODO: disable this only in cases where we actually need to use retransmitPlain? */
        srtpPolicy.setSendReplayEnabled(false);

        srtpPolicy.setCryptexEnabled(cryptex);

        SrtpContextFactory clientSrtpContextFactory = new SrtpContextFactory(
            tlsRole == TlsRole.CLIENT,
            clientWriteSrtpMasterKey,
            clientWriterSrtpMasterSalt,
            srtpPolicy,
            srtcpPolicy,
            parentLogger
        );
        SrtpContextFactory serverSrtpContextFactory = new SrtpContextFactory(
            tlsRole == TlsRole.SERVER,
            serverWriteSrtpMasterKey,
            serverWriterSrtpMasterSalt,
            srtpPolicy,
            srtcpPolicy,
            parentLogger
        );
        SrtpContextFactory forwardSrtpContextFactory;
        SrtpContextFactory reverseSrtpContextFactory;

        switch (tlsRole)
        {
            case CLIENT:
                forwardSrtpContextFactory = clientSrtpContextFactory;
                reverseSrtpContextFactory = serverSrtpContextFactory;
                break;
            case SERVER:
                forwardSrtpContextFactory = serverSrtpContextFactory;
                reverseSrtpContextFactory = clientSrtpContextFactory;
                break;
            default:
                throw new IllegalStateException();
        }

        return new SrtpTransformers(
            new SrtpDecryptTransformer(reverseSrtpContextFactory, parentLogger),
            new SrtpEncryptTransformer(forwardSrtpContextFactory, parentLogger),
            new SrtcpDecryptTransformer(reverseSrtpContextFactory, parentLogger),
            new SrtcpEncryptTransformer(forwardSrtpContextFactory, parentLogger)
        );
    }
}
