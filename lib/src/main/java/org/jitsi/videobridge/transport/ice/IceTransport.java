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

package org.jitsi.videobridge.transport.ice;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;
import org.ice4j.ice.HostCandidate;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.IceProcessingState;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.ice.RemoteCandidate;
import org.ice4j.ice.harvest.MappingCandidateHarvesters;
import org.ice4j.socket.IceSocketWrapper;
import org.ice4j.util.Buffer;
import org.ice4j.util.BufferHandler;
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.ice.Harvesters;
import org.jitsi.videobridge.ice.IceConfig;
import org.jitsi.videobridge.ice.TransportUtils;
import org.jitsi.videobridge.transport.IceCandidate;
import org.jitsi.videobridge.transport.TransportDescription;
import org.jitsi.videobridge.util.ByteBufferPool;
import org.jitsi.videobridge.util.TaskPools;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet6Address;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * The transport layer for an ICE-based connection. Manages the ice4j {@link Agent}, provides
 * connectivity establishment, and reads/writes data over the negotiated candidate pair.
 */
public class IceTransport
{
    static
    {
        // Upstream jitsi-videobridge ships these ice4j overrides in its application.conf
        // (jvb/src/main/resources/application.conf). This library has no HOCON application
        // config, so apply the same defaults programmatically (hosts can still override by
        // setting the properties before this class loads):
        // - Components must not additionally gather dynamic-port host candidates: those
        //   sockets have no reader when the push API (single port harvester) is in use.
        // - Link-local addresses are not useful to advertise.
        if (System.getProperty("ice4j.harvest.udp.use-dynamic-ports") == null)
        {
            System.setProperty("ice4j.harvest.udp.use-dynamic-ports", "false");
        }
        if (System.getProperty("ice4j.harvest.use-link-local-addresses") == null)
        {
            System.setProperty("ice4j.harvest.use-link-local-addresses", "false");
        }
        // Upstream sets this in Main.kt (stripped from this library): incoming data on the
        // single port harvester is pushed to Component.setBufferCallback instead of being
        // queued on a DatagramSocket nobody reads.
        org.ice4j.ice.harvest.AbstractUdpListener.USE_PUSH_API = true;
    }

    private final Logger logger;

    /**
     * Whether the ICE agent created by this transport should use unique local ports, rather
     * than the configured port.
     */
    public final boolean useUniquePort;

    /**
     * Use private addresses for this {@link IceTransport} even if
     * {@link IceConfig#advertisePrivateCandidates} is false.
     */
    private final boolean advertisePrivateAddresses;

    private final Clock clock;

    /**
     * The handler which will be invoked when data is received.
     * This field should be set by some other entity which wishes to handle the incoming data
     * received over the ICE connection.
     */
    public IncomingDataHandler incomingDataHandler;

    /**
     * The handler which will be invoked when events fired by {@link IceTransport}
     * occur.  This field should be set by another entity who wishes to handle
     * the events.  Handlers will only be notified of events which occur
     * *after* the handler has been set.
     */
    public EventHandler eventHandler;

    /**
     * Whether or not it is possible to write to this {@link IceTransport}.
     *
     * This happens as soon as any candidate pair is validated, and happens (usually) before iceConnected.
     */
    private final AtomicBoolean iceWriteable = new AtomicBoolean(false);

    /**
     * Whether or not this {@link IceTransport} has connected.
     */
    private final AtomicBoolean iceConnected = new AtomicBoolean(false);

    /**
     * Whether or not this {@link IceTransport} has failed to connect.
     */
    private final AtomicBoolean iceFailed = new AtomicBoolean(false);

    public boolean hasFailed()
    {
        return iceFailed.get();
    }

    public boolean isWriteable()
    {
        return iceWriteable.get();
    }

    public boolean isConnected()
    {
        return iceConnected.get();
    }

    /**
     * Whether or not this transport is 'running'.  If it is not
     * running, no more data will be read from the socket or sent out.
     */
    private final AtomicBoolean running = new AtomicBoolean(true);

    private final PropertyChangeListener iceStateChangeListener = this::iceStateChanged;
    private final PropertyChangeListener iceStreamPairChangedListener = this::iceStreamPairChanged;

    private final Agent iceAgent;
    private final IceMediaStream iceStream;
    private final Component iceComponent;
    private final PacketStats packetStats = new PacketStats();

