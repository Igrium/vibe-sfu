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

import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.DTLSClientProtocol;
import org.bouncycastle.tls.DTLSTransport;
import org.bouncycastle.tls.DatagramTransport;
import org.jitsi.nlj.srtp.TlsRole;
import org.jitsi.utils.logging2.Logger;

import java.util.function.Consumer;

public class DtlsClient implements DtlsRole
{
    private final DatagramTransport datagramTransport;
    /**
     * A callback invoked with (chosenSrtpProtectionProfile, tlsRole, keyingMaterial) once the handshake completes.
     */
    private final HandshakeCompleteHandler handshakeCompleteHandler;
    private final Logger logger;
    private final DTLSClientProtocol dtlsClientProtocol;

    private final TlsClientImpl tlsClient;

    public DtlsClient(DatagramTransport datagramTransport, CertificateInfo certificateInfo, Logger parentLogger)
    {
        this(datagramTransport, certificateInfo, (profile, role, keyingMaterial) -> { }, cert -> { }, parentLogger, new DTLSClientProtocol());
    }

    public DtlsClient(
        DatagramTransport datagramTransport,
        CertificateInfo certificateInfo,
        HandshakeCompleteHandler handshakeCompleteHandler,
        Consumer<Certificate> verifyAndValidateRemoteCertificate,
        Logger parentLogger)
    {
        this(datagramTransport, certificateInfo, handshakeCompleteHandler, verifyAndValidateRemoteCertificate, parentLogger, new DTLSClientProtocol());
    }

    public DtlsClient(
        DatagramTransport datagramTransport,
        CertificateInfo certificateInfo,
        HandshakeCompleteHandler handshakeCompleteHandler,
        Consumer<Certificate> verifyAndValidateRemoteCertificate,
        Logger parentLogger,
        DTLSClientProtocol dtlsClientProtocol)
    {
        this.datagramTransport = datagramTransport;
        this.handshakeCompleteHandler = handshakeCompleteHandler;
        this.dtlsClientProtocol = dtlsClientProtocol;
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.tlsClient = new TlsClientImpl(certificateInfo, verifyAndValidateRemoteCertificate, logger);
    }

    @Override
    public DTLSTransport start()
    {
        return connect();
    }

    public DTLSTransport connect()
    {
        try
        {
            DTLSTransport transport = dtlsClientProtocol.connect(this.tlsClient, datagramTransport);
            logger.debug(() -> "DTLS handshake finished");
            handshakeCompleteHandler.handshakeComplete(
                tlsClient.getChosenSrtpProtectionProfile(),
                TlsRole.CLIENT,
                tlsClient.getSrtpKeyingMaterial()
            );
            return transport;
        }
        catch (Exception e)
        {
            logger.error(() -> "Error during DTLS connection: " + e);
            if (e instanceof RuntimeException)
            {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    public interface HandshakeCompleteHandler
    {
        void handshakeComplete(int chosenSrtpProtectionProfile, TlsRole tlsRole, byte[] keyingMaterial);
    }
}
