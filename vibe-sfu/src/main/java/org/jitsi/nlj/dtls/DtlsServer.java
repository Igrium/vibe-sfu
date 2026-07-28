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
import org.bouncycastle.tls.DTLSServerProtocol;
import org.bouncycastle.tls.DTLSTransport;
import org.bouncycastle.tls.DatagramTransport;
import org.jitsi.nlj.srtp.TlsRole;
import org.jitsi.utils.logging2.Logger;

import java.util.function.Consumer;

public class DtlsServer implements DtlsRole
{
    private final DatagramTransport datagramTransport;
    private final DtlsClient.HandshakeCompleteHandler handshakeCompleteHandler;
    private final Logger logger;
    private final DTLSServerProtocol dtlsServerProtocol;

    private final TlsServerImpl tlsServer;

    public DtlsServer(DatagramTransport datagramTransport, CertificateInfo certificateInfo, Logger parentLogger)
    {
        this(datagramTransport, certificateInfo, (profile, role, keyingMaterial) -> { }, cert -> { }, parentLogger, new DTLSServerProtocol());
    }

    public DtlsServer(
        DatagramTransport datagramTransport,
        CertificateInfo certificateInfo,
        DtlsClient.HandshakeCompleteHandler handshakeCompleteHandler,
        Consumer<Certificate> verifyAndValidateRemoteCertificate,
        Logger parentLogger)
    {
        this(datagramTransport, certificateInfo, handshakeCompleteHandler, verifyAndValidateRemoteCertificate, parentLogger, new DTLSServerProtocol());
    }

    public DtlsServer(
        DatagramTransport datagramTransport,
        CertificateInfo certificateInfo,
        DtlsClient.HandshakeCompleteHandler handshakeCompleteHandler,
        Consumer<Certificate> verifyAndValidateRemoteCertificate,
        Logger parentLogger,
        DTLSServerProtocol dtlsServerProtocol)
    {
        this.datagramTransport = datagramTransport;
        this.handshakeCompleteHandler = handshakeCompleteHandler;
        this.dtlsServerProtocol = dtlsServerProtocol;
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.tlsServer = new TlsServerImpl(certificateInfo, verifyAndValidateRemoteCertificate, logger);
    }

    @Override
    public DTLSTransport start()
    {
        return accept();
    }

    public DTLSTransport accept()
    {
        try
        {
            DTLSTransport transport = dtlsServerProtocol.accept(tlsServer, datagramTransport);
            logger.debug(() -> "DTLS handshake finished");
            handshakeCompleteHandler.handshakeComplete(
                tlsServer.getChosenSrtpProtectionProfile(),
                TlsRole.SERVER,
                tlsServer.getSrtpKeyingMaterial()
            );
            return transport;
        }
        catch (Throwable t)
        {
            logger.error(() -> "Error during DTLS connection: " + t);
            if (t instanceof RuntimeException)
            {
                throw (RuntimeException) t;
            }
            if (t instanceof Error)
            {
                throw (Error) t;
            }
            throw new RuntimeException(t);
        }
    }
}