    public IceTransport(
        String id,
        boolean controlling,
        boolean useUniquePort,
        boolean advertisePrivateAddresses,
        Logger parentLogger)
    {
        this(id, controlling, useUniquePort, advertisePrivateAddresses, parentLogger, Clock.systemUTC());
    }

    public IceTransport(
        String id,
        boolean controlling,
        boolean useUniquePort,
        boolean advertisePrivateAddresses,
        Logger parentLogger,
        Clock clock)
    {
        this.useUniquePort = useUniquePort;
        this.advertisePrivateAddresses = advertisePrivateAddresses;
        this.clock = clock;
        this.logger = parentLogger.createChildLogger(getClass().getName());

        iceAgent = new Agent(IceConfig.config.ufragPrefix, logger);
        if (useUniquePort)
        {
            iceAgent.setUseDynamicPorts(true);
        }
        else
        {
            appendHarvesters(iceAgent);
        }
        iceAgent.setControlling(controlling);
        iceAgent.setPerformConsentFreshness(true);
        iceAgent.setNominationStrategy(IceConfig.config.nominationStrategy);
        iceAgent.addStateChangeListener(iceStateChangeListener);
        logger.addContext("local_ufrag", iceAgent.getLocalUfrag());

        iceStream = iceAgent.createMediaStream("stream");
        iceStream.addPairChangeListener(iceStreamPairChangedListener);

        try
        {
            iceComponent = iceAgent.createComponent(iceStream, IceConfig.config.keepAliveStrategy, false);
        }
        catch (java.io.IOException e)
        {
            // Kotlin does not have checked exceptions; createComponent's IOException is not expected in
            // practice for a non-dynamic-port harvester setup, so we translate it to an unchecked exception here.
            throw new RuntimeException(e);
        }
        iceComponent.setBufferCallback(new BufferHandler()
        {
            @Override
            public void handleBuffer(Buffer buffer)
            {
                if (incomingDataHandler != null)
                {
                    incomingDataHandler.dataReceived(buffer);
                }
                else
                {
                    packetStats.numIncomingPacketsDroppedNoHandler.increment();
                    ByteBufferPool.returnBuffer(buffer.getBuffer());
                }
            }
        });
    }

    public String getIcePassword()
    {
        return iceAgent.getLocalPassword();
    }

    /**
     * Tell this {@link IceTransport} to start ICE connectivity establishment.
     */
    public void startConnectivityEstablishment(TransportDescription transport)
    {
        if (!running.get())
        {
            logger.warn("Not starting connectivity establishment, transport is not running");
            return;
        }
        if (iceAgent.getState().isEstablished())
        {
            logger.debug(() -> "Connection already established");
            return;
        }
        logger.debug(() -> "Starting ICE connectivity establishment");

        // Set the remote ufrag/password
        iceStream.setRemoteUfrag(transport.ufrag);
        iceStream.setRemotePassword(transport.password);

        // If ICE is running already, we try to update the checklists with the
        // candidates. Note that this is a best effort.
        boolean iceAgentStateIsRunning = IceProcessingState.RUNNING == iceAgent.getState();

        List<IceCandidate> remoteCandidates = transport.candidates;
        if (iceAgentStateIsRunning && remoteCandidates.isEmpty())
        {
            logger.debug(() -> "Ignoring transport extensions with no candidates, "
                + "the Agent is already running.");
            return;
        }

        int remoteCandidateCount = addRemoteCandidates(remoteCandidates, iceAgentStateIsRunning);
        if (iceAgentStateIsRunning)
        {
            if (remoteCandidateCount != 0)
            {
                iceComponent.updateRemoteCandidates();
            }
            // else: XXX Effectively, the check above but realizing that all
            // candidates were ignored:
            // iceAgentStateIsRunning && candidates.isEmpty().
        }
        else if (remoteCandidateCount != 0)
        {
            // Once again, because the ICE Agent does not support adding
            // candidates after the connectivity establishment has been started
            // and because multiple transport-info messages may be used to send
            // the whole set of transport candidates from the remote peer to the
            // local peer, do not really start the connectivity establishment
            // until we have at least one remote candidate per ICE Component.
            if (iceComponent.getRemoteCandidateCount() > 0)
            {
                logger.debug("Starting the agent with remote candidates.");
                iceAgent.startConnectivityEstablishment();
            }
        }
        else if (remoteUfragAndPasswordKnown(iceStream))
        {
            // We don't have any remote candidates, but we already know the
            // remote ufrag and password, so we can start ICE.
            logger.debug("Starting the Agent without remote candidates.");
            iceAgent.startConnectivityEstablishment();
        }
        else
        {
            logger.debug(() -> "Not starting ICE, no ufrag and pwd yet.");
        }
    }

