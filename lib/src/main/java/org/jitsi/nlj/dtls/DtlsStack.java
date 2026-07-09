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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.DTLSTransport;
import org.bouncycastle.tls.DatagramTransport;
import org.jitsi.nlj.srtp.TlsRole;
import org.jitsi.nlj.util.BufferPool;
import org.jitsi.utils.concurrent.ArrayBlockingQueueWithShutdown;
import org.jitsi.utils.logging2.Logger;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A DTLS stack implementation, which can act as either the DTLS server or DTLS client, and can be used
 * for negotiating the DTLS connection and sending and receiving data over that connection.
 *
 * This class does not directly communicate with the network in any way.  Raw DTLS packets must be fed into
 * this stack via the {@link #processIncomingProtocolData(byte[], int, int)} method.  {@link #incomingDataHandler}
 * will be invoked with the decrypted, application data sent over the connection.
 *
 * Data can be sent through the DTLS connection via {@link #sendApplicationData}; it will be encrypted by the stack
 * and then sent out via the {@link #outgoingDataHandler}, which must be set to have the data go anywhere
 * interesting :)
 *
 * {@link #eventHandler} will be invoked when any events occur.
 *
 * After wiring up all the handlers, to start a connection the stack must be told which role to fullfil: client
 * or server.  This can be done by calling either the {@link #actAsClient()} or {@link #actAsServer()} methods.
 * Once the role has been set, {@link #start()} can be called to start the negotiation.
 */
public class DtlsStack
{
    private static final int QUEUE_SIZE = 50;

    /**
     * Because generating the certificateInfo can be expensive, we generate a single
     * one to be used everywhere which expires in 24 hours (when we'll generate
     * another one).
     */
    private static final Object syncRoot = new Object();
    private static CertificateInfo certificateInfoField = generateCertificateInfoOrThrow();

    private static CertificateInfo generateCertificateInfoOrThrow()
    {
        try
        {
            return DtlsUtils.generateCertificateInfo();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private static CertificateInfo getCertificateInfo()
    {
        synchronized (syncRoot)
        {
            long expirationPeriodMs = java.time.Duration.ofDays(1).toMillis();
            if (certificateInfoField.getCreationTimestampMs() + expirationPeriodMs < System.currentTimeMillis())
            {
                // TODO: avoid creating our own thread
                Thread thread = new Thread(() -> {
                    synchronized (syncRoot)
                    {
                        certificateInfoField = generateCertificateInfoOrThrow();
                    }
                });
                thread.start();
            }
            return certificateInfoField;
        }
    }

    private final Logger logger;
    private final CountDownLatch roleSet = new CountDownLatch(1);

    /**
     * The certificate info for this particular {@link DtlsStack} instance. We save it in a local val because the
     * global one might be refreshed.
     */
    private final CertificateInfo certificateInfo = getCertificateInfo();

    /**
     * The remote fingerprints sent to us over the signaling path.
     */
    private Map<String, List<String>> remoteFingerprints = Collections.emptyMap();

    /**
     * A handler which will be invoked when DTLS application data is received
     */
    private IncomingDataHandler incomingDataHandler;

    /**
     * The method {@link DtlsStack} will invoke when it wants to send DTLS data out onto the network.
     */
    private OutgoingDataHandler outgoingDataHandler;

    /**
     * Handle to be invoked when events occur
     */
    private EventHandler eventHandler;

    private final ArrayBlockingQueueWithShutdown<ByteBuffer> incomingProtocolData =
        new ArrayBlockingQueueWithShutdown<>(QUEUE_SIZE, true);
    private int numPacketDropsQueueFull = 0;

    /**
     * The {@link DtlsRole} 'plugin' that will determine how this stack operates (as a client
     * or a server).  A call to {@link #actAsClient()} or {@link #actAsServer()} must be made to fill out
     * this role and successfully call {@link #start()}
     */
    private DtlsRole role;

    /**
     * A buffer we'll use to receive data from {@code dtlsTransport}.
     */
    private final byte[] dtlsAppDataBuf = new byte[1500];

    /**
     * The negotiated DTLS transport.  This is used to read and write DTLS application data.
     */
    private DTLSTransport dtlsTransport;

    /**
     * The {@link DatagramTransport} implementation we use for this stack.
     */
    private final DatagramTransport datagramTransport;

    public DtlsStack(Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.datagramTransport = new DatagramTransportImpl(logger);
    }

    public String getLocalFingerprintHashFunction()
    {
        return certificateInfo.getLocalFingerprintHashFunction();
    }

    public String getLocalFingerprint()
    {
        return certificateInfo.getLocalFingerprint();
    }

    public Map<String, List<String>> getRemoteFingerprints()
    {
        return remoteFingerprints;
    }

    public void setRemoteFingerprints(Map<String, List<String>> remoteFingerprints)
    {
        this.remoteFingerprints = remoteFingerprints;
    }

    public IncomingDataHandler getIncomingDataHandler()
    {
        return incomingDataHandler;
    }

    public void setIncomingDataHandler(IncomingDataHandler incomingDataHandler)
    {
        this.incomingDataHandler = incomingDataHandler;
    }

    public OutgoingDataHandler getOutgoingDataHandler()
    {
        return outgoingDataHandler;
    }

    public void setOutgoingDataHandler(OutgoingDataHandler outgoingDataHandler)
    {
        this.outgoingDataHandler = outgoingDataHandler;
    }

    public EventHandler getEventHandler()
    {
        return eventHandler;
    }

    public void setEventHandler(EventHandler eventHandler)
    {
        this.eventHandler = eventHandler;
    }

    public DtlsRole getRole()
    {
        return role;
    }

    public void actAsServer()
    {
        role = new DtlsServer(
            datagramTransport,
            certificateInfo,
            (chosenSrtpProfile, tlsRole, keyingMaterial) -> {
                if (eventHandler != null)
                {
                    eventHandler.handshakeComplete(chosenSrtpProfile, tlsRole, keyingMaterial);
                }
            },
            this::verifyAndValidateRemoteCertificate,
            logger
        );
        roleSet.countDown();
    }

    public void actAsClient()
    {
        role = new DtlsClient(
            datagramTransport,
            certificateInfo,
            (chosenSrtpProfile, tlsRole, keyingMaterial) -> {
                if (eventHandler != null)
                {
                    eventHandler.handshakeComplete(chosenSrtpProfile, tlsRole, keyingMaterial);
                }
            },
            this::verifyAndValidateRemoteCertificate,
            logger
        );
        roleSet.countDown();
    }

    /**
     * 'start' this stack, in whatever role it has been told to operate (client or server).  If a role
     * has not yet been yet (via {@link #actAsServer()} or {@link #actAsClient()}), then it will block until the role
     * has been set.
     */
    public void start()
    {
        try
        {
            roleSet.await();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        dtlsTransport = role != null ? role.start() : null;
        // There is a bit of a race here: It's technically possible the
        // far side could finish the handshake and send a message before
        // this side assigns dtlsTransport here.  If so, that message
        // would be passed to #processIncomingProtocolData and put in
        // incomingProtocolData, but, since dtlsTransport won't be set
        // yet, we won't 'receive' it yet.  Check for any incoming packets
        // here, to handle this case.
        processIncomingProtocolData();
    }

    public void close()
    {
        try
        {
            datagramTransport.close();
        }
        catch (java.io.IOException e)
        {
            logger.warn("Error closing datagram transport: " + e);
        }

        incomingProtocolData.shutdown();
        for (ByteBuffer buf : incomingProtocolData)
        {
            BufferPool.returnBuffer(buf.array());
        }
        incomingProtocolData.clear();
    }

    /**
     * Checks that a specific {@link Certificate} matches the remote fingerprints sent to us over the signaling path.
     */
    private void verifyAndValidateRemoteCertificate(Certificate remoteCertificate)
    {
        if (remoteCertificate != null)
        {
            DtlsUtils.verifyAndValidateCertificate(remoteCertificate, remoteFingerprints);
            // The above throws an exception if the checks fail.
            logger.debug(() -> "Fingerprints verified.");
        }
        else
        {
            throw new DtlsUtils.DtlsException("Remote certificate was null");
        }
    }

    public void sendApplicationData(byte[] data, int off, int len)
    {
        if (dtlsTransport != null)
        {
            try
            {
                dtlsTransport.send(data, off, len);
            }
            catch (java.io.IOException e)
            {
                logger.warn("Error sending DTLS application data: " + e);
            }
        }
    }

    private void processIncomingProtocolData()
    {
        int bytesReceived;
        do
        {
            byte[] bufCopy2;
            synchronized (dtlsAppDataBuf)
            {
                int received;
                try
                {
                    received = dtlsTransport != null ? dtlsTransport.receive(dtlsAppDataBuf, 0, 1500, 1) : -1;
                }
                catch (java.io.IOException e)
                {
                    logger.warn("Error receiving DTLS application data: " + e);
                    received = -1;
                }
                bytesReceived = received;

                if (bytesReceived > 0)
                {
                    // Copy again to copy out of dtlsAppDataBuf, which we re-use.
                    byte[] copy = BufferPool.getBuffer(bytesReceived);
                    System.arraycopy(dtlsAppDataBuf, 0, copy, 0, bytesReceived);
                    bufCopy2 = copy;
                }
                else
                {
                    bufCopy2 = null;
                }
            }
            if (bufCopy2 != null && incomingDataHandler != null)
            {
                incomingDataHandler.dataReceived(bufCopy2, 0, bytesReceived);
            }
        }
        while (bytesReceived > 0);
    }

    /**
     * We get 'pushed' the data from a lower transport layer, but bouncycastle wants to 'pull' the data
     * itself.  To mimic this, we put the received data into a queue, and then 'pull' it through ourselves by
     * calling 'receive' on the negotiated {@link DTLSTransport}.
     *
     * Note: the data we get here may be a DTLS protocol packet and therefore won't generate anything
     * to be received by {@code dtlsTransport} or a DTLS app packet (data sent over DTLS after the handshake has
     * completed) and will result in data being received through {@code dtlsTransport}.  It's possible, though,
     * that the handshake has finished and the far end has sent application data but we've not yet set
     * {@code dtlsTransport}, so we "miss" it.  We don't lose this data, but it will sit inside of
     * {@code incomingProtocolData} until the next packet comes through.  This means that we must make a copy of the
     * buffer we receive, as we won't necessarily be done with it by the time this method completes.
     */
    public void processIncomingProtocolData(byte[] data, int off, int len)
    {
        byte[] bufCopy = BufferPool.getBuffer(len);
        System.arraycopy(data, off, bufCopy, 0, len);
        if (!incomingProtocolData.offer(ByteBuffer.wrap(bufCopy, 0, len)))
        {
            BufferPool.returnBuffer(bufCopy);
            if (!incomingProtocolData.isShutdown())
            {
                logger.warn("DTLS stack queue full, dropping packet");
                numPacketDropsQueueFull++;
            }
        }

        processIncomingProtocolData();
    }

    public ObjectNode getDebugState()
    {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("localFingerprintHashFunction", certificateInfo.getLocalFingerprint());
        StringJoiner joiner = new StringJoiner(", ");
        remoteFingerprints.forEach((hash, fp) -> joiner.add(hash + ": " + fp));
        o.put("remoteFingerprints", joiner.toString());
        o.put("role", (role != null ? role.getClass() : "null").toString());
        o.put("num_packet_drops_queue_full", numPacketDropsQueueFull);
        return o;
    }

    /**
     * An implementation of {@link DatagramTransport} which 'receives' from the queue in {@link DtlsStack} and sends
     * out via {@link DtlsStack}'s {@link #outgoingDataHandler}
     */
    private class DatagramTransportImpl implements DatagramTransport
    {
        private final Logger logger;

        DatagramTransportImpl(Logger parentLogger)
        {
            this.logger = parentLogger.createChildLogger(getClass().getName());
        }

        @Override
        public int receive(byte[] buf, int off, int len, int waitMillis)
        {
            ByteBuffer data;
            try
            {
                data = incomingProtocolData.poll(waitMillis, TimeUnit.MILLISECONDS);
                if (data == null)
                {
                    return -1;
                }
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                return -1;
            }
            int length = Math.min(len, data.limit());
            if (length < data.limit())
            {
                logger.warn(
                    "Passed buffer size (" + len + ") was too small to hold incoming data size (" + data.limit() +
                        "); data was truncated"
                );
            }
            System.arraycopy(data.array(), data.arrayOffset(), buf, off, length);
            BufferPool.returnBuffer(data.array());
            return length;
        }

        @Override
        public void send(byte[] buf, int off, int len)
        {
            if (outgoingDataHandler != null)
            {
                outgoingDataHandler.sendData(buf, off, len);
            }
        }

        /**
         * Receive limit computation copied from {@link org.bouncycastle.tls.UDPTransport}
         */
        @Override
        public int getReceiveLimit()
        {
            return 1500 - 20 - 8;
        }

        /**
         * Send limit computation copied from {@link org.bouncycastle.tls.UDPTransport}
         */
        @Override
        public int getSendLimit()
        {
            return 1500 - 84 - 8;
        }

        @Override
        public void close()
        {
        }
    }

    public interface IncomingDataHandler
    {
        /**
         * Notify the handler that data has been received.  The handler takes ownership of the passed
         * buffer, and it should be returned to the buffer pool when done with it.
         */
        void dataReceived(byte[] data, int off, int len);
    }

    public interface OutgoingDataHandler
    {
        void sendData(byte[] data, int off, int len);
    }

    public interface EventHandler
    {
        void handshakeComplete(int chosenSrtpProtectionProfile, TlsRole tlsRole, byte[] keyingMaterial);
    }
}
