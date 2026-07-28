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

package org.jitsi.videobridge.transport.dtls;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ice4j.util.Buffer;
import org.jitsi.nlj.dtls.DtlsClient;
import org.jitsi.nlj.dtls.DtlsServer;
import org.jitsi.nlj.dtls.DtlsStack;
import org.jitsi.nlj.srtp.TlsRole;
import org.jitsi.nlj.util.BufferPool;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.queue.PacketQueue;
import org.jitsi.videobridge.transport.DtlsFingerprint;
import org.jitsi.videobridge.transport.TransportDescription;
import org.jitsi.videobridge.util.TaskPools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transport layer which negotiates a DTLS connection and supports
 * decrypting and encrypting data.
 *
 * Incoming DTLS data should be fed into this layer via {@link #enqueueBuffer(Buffer)},
 * and decrypted DTLS application data will be passed to the
 * {@link #incomingDataHandler}, which should be set by an interested party.
 *
 * Outgoing data can be sent via {@link #sendDtlsData} and the encrypted data will
 * be passed to the {@link #outgoingDataHandler}, which should be set by an
 * interested party.
 */
public class DtlsTransport
{
    private final Logger logger;

    private final AtomicBoolean running = new AtomicBoolean(true);

    public IncomingDataHandler incomingDataHandler;

    public OutgoingDataHandler outgoingDataHandler;

    public EventHandler eventHandler;
    private volatile boolean dtlsHandshakeComplete = false;

    private final Stats stats = new Stats();

    /** Whether to advertise cryptex to peers. */
    public boolean cryptex = false;

    private final PacketQueue<Buffer> dtlsQueue;

    /**
     * The DTLS stack instance
     */
    private final DtlsStack dtlsStack;

    public DtlsTransport(Logger parentLogger, String id)
    {
        this.logger = parentLogger.createChildLogger(getClass().getName());

        dtlsQueue = new PacketQueue<Buffer>(
            128,
            null,
            "dtls-queue-" + id,
            buffer -> {
                try
                {
                    dtlsDataReceived(buffer.getBuffer(), buffer.getOffset(), buffer.getLength());
                    return true;
                }
                catch (Exception e)
                {
                    logger.warn("Failed to handle DTLS data", e);
                    return false;
                }
            },
            TaskPools.IO_POOL
        )
        {
            @Override
            protected void releasePacket(Buffer buffer)
            {
                BufferPool.returnBuffer(buffer.getBuffer());
            }
        };

        dtlsStack = new DtlsStack(logger);
        // Install a handler for when the DTLS stack has decrypted application data available
        dtlsStack.setIncomingDataHandler((data, off, len) -> {
            stats.numPacketsReceived++;
            if (incomingDataHandler != null)
            {
                incomingDataHandler.dtlsAppDataReceived(data, off, len);
            }
            else
            {
                stats.numIncomingPacketsDroppedNoHandler++;
            }
        });

        // Install a handler to allow the DTLS stack to send out encrypted data
        dtlsStack.setOutgoingDataHandler((data, off, len) -> {
            if (outgoingDataHandler != null)
            {
                outgoingDataHandler.sendData(data, off, len);
                stats.numPacketsSent++;
            }
            else
            {
                stats.numOutgoingPacketsDroppedNoHandler++;
            }
        });

        // Handle DTLS stack events
        dtlsStack.setEventHandler((chosenSrtpProtectionProfile, tlsRole, keyingMaterial) -> {
            dtlsHandshakeComplete = true;
            if (eventHandler != null)
            {
                eventHandler.handshakeComplete(chosenSrtpProtectionProfile, tlsRole, keyingMaterial);
            }
        });
    }

    public boolean isConnected()
    {
        return dtlsHandshakeComplete;
    }

    /**
     * Start a DTLS handshake.  The 'role' should have been set before calling this
     * (via {@link #setSetupAttribute}
     */
    public void startDtlsHandshake()
    {
        logger.info("Starting DTLS handshake, role="
            + (dtlsStack.getRole() != null ? dtlsStack.getRole().getClass().getSimpleName() : "null"));
        if (dtlsStack.getRole() == null)
        {
            logger.warn("Starting the DTLS stack before it knows its role");
        }
        try
        {
            dtlsStack.start();
        }
        catch (Throwable t)
        {
            logger.error("Error during DTLS negotiation, closing this transport manager", t);
            if (eventHandler != null)
            {
                eventHandler.handshakeFailed(t);
            }
        }
    }

    public void setSetupAttribute(String setupAttr)
    {
        if (setupAttr == null || setupAttr.isEmpty())
        {
            return;
        }
        switch (setupAttr.toLowerCase())
        {
        case "active":
            logger.info("The remote side is acting as DTLS client, we'll act as server");
            dtlsStack.actAsServer();
            break;
        case "passive":
            logger.info("The remote side is acting as DTLS server, we'll act as client");
            dtlsStack.actAsClient();
            break;
        default:
            logger.error("The remote side sent an unrecognized DTLS setup value: " + setupAttr);
            break;
        }
    }

    public void setRemoteFingerprints(Map<String, List<String>> remoteFingerprints)
    {
        // Don't pass an empty list to the stack in order to avoid wiping
        // certificates that were contained in a previous request.
        if (remoteFingerprints.isEmpty())
        {
            return;
        }

        dtlsStack.setRemoteFingerprints(remoteFingerprints);
    }

    /**
     * Describe the properties of this {@link DtlsTransport} into the given {@link TransportDescription}
     */
    public void describe(TransportDescription out)
    {
        DtlsFingerprint fingerprint;
        if (out.fingerprints.isEmpty())
        {
            fingerprint = new DtlsFingerprint();
            out.fingerprints.add(fingerprint);
        }
        else
        {
            fingerprint = out.fingerprints.get(0);
        }

        Object role = dtlsStack.getRole();
        if (role instanceof DtlsServer)
        {
            fingerprint.setup = "passive";
        }
        else if (role instanceof DtlsClient)
        {
            fingerprint.setup = "active";
        }
        else if (role == null)
        {
            fingerprint.setup = "actpass";
        }
        else
        {
            throw new IllegalStateException("Cannot describe role " + role);
        }
        fingerprint.fingerprint = dtlsStack.getLocalFingerprint();
        fingerprint.hash = dtlsStack.getLocalFingerprintHashFunction();
        if (cryptex)
        {
            fingerprint.cryptex = true;
        }
    }

    public void enqueueBuffer(Buffer buffer)
    {
        dtlsQueue.add(buffer);
    }

    /**
     * Notify this layer that DTLS data has been received from the network
     */
    private void dtlsDataReceived(byte[] data, int off, int len)
    {
        dtlsStack.processIncomingProtocolData(data, off, len);
    }

    /**
     * Send out DTLS data
     */
    public void sendDtlsData(byte[] data, int off, int len)
    {
        dtlsStack.sendApplicationData(data, off, len);
    }

    public void stop()
    {
        if (running.compareAndSet(true, false))
        {
            logger.info("Stopping");
            dtlsStack.close();
        }
    }

    public ObjectNode getDebugState()
    {
        ObjectNode o = stats.toJson();
        o.put("running", running.get());
        o.put("role", dtlsStack.getRole() != null ? dtlsStack.getRole().getClass().getSimpleName() : "null");
        o.put("is_connected", isConnected());
        return o;
    }

    private static class Stats
    {
        int numPacketsReceived = 0;
        int numIncomingPacketsDroppedNoHandler = 0;
        int numPacketsSent = 0;
        int numOutgoingPacketsDroppedNoHandler = 0;

        ObjectNode toJson()
        {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            o.put("num_packets_received", numPacketsReceived);
            o.put("num_incoming_packets_dropped_no_handler", numIncomingPacketsDroppedNoHandler);
            o.put("num_packets_sent", numPacketsSent);
            o.put("num_outgoing_packets_dropped_no_handler", numOutgoingPacketsDroppedNoHandler);
            return o;
        }
    }

    /**
     * A handler for when {@link DtlsTransport} wants to send data out
     * onto the network
     */
    public interface OutgoingDataHandler
    {
        void sendData(byte[] buf, int off, int len);
    }

    /**
     * A handler for when {@link DtlsTransport} has received DTLS application
     * data
     */
    public interface IncomingDataHandler
    {
        void dtlsAppDataReceived(byte[] buf, int off, int len);
    }

    /**
     * A handler for {@link DtlsTransport} events
     */
    public interface EventHandler
    {
        void handshakeComplete(int chosenSrtpProtectionProfile, TlsRole tlsRole, byte[] keyingMaterial);

        /**
         * The DTLS handshake failed and the transport cannot carry data. Upstream only logs
         * this (see the {@code startDtlsHandshake} catch block and its {@code TODO}); the
         * library adds this hook so the failure can be surfaced to the application.
         */
        default void handshakeFailed(Throwable t)
        {
        }
    }
}