    public void startReadingData()
    {
        logger.debug(() -> "Starting to read incoming data");
        IceSocketWrapper socket = iceComponent.getSelectedPair().getIceSocketWrapper();
        byte[] receiveBuf = new byte[1500];
        DatagramPacket packet = new DatagramPacket(receiveBuf, 0, receiveBuf.length);
        Instant receivedTime;

        while (running.get())
        {
            try
            {
                socket.receive(packet);
                receivedTime = clock.instant();
            }
            catch (IOException e)
            {
                logger.warn("Stopping reader", e);
                break;
            }
            packetStats.numPacketsReceived.increment();
            try
            {
                byte[] b = ByteBufferPool.getBuffer(
                    RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET + packet.getLength()
                        + Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET
                );
                System.arraycopy(
                    packet.getData(),
                    packet.getOffset(),
                    b,
                    RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET,
                    packet.getLength()
                );
                Buffer buffer =
                    new Buffer(b, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, packet.getLength(), receivedTime);

                if (incomingDataHandler != null)
                {
                    incomingDataHandler.dataReceived(buffer);
                }
                else
                {
                    logger.debug(() -> "Data handler is null, dropping data");
                    packetStats.numIncomingPacketsDroppedNoHandler.increment();
                }
            }
            catch (Throwable e)
            {
                logger.error("Uncaught exception processing packet", e);
            }
        }
        logger.info("No longer running, stopped reading packets");
    }

    /**
     * Send data out via this transport
     */
    public void send(byte[] data, int off, int length)
    {
        if (running.get())
        {
            try
            {
                iceComponent.send(data, off, length);
                packetStats.numPacketsSent.increment();
            }
            catch (IOException e)
            {
                logger.error("Error sending packet", e);
                throw new RuntimeException();
            }
        }
        else
        {
            packetStats.numOutgoingPacketsDroppedStopped.increment();
        }
    }

    public void stop()
    {
        if (running.compareAndSet(true, false))
        {
            logger.info("Stopping");
            iceAgent.removeStateChangeListener(iceStateChangeListener);
            iceStream.removePairStateChangeListener(iceStreamPairChangedListener);
            iceAgent.free();
        }
    }

    public ObjectNode getDebugState()
    {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("keepAliveStrategy", IceConfig.config.keepAliveStrategy.toString());
        o.put("nominationStrategy", IceConfig.config.nominationStrategy.toString());
        o.put("advertisePrivateCandidates", IceConfig.config.advertisePrivateCandidates);
        o.put("closed", !running.get());
        o.put("iceWriteable", iceWriteable.get());
        o.put("iceConnected", iceConnected.get());
        o.put("iceFailed", iceFailed.get());
        o.setAll(packetStats.toJson());
        return o;
    }

    public void describe(TransportDescription out)
    {
        if (!running.get())
        {
            logger.warn("Not describing, transport is not running");
        }
        out.password = iceAgent.getLocalPassword();
        out.ufrag = iceAgent.getLocalUfrag();
        List<LocalCandidate> localCandidates = iceComponent.getLocalCandidates();
        if (localCandidates != null)
        {
            for (LocalCandidate cand : localCandidates)
            {
                IceCandidate ic = toIceCandidate(cand, advertisePrivateAddresses);
                if (ic != null)
                {
                    out.candidates.add(ic);
                }
            }
        }
        out.rtcpMux = true;
    }

