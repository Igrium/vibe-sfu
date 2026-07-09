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
package org.jitsi.nlj.dtls;

import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.ExporterLabel;
import org.bouncycastle.tls.ExtensionType;
import org.bouncycastle.tls.HashAlgorithm;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.SignatureAlgorithm;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsSRTPUtils;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.TlsSession;
import org.bouncycastle.tls.TlsUtils;
import org.bouncycastle.tls.UseSRTPData;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;
import org.jitsi.nlj.srtp.SrtpConfig;
import org.jitsi.nlj.srtp.SrtpUtil;
import org.jitsi.rtp.extensions.ByteBufferExtensions;
import org.jitsi.utils.logging2.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Hashtable;
import java.util.function.Consumer;

/**
 * Implementation of {@link DefaultTlsClient}.
 */
public class TlsClientImpl extends DefaultTlsClient
{
    private final CertificateInfo certificateInfo;

    /**
     * The function to call when the server certificateInfo is available.
     */
    private final Consumer<Certificate> notifyServerCertificate;

    private final Logger logger;

    private TlsSession session;

    private TlsCredentials clientCredentials;

    /**
     * Only set after a handshake has completed
     */
    private byte[] srtpKeyingMaterial;

    private int chosenSrtpProtectionProfile = 0;

    public TlsClientImpl(CertificateInfo certificateInfo, Consumer<Certificate> notifyServerCertificate, Logger parentLogger)
    {
        super(DtlsUtils.BC_TLS_CRYPTO);
        this.certificateInfo = certificateInfo;
        this.notifyServerCertificate = notifyServerCertificate;
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    public byte[] getSrtpKeyingMaterial()
    {
        return srtpKeyingMaterial;
    }

    public int getChosenSrtpProtectionProfile()
    {
        return chosenSrtpProtectionProfile;
    }

    @Override
    public TlsSession getSessionToResume()
    {
        return session;
    }

    @Override
    public TlsAuthentication getAuthentication()
    {
        return new TlsAuthentication()
        {
            @Override
            public TlsCredentials getClientCredentials(CertificateRequest certificateRequest) throws IOException
            {
                // NOTE: can't set clientCredentials when it is declared because 'context' won't be set yet
                if (clientCredentials == null)
                {
                    clientCredentials = new BcDefaultTlsCredentialedSigner(
                        new TlsCryptoParameters(context),
                        (BcTlsCrypto) context.getCrypto(),
                        PrivateKeyFactory.createKey(certificateInfo.getKeyPair().getPrivate().getEncoded()),
                        certificateInfo.getCertificate(),
                        TlsUtils.isSignatureAlgorithmsExtensionAllowed(context.getServerVersion())
                            ? new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa)
                            : null
                    );
                }
                return clientCredentials;
            }

            @Override
            public void notifyServerCertificate(TlsServerCertificate serverCertificate)
            {
                TlsClientImpl.this.notifyServerCertificate.accept(serverCertificate.getCertificate());
            }
        };
    }

    @Override
    public Hashtable<Integer, byte[]> getClientExtensions() throws IOException
    {
        @SuppressWarnings("unchecked")
        Hashtable<Integer, byte[]> clientExtensions = (Hashtable<Integer, byte[]>) super.getClientExtensions();
        if (TlsSRTPUtils.getUseSRTPExtension(clientExtensions) == null)
        {
            if (clientExtensions == null)
            {
                clientExtensions = new Hashtable<>();
            }

            int[] profiles = SrtpConfig.protectionProfiles.stream().mapToInt(Integer::intValue).toArray();
            TlsSRTPUtils.addUseSRTPExtension(
                clientExtensions,
                new UseSRTPData(profiles, TlsUtils.EMPTY_BYTES)
            );
        }
        clientExtensions.put(ExtensionType.renegotiation_info, new byte[] { 0 });

        return clientExtensions;
    }

    @Override
    public void processServerExtensions(Hashtable serverExtensions) throws IOException
    {
        // TODO: a few cases we should be throwing alerts for in here.  see old TlsClientImpl
        UseSRTPData useSRTPData = TlsSRTPUtils.getUseSRTPExtension(serverExtensions);
        int[] protectionProfiles = useSRTPData.getProtectionProfiles();
        java.util.List<Integer> theirs = new java.util.ArrayList<>();
        for (int p : protectionProfiles)
        {
            theirs.add(p);
        }
        chosenSrtpProtectionProfile = DtlsUtils.chooseSrtpProtectionProfile(SrtpConfig.protectionProfiles, theirs);
    }

    @Override
    public int[] getCipherSuites()
    {
        return DtlsConfig.config.getCipherSuites().stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public int getHandshakeTimeoutMillis()
    {
        return (int) DtlsConfig.config.getHandshakeTimeout().toMillis();
    }

    @Override
    public void notifyHandshakeComplete() throws IOException
    {
        super.notifyHandshakeComplete();
        logger.info(() -> "Negotiated DTLS version " + context.getSecurityParameters().getNegotiatedVersion());
        TlsSession newSession = context.getResumableSession();
        if (newSession != null)
        {
            if (session != null && session.getSessionID() != null &&
                java.util.Arrays.equals(session.getSessionID(), newSession.getSessionID()))
            {
                logger.debug(() -> {
                    String newSessionIdHex = ByteBufferExtensions.toHex(ByteBuffer.wrap(newSession.getSessionID()));
                    return "Resumed DTLS session " + newSessionIdHex;
                });
            }
            else
            {
                logger.debug(() -> {
                    String newSessionIdHex = ByteBufferExtensions.toHex(ByteBuffer.wrap(newSession.getSessionID()));
                    return "Established DTLS session " + newSessionIdHex;
                });
                this.session = newSession;
            }
        }
        org.jitsi.nlj.srtp.SrtpProfileInformation srtpProfileInformation =
            SrtpUtil.getSrtpProfileInformationFromSrtpProtectionProfile(chosenSrtpProtectionProfile);
        srtpKeyingMaterial = context.exportKeyingMaterial(
            ExporterLabel.dtls_srtp,
            null,
            2 * (srtpProfileInformation.getCipherKeyLength() + srtpProfileInformation.getCipherSaltLength())
        );
    }

    @Override
    protected ProtocolVersion[] getSupportedVersions()
    {
        return new ProtocolVersion[] { ProtocolVersion.DTLSv12 };
    }

    @Override
    public void notifyAlertRaised(short alertLevel, short alertDescription, String message, Throwable cause)
    {
        DtlsUtils.notifyAlertRaised(logger, alertLevel, alertDescription, message, cause);
    }

    @Override
    public void notifyAlertReceived(short alertLevel, short alertDescription)
    {
        DtlsUtils.notifyAlertReceived(logger, alertLevel, alertDescription);
    }
}
