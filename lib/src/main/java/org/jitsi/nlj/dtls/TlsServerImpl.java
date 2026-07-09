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

package org.jitsi.nlj.dtls;

import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.ClientCertificateType;
import org.bouncycastle.tls.DefaultTlsServer;
import org.bouncycastle.tls.ExporterLabel;
import org.bouncycastle.tls.HashAlgorithm;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.SessionParameters;
import org.bouncycastle.tls.SignatureAlgorithm;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.TlsCredentialedDecryptor;
import org.bouncycastle.tls.TlsCredentialedSigner;
import org.bouncycastle.tls.TlsSRTPUtils;
import org.bouncycastle.tls.TlsSession;
import org.bouncycastle.tls.TlsUtils;
import org.bouncycastle.tls.UseSRTPData;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.TlsSecret;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedDecryptor;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;
import org.jitsi.nlj.srtp.SrtpConfig;
import org.jitsi.nlj.srtp.SrtpProfileInformation;
import org.jitsi.nlj.srtp.SrtpUtil;
import org.jitsi.rtp.extensions.ByteBufferExtensions;
import org.jitsi.utils.logging2.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Vector;
import java.util.function.Consumer;

public class TlsServerImpl extends DefaultTlsServer
{
    private final CertificateInfo certificateInfo;

    /**
     * The function to call when the client certificateInfo is available.
     */
    private final Consumer<Certificate> notifyClientCertificateReceived;

    private final Logger logger;

    private TlsSession session;

    /**
     * Only set after a handshake has completed
     */
    private byte[] srtpKeyingMaterial;

    private int chosenSrtpProtectionProfile = 0;

    public TlsServerImpl(CertificateInfo certificateInfo, Consumer<Certificate> notifyClientCertificateReceived, Logger parentLogger)
    {
        super(DtlsUtils.BC_TLS_CRYPTO);
        this.certificateInfo = certificateInfo;
        this.notifyClientCertificateReceived = notifyClientCertificateReceived;
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
    public TlsSession getSessionToResume(byte[] sessionID)
    {
        return session;
        // TODO: do we need to map multiple sessions (per sessionID?)
        // return super.getSessionToResume(sessionID);
    }

    @Override
    public Hashtable<Integer, byte[]> getServerExtensions() throws IOException
    {
        @SuppressWarnings("unchecked")
        Hashtable<Integer, byte[]> extensions = (Hashtable<Integer, byte[]>) super.getServerExtensions();
        if (extensions == null)
        {
            extensions = new Hashtable<>();
        }
        if (TlsSRTPUtils.getUseSRTPExtension(extensions) == null)
        {
            TlsSRTPUtils.addUseSRTPExtension(
                extensions,
                new UseSRTPData(new int[] { chosenSrtpProtectionProfile }, TlsUtils.EMPTY_BYTES)
            );
        }
        return extensions;
    }

    @Override
    public void processClientExtensions(Hashtable clientExtensions) throws IOException
    {
        super.processClientExtensions(clientExtensions);
        UseSRTPData useSRTPData = TlsSRTPUtils.getUseSRTPExtension(clientExtensions);
        int[] protectionProfiles = useSRTPData.getProtectionProfiles();
        List<Integer> theirs = new ArrayList<>();
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
    protected TlsCredentialedDecryptor getRSAEncryptionCredentials() throws IOException
    {
        return new BcDefaultTlsCredentialedDecryptor(
            (BcTlsCrypto) context.getCrypto(),
            certificateInfo.getCertificate(),
            PrivateKeyFactory.createKey(certificateInfo.getKeyPair().getPrivate().getEncoded())
        );
    }

    @Override
    protected TlsCredentialedSigner getECDSASignerCredentials() throws IOException
    {
        return new BcDefaultTlsCredentialedSigner(
            new TlsCryptoParameters(context),
            (BcTlsCrypto) context.getCrypto(),
            PrivateKeyFactory.createKey(certificateInfo.getKeyPair().getPrivate().getEncoded()),
            certificateInfo.getCertificate(),
            new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa)
        );
    }

    @Override
    public CertificateRequest getCertificateRequest()
    {
        Vector<SignatureAndHashAlgorithm> signatureAlgorithms = new Vector<>(1);
        signatureAlgorithms.add(new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa));
        return new CertificateRequest(new short[] { ClientCertificateType.ecdsa_sign }, signatureAlgorithms, null);
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
            String newSessionIdHex = ByteBufferExtensions.toHex(ByteBuffer.wrap(newSession.getSessionID()));

            if (session != null && session.getSessionID() != null &&
                Arrays.equals(session.getSessionID(), newSession.getSessionID()))
            {
                logger.info(() -> "Resumed DTLS session " + newSessionIdHex);
            }
            else
            {
                logger.info(() -> "Established DTLS session " + newSessionIdHex);
                this.session = newSession;
            }
        }
        SrtpProfileInformation srtpProfileInformation =
            SrtpUtil.getSrtpProfileInformationFromSrtpProtectionProfile(chosenSrtpProtectionProfile);
        if (!context.getSecurityParameters().isExtendedMasterSecret())
        {
            TlsSession contextSession = context.getSession();
            if (contextSession != null)
            {
                SessionParameters sessionParameters = contextSession.exportSessionParameters();
                if (sessionParameters != null)
                {
                    TlsSecret masterSecret = sessionParameters.getMasterSecret();
                    if (masterSecret != null)
                    {
                        srtpKeyingMaterial = DtlsUtils.exportKeyingMaterial(
                            context,
                            ExporterLabel.dtls_srtp,
                            null,
                            2 * (srtpProfileInformation.getCipherKeyLength() + srtpProfileInformation.getCipherSaltLength()),
                            masterSecret
                        );
                    }
                }
            }
        }
        else
        {
            srtpKeyingMaterial = context.exportKeyingMaterial(
                ExporterLabel.dtls_srtp,
                null,
                2 * (srtpProfileInformation.getCipherKeyLength() + srtpProfileInformation.getCipherSaltLength())
            );
        }
    }

    @Override
    public void notifyClientCertificate(Certificate clientCertificate)
    {
        notifyClientCertificateReceived.accept(clientCertificate);
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

    @Override
    protected ProtocolVersion[] getSupportedVersions()
    {
        return new ProtocolVersion[] { ProtocolVersion.DTLSv12 };
    }
}