    /**
     * @return the number of network reachable remote candidates contained in
     * the given list of candidates.
     */
    private int addRemoteCandidates(List<IceCandidate> remoteCandidates, boolean iceAgentIsRunning)
    {
        int remoteCandidateCount = 0;
        // Sort the remote candidates (host < reflexive < relayed) in order to
        // create first the host, then the reflexive, the relayed candidates and
        // thus be able to set the relative-candidate matching the
        // rel-addr/rel-port attribute.
        List<IceCandidate> sorted = new ArrayList<>(remoteCandidates);
        Collections.sort(sorted);
        for (IceCandidate candidate : sorted)
        {
            // Is the remote candidate from the current generation of the
            // iceAgent?
            if (candidate.generation != iceAgent.getGeneration())
            {
                continue;
            }
            if (candidate.ipNeedsResolution() && !IceConfig.config.resolveRemoteCandidates)
            {
                logger.debug(() -> "Ignoring remote candidate with non-literal address: " + candidate.ip);
                continue;
            }
            Component component = iceStream.getComponent(candidate.component);
            RemoteCandidate remoteCandidate = new RemoteCandidate(
                new TransportAddress(candidate.ip, candidate.port, Transport.parse(candidate.protocol)),
                component,
                candidate.type,
                candidate.foundation,
                candidate.priority,
                null
            );
            // XXX IceTransport harvests host candidates only and the
            // ICE Components utilize the UDP protocol/transport only at the
            // time of this writing. The ice4j library will, of course, check
            // the theoretical reachability between the local and the remote
            // candidates. However, we would like (1) to not mess with a
            // possibly running iceAgent and (2) to return a consistent return
            // value.
            if (!TransportUtils.canReach(component, remoteCandidate))
            {
                continue;
            }
            if (iceAgentIsRunning)
            {
                component.addUpdateRemoteCandidates(remoteCandidate);
            }
            else
            {
                component.addRemoteCandidate(remoteCandidate);
            }
            remoteCandidateCount++;
        }

        return remoteCandidateCount;
    }

    private void iceStateChanged(PropertyChangeEvent ev)
    {
        IceProcessingState oldState = (IceProcessingState) ev.getOldValue();
        IceProcessingState newState = (IceProcessingState) ev.getNewValue();
        IceProcessingStateTransition transition = new IceProcessingStateTransition(oldState, newState);

        logger.debug("ICE state changed old=" + oldState + " new=" + newState);

        if (transition.completed())
        {
            if (iceConnected.compareAndSet(false, true))
            {
                if (eventHandler != null)
                {
                    eventHandler.connected();
                }
                if (useUniquePort)
                {
                    // ice4j's push API only works with the single port harvester. With unique ports we still need
                    // to read from the socket.
                    TaskPools.IO_POOL.submit(this::startReadingData);
                }
                if (iceComponent.getSelectedPair().getRemoteCandidate().getType() == CandidateType.RELAYED_CANDIDATE
                    || iceComponent.getSelectedPair().getLocalCandidate().getType()
                        == CandidateType.RELAYED_CANDIDATE)
                {
                    // (relayed-pair succeeded; metrics counter removed for this library)
                    logger.debug(() -> "ICE succeeded with a relayed candidate pair");
                }
            }
        }
        else if (transition.failed())
        {
            if (iceFailed.compareAndSet(false, true))
            {
                if (eventHandler != null)
                {
                    eventHandler.failed();
                }
            }
        }
    }

    /** Update IceStatistics once an initial round-trip-time measurement is available. */
    public void updateStatsOnInitialRtt(double rttMs)
    {
        org.ice4j.ice.CandidatePair selectedPair = iceComponent.getSelectedPair();
        LocalCandidate localCandidate = selectedPair != null ? selectedPair.getLocalCandidate() : null;
        if (localCandidate == null)
        {
            return;
        }
        String harvesterName;
        if (localCandidate instanceof HostCandidate)
        {
            harvesterName = "host";
        }
        else
        {
            org.ice4j.ice.harvest.MappingCandidateHarvester harvester =
                MappingCandidateHarvesters.findHarvesterForAddress(localCandidate.getTransportAddress());
            harvesterName = harvester != null ? harvester.getName() : "other";
        }

        IceStatistics.stats.add(harvesterName, rttMs);
    }

    private void iceStreamPairChanged(PropertyChangeEvent ev)
    {
        if (IceMediaStream.PROPERTY_PAIR_VALIDATED.equals(ev.getPropertyName()))
        {
            if (iceWriteable.compareAndSet(false, true))
            {
                if (eventHandler != null)
                {
                    eventHandler.writeable();
                }
            }
        }
        else if (IceMediaStream.PROPERTY_PAIR_CONSENT_FRESHNESS_CHANGED.equals(ev.getPropertyName()))
        {
            /* TODO: Currently ice4j only triggers this event for the selected
             * pair, but should we double-check the pair anyway?
             */
            Instant time = Instant.ofEpochMilli((Long) ev.getNewValue());
            if (eventHandler != null)
            {
                eventHandler.consentUpdated(time);
            }
        }
    }

    public static void appendHarvesters(Agent iceAgent)
    {
        for (org.ice4j.ice.harvest.SinglePortUdpHarvester h : Harvesters.getInstance().singlePortHarvesters)
        {
            iceAgent.addCandidateHarvester(h);
        }
    }

    private static class PacketStats
    {
        final LongAdder numPacketsReceived = new LongAdder();
        final LongAdder numIncomingPacketsDroppedNoHandler = new LongAdder();
        final LongAdder numPacketsSent = new LongAdder();
        final LongAdder numOutgoingPacketsDroppedStopped = new LongAdder();

        ObjectNode toJson()
        {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            o.put("num_packets_received", numPacketsReceived.sum());
            o.put("num_incoming_packets_dropped_no_handler", numIncomingPacketsDroppedNoHandler.sum());
            o.put("num_packets_sent", numPacketsSent.sum());
            o.put("num_outgoing_packets_dropped_stopped", numOutgoingPacketsDroppedStopped.sum());
            return o;
        }
    }

    public interface IncomingDataHandler
    {
        /**
         * Notify the handler that data was received.
         */
        void dataReceived(Buffer buffer);
    }

    public interface EventHandler
    {
        /**
         * Notify the event handler that it is possible to write to the ICE stack
         */
        void writeable();

        /**
         * Notify the event handler that ICE connected successfully
         */
        void connected();

        /**
         * Notify the event handler that ICE failed to connect
         */
        void failed();

        /**
         * Notify the event handler that ICE consent was updated
         */
        void consentUpdated(Instant time);
    }

    /**
     * Models a transition from one ICE state to another and provides convenience
     * functions to test the transition.
     */
    private static class IceProcessingStateTransition
    {
        private final IceProcessingState oldState;
        private final IceProcessingState newState;

        IceProcessingStateTransition(IceProcessingState oldState, IceProcessingState newState)
        {
            this.oldState = oldState;
            this.newState = newState;
        }

        // We should be using newState.isEstablished() here, but we see
        // transitions from RUNNING to TERMINATED, which can happen if the Agent is
        // free prior to being started, so we handle that case separately below.
        boolean completed()
        {
            return newState == IceProcessingState.COMPLETED;
        }

        boolean failed()
        {
            return newState == IceProcessingState.FAILED
                || (oldState == IceProcessingState.RUNNING && newState == IceProcessingState.TERMINATED);
        }
    }

    private static boolean remoteUfragAndPasswordKnown(IceMediaStream stream)
    {
        return stream.getRemoteUfrag() != null && stream.getRemotePassword() != null;
    }

    private static boolean isPrivateAddress(TransportAddress address)
    {
        return address.getAddress().isSiteLocalAddress()
            /* 0xfc00::/7 */
            || ((address.getAddress() instanceof Inet6Address)
                && ((address.getAddressBytes()[0] & 0xfe) == 0xfc));
    }

    private static String generateCandidateId(LocalCandidate candidate)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(Long.toHexString(candidate.hashCode()));
        sb.append(Long.toHexString(candidate.getParentComponent().getParentStream().getParentAgent().hashCode()));
        sb.append(Long.toHexString(candidate.getParentComponent().getParentStream().getParentAgent().getGeneration()));
        sb.append(Long.toHexString(candidate.hashCode()));
        return sb.toString();
    }

    private static IceCandidate toIceCandidate(LocalCandidate localCandidate, boolean advertisePrivateAddresses)
    {
        if (isPrivateAddress(localCandidate.getTransportAddress())
            && !advertisePrivateAddresses
            && !IceConfig.config.advertisePrivateCandidates)
        {
            return null;
        }
        IceCandidate cand = new IceCandidate();
        cand.component = localCandidate.getParentComponent().getComponentID();
        cand.foundation = localCandidate.getFoundation();
        cand.generation = localCandidate.getParentComponent().getParentStream().getParentAgent().getGeneration();
        cand.id = generateCandidateId(localCandidate);
        cand.network = 0;
        cand.priority = localCandidate.getPriority();

        cand.protocol = localCandidate.getTransport().toString();
        cand.type = localCandidate.getType();
        cand.ip = localCandidate.getTransportAddress().getHostAddress();
        cand.port = localCandidate.getTransportAddress().getPort();

        TransportAddress relatedAddress = localCandidate.getRelatedAddress();
        if (relatedAddress != null)
        {
            if (!IceConfig.config.advertisePrivateCandidates && isPrivateAddress(relatedAddress))
            {
                cand.relAddr = "0.0.0.0";
                cand.relPort = 9;
            }
            else
            {
                cand.relAddr = relatedAddress.getHostAddress();
                cand.relPort = relatedAddress.getPort();
            }
        }

        return cand;
    }
}
