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
package org.jitsi.nlj;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.rtcp.RtcpEventNotifier;
import org.jitsi.nlj.rtp.RtpExtension;
import org.jitsi.nlj.rtp.RtpExtensionType;
import org.jitsi.nlj.rtp.TransportCcEngine;
import org.jitsi.nlj.srtp.SrtpProfileInformation;
import org.jitsi.nlj.srtp.SrtpTransformers;
import org.jitsi.nlj.srtp.SrtpUtil;
import org.jitsi.nlj.srtp.TlsRole;
import org.jitsi.nlj.stats.EndpointConnectionStats;
import org.jitsi.nlj.stats.PacketIOActivity;
import org.jitsi.nlj.stats.TransceiverStats;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.LocalSsrcAssociation;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.nlj.util.SsrcAssociation;
import org.jitsi.nlj.util.StreamInformationStoreImpl;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.Logger;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

/**
 * Handles all packets (incoming and outgoing) for a particular stream.
 * (TODO: 'stream' defined as what, exactly, here?)
 * Handles the DTLS negotiation
 *
 * Incoming packets should be written via {@link #handleIncomingPacket}.  Outgoing
 * packets are sent via {@link #sendPacket}.
 *
 * This is an API class, so its usages will largely be outside of this library.
 */
public class Transceiver implements Stoppable
{
    private final String id;
    private final Logger logger;
    private final Clock clock;

    public final PacketIOActivity packetIOActivity = new PacketIOActivity();
    private final EndpointConnectionStats endpointConnectionStats;
    private final StreamInformationStoreImpl streamInformationStore = new StreamInformationStoreImpl();
    public final ReadOnlyStreamInformationStore readOnlyStreamInformationStore = streamInformationStore;

    /**
     * A central place to subscribe to be notified on the reception or transmission of RTCP packets for
     * this transceiver.  This is intended to be used by internal entities: mainly logic for things like generating
     * SRs and RRs and calculating RTT.  Since it is used for both send and receive, it is held here and passed to
     * the sender and receive so each can push or subscribe to updates.
     */
    public final RtcpEventNotifier rtcpEventNotifier = new RtcpEventNotifier();

    private MediaSources mediaSources = new MediaSources();

    private SrtpTransformers srtpTransformers = null;

    /** Whether the srtpTransformers were created inside this object, or passed in externally.
     * For external transformers it's the external owner's responsibility to close them.
     */
    private boolean internalTransformers = false;

    public final RtpSender rtpSender;
    private final RtpReceiver rtpReceiver;

    /**
     * (Deviation: upstream's constructor is annotated {@code @JvmOverloads}, which generates suffix-dropped
     * overloads for the trailing {@code clock} and {@code getMidBySsrc} default parameters; those two overloads are
     * spelled out explicitly below.)
     */
    public Transceiver(
        String id,
        ExecutorService receiverExecutor,
        ExecutorService senderExecutor,
        ScheduledExecutorService backgroundExecutor,
        DiagnosticContext diagnosticContext,
        Logger parentLogger,
        TransceiverEventHandler eventHandler,
        Clock clock,
        Function<Long, String> getMidBySsrc)
    {
        this.id = id;
        this.clock = clock;
        this.logger = parentLogger.createChildLogger(Transceiver.class.getName());
        this.endpointConnectionStats = new EndpointConnectionStats(logger);

        this.rtpSender = new RtpSenderImpl(
            id,
            rtcpEventNotifier,
            senderExecutor,
            backgroundExecutor,
            streamInformationStore,
            logger,
            diagnosticContext,
            getMidBySsrc
        );
        this.rtpReceiver = new RtpReceiverImpl(
            id,
            rtcpPacket -> {
                if (rtcpPacket.getLength() >= 1500)
                {
                    logger.warn(
                        "Sending large locally-generated RTCP packet of size " + rtcpPacket.getLength() + ", " +
                            "first packet of type " + rtcpPacket.getPacketType() + " rc " +
                            rtcpPacket.getReportCount() + "."
                    );
                }
                rtpSender.processPacket(new PacketInfo(rtcpPacket));
            },
            rtcpEventNotifier,
            receiverExecutor,
            backgroundExecutor,
            streamInformationStore,
            eventHandler,
            logger,
            diagnosticContext
        );

        rtpSender.addBandwidthListener(new TransportCcEngine.BandwidthListener()
        {
            @Override
            public void bandwidthEstimationChanged(Bandwidth newValue)
            {
                eventHandler.bandwidthEstimationChanged(newValue);
            }
        });

        rtpReceiver.addLossListener(endpointConnectionStats.getIncomingLossTracker());
        rtpSender.addLossListener(endpointConnectionStats.getOutgoingLossTracker());

        rtcpEventNotifier.addRtcpEventListener(endpointConnectionStats);

        endpointConnectionStats.addListener(rtpSender);
        endpointConnectionStats.addListener(rtpReceiver);
    }

    public Transceiver(
        String id,
        ExecutorService receiverExecutor,
        ExecutorService senderExecutor,
        ScheduledExecutorService backgroundExecutor,
        DiagnosticContext diagnosticContext,
        Logger parentLogger,
        TransceiverEventHandler eventHandler,
        Clock clock)
    {
        this(
            id,
            receiverExecutor,
            senderExecutor,
            backgroundExecutor,
            diagnosticContext,
            parentLogger,
            eventHandler,
            clock,
            ssrc -> null
        );
    }

    public Transceiver(
        String id,
        ExecutorService receiverExecutor,
        ExecutorService senderExecutor,
        ScheduledExecutorService backgroundExecutor,
        DiagnosticContext diagnosticContext,
        Logger parentLogger,
        TransceiverEventHandler eventHandler)
    {
        this(
            id,
            receiverExecutor,
            senderExecutor,
            backgroundExecutor,
            diagnosticContext,
            parentLogger,
            eventHandler,
            Clock.systemUTC(),
            ssrc -> null
        );
    }

    /**
     * Whether this {@link Transceiver} is receiving audio from the remote endpoint.
     */
    public boolean isReceivingAudio()
    {
        return rtpReceiver.isReceivingAudio();
    }

    /**
     * Whether this {@link Transceiver} is receiving video from the remote endpoint.
     */
    public boolean isReceivingVideo()
    {
        return rtpReceiver.isReceivingVideo();
    }

    /**
     * Handle an incoming {@link PacketInfo} (that is, a packet received by the endpoint
     * this transceiver is associated with) to be processed by the receiver pipeline.
     */
    public void handleIncomingPacket(PacketInfo p)
    {
        packetIOActivity.setLastRtpPacketReceivedInstant(clock.instant());
        rtpReceiver.enqueuePacket(p);
    }

    /**
     * Send packets to the endpoint this transceiver is associated with by
     * passing them out the sender's outgoing pipeline
     */
    public void sendPacket(PacketInfo packetInfo)
    {
        packetIOActivity.setLastRtpPacketSentInstant(clock.instant());
        rtpSender.processPacket(packetInfo);
    }

    public int sendProbing(java.util.Collection<Long> mediaSsrcs, int numBytes)
    {
        return rtpSender.sendProbing(mediaSsrcs, numBytes);
    }

    /**
     * Set a handler to be invoked when incoming RTP packets have finished
     * being processed.
     */
    public void setIncomingPacketHandler(PacketHandler rtpHandler)
    {
        rtpReceiver.setPacketHandler(rtpHandler);
    }

    /**
     * Set a handler to be invoked when outgoing packets have finished
     * being processed (and are ready to be sent)
     */
    public void setOutgoingPacketHandler(PacketHandler outgoingPacketHandler)
    {
        rtpSender.onOutgoingPacket(outgoingPacketHandler);
    }

    public void addReceiveSsrc(long ssrc, MediaType mediaType)
    {
        logger.debug(() -> hashCode() + " adding receive ssrc " + ssrc + " of type " + mediaType);
        streamInformationStore.addReceiveSsrc(ssrc, mediaType);
    }

    public void removeReceiveSsrc(long ssrc)
    {
        logger.info("Transceiver " + hashCode() + " removing receive ssrc " + ssrc);
        streamInformationStore.removeReceiveSsrc(ssrc);
    }

    /**
     * Set the 'local' bridge SSRC to {@code ssrc} for {@code mediaType}
     */
    public void setLocalSsrc(MediaType mediaType, long ssrc)
    {
        SetLocalSsrcEvent localSsrcSetEvent = new SetLocalSsrcEvent(mediaType, ssrc);
        rtpSender.handleEvent(localSsrcSetEvent);
        rtpReceiver.handleEvent(localSsrcSetEvent);
    }

    public void addRtpExtensionToRetain(RtpExtensionType extensionType)
    {
        rtpSender.addRtpExtensionToRetain(extensionType);
    }

    public boolean receivesSsrc(long ssrc)
    {
        return streamInformationStore.getReceiveSsrcs().contains(ssrc);
    }

    public Set<Long> getReceiveSsrcs()
    {
        return streamInformationStore.getReceiveSsrcs();
    }

    public boolean setMediaSources(MediaSourceDesc[] mediaSources)
    {
        logger.debug(() -> id + " setting media sources: " + java.util.Arrays.toString(mediaSources));
        boolean ret = this.mediaSources.setMediaSources(mediaSources);
        MediaSourceDesc[] mergedMediaSources = this.mediaSources.getMediaSources();
        MediaSourceDesc[] signaledMediaSources = MediaSourceDesc.copy(mediaSources);
        rtpReceiver.handleEvent(new SetMediaSourcesEvent(mergedMediaSources, signaledMediaSources));
        return ret;
    }

    // TODO(brian): we should only expose an immutable version of this, but Array doesn't have that.  Go in
    // and change all the storage of the media sources to use a list
    public MediaSourceDesc[] getMediaSources()
    {
        return mediaSources.getMediaSources();
    }

    public void requestKeyFrame(String requesterID, Long mediaSsrc)
    {
        rtpSender.requestKeyframe(requesterID, mediaSsrc);
    }

    public void requestKeyFrame(String requesterID)
    {
        requestKeyFrame(requesterID, null);
    }

    public void requestKeyFrame()
    {
        requestKeyFrame(null, null);
    }

    public void addPayloadType(PayloadType payloadType)
    {
        logger.debug(() -> "Payload type added: " + payloadType);
        streamInformationStore.addRtpPayloadType(payloadType);
    }

    public void clearPayloadTypes()
    {
        logger.info("All payload types being cleared");
        streamInformationStore.clearRtpPayloadTypes();
    }

    public void addRtpExtension(RtpExtension rtpExtension)
    {
        logger.debug(() -> "Adding RTP extension: " + rtpExtension);
        streamInformationStore.addRtpExtensionMapping(rtpExtension);
    }

    public void clearRtpExtensions()
    {
        logger.info("Clearing all RTP extensions");
        // TODO: ignoring this for now, since we'll have conflicts from each channel calling it
        // val rtpExtensionClearEvent = RtpExtensionClearEvent()
        // rtpReceiver.handleEvent(rtpExtensionClearEvent)
        // rtpSender.handleEvent(rtpExtensionClearEvent)
        // rtpExtensions.clear()
    }

    public void setExtmapAllowMixed(boolean allow)
    {
        streamInformationStore.setExtmapAllowMixed(allow);
    }

    // TODO(brian): we may want to handle local and remote ssrc associations differently, as different parts of the
    // code care about one or the other, but currently there is no issue treating them the same.
    public void addSsrcAssociation(SsrcAssociation ssrcAssociation)
    {
        logger.debug(() -> {
            String location = ssrcAssociation instanceof LocalSsrcAssociation ? "local" : "remote";
            return "Adding " + location + " SSRC association: " + ssrcAssociation;
        });
        streamInformationStore.addSsrcAssociation(ssrcAssociation);
    }

    public void setSrtpInformation(
        int chosenSrtpProtectionProfile,
        TlsRole tlsRole,
        byte[] keyingMaterial,
        boolean cryptex)
    {
        SrtpProfileInformation srtpProfileInfo =
            SrtpUtil.getSrtpProfileInformationFromSrtpProtectionProfile(chosenSrtpProtectionProfile);
        logger.debug(() -> "Transceiver " + id + " creating transformers with:\n" +
            "profile info:\n" + srtpProfileInfo + "\n" +
            "tls role: " + tlsRole + "\n" +
            "cryptex: " + cryptex);
        /*
         * (Deviation: upstream's SrtpUtil.initializeTransformer() declares `throws GeneralSecurityException` in
         * this port (Kotlin ignores checked exceptions entirely, so upstream calls it with no try/catch). Wrap in a
         * RuntimeException here to preserve that behavior -- this path is not expected to be hit in practice. See
         * the same deviation note in SrtpTransformerNode.java.)
         */
        SrtpTransformers transformers;
        try
        {
            transformers = SrtpUtil.initializeTransformer(
                srtpProfileInfo,
                keyingMaterial,
                tlsRole,
                cryptex,
                logger
            );
        }
        catch (GeneralSecurityException e)
        {
            throw new RuntimeException(e);
        }
        this.srtpTransformers = transformers;
        setSrtpInformationInternal(transformers, true);
    }

    private void setSrtpInformationInternal(SrtpTransformers srtpTransformers, boolean internal)
    {
        rtpReceiver.setSrtpTransformers(srtpTransformers);
        rtpSender.setSrtpTransformers(srtpTransformers);
        internalTransformers = internal;
    }

    public void setSrtpInformation(SrtpTransformers srtpTransformers)
    {
        setSrtpInformationInternal(srtpTransformers, false);
    }

    public SrtpTransformers getSrtpTransformers()
    {
        return srtpTransformers;
    }

    public boolean isInternalTransformers()
    {
        return internalTransformers;
    }

    /**
     * Forcibly mute or unmute the incoming audio stream
     */
    public void forceMuteAudio(boolean shouldMute)
    {
        if (shouldMute)
        {
            logger.info("Muting incoming audio");
        }
        else
        {
            logger.info("Unmuting incoming audio");
        }
        rtpReceiver.forceMuteAudio(shouldMute);
    }

    public void forceMuteVideo(boolean shouldMute)
    {
        if (shouldMute)
        {
            logger.info("Muting incoming video");
        }
        else
        {
            logger.info("Unmuting incoming video");
        }
        rtpReceiver.forceMuteVideo(shouldMute);
    }

    public ObjectNode debugState(DebugStateMode mode)
    {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.set("stream_information_store", streamInformationStore.debugState(mode));
        o.set("media_sources", mediaSources.debugState());
        o.set("endpoint_connection_stats", endpointConnectionStats.getSnapshot().toJson());
        o.set("receiver", rtpReceiver.debugState(mode));
        o.set("sender", rtpSender.debugState(mode));
        return o;
    }

    /**
     * Get various media and network stats
     */
    public TransceiverStats getTransceiverStats()
    {
        return new TransceiverStats(
            endpointConnectionStats.getSnapshot(),
            rtpReceiver.getStats(),
            rtpSender.getStreamStats(),
            rtpSender.getPacketStreamStats(),
            rtpSender.getTransportCcEngineStats()
        );
    }

    public void addEndpointConnectionStatsListener(EndpointConnectionStats.EndpointConnectionStatsListener listener)
    {
        endpointConnectionStats.addListener(listener);
    }

    public void removeEndpointConnectionStatsListener(
        EndpointConnectionStats.EndpointConnectionStatsListener listener)
    {
        endpointConnectionStats.removeListener(listener);
    }

    @Override
    public void stop()
    {
        rtpReceiver.stop();
        rtpSender.stop();
        if (internalTransformers && srtpTransformers != null)
        {
            srtpTransformers.close();
        }
    }

    public void teardown()
    {
        logger.debug("Tearing down");
        rtpReceiver.tearDown();
        rtpSender.tearDown();
    }

    public void setFeature(Features feature, boolean enabled)
    {
        rtpReceiver.setFeature(feature, enabled);
        rtpSender.setFeature(feature, enabled);
    }

    public boolean isFeatureEnabled(Features feature)
    {
        // As of now, the only feature we have (pcap) is always enabled on both
        // the RTP sender and RTP receiver at the same time, so returning
        // the state of one of them is sufficient.  If that were to change
        // in the future we'd have to rethink this API
        return rtpReceiver.isFeatureEnabled(feature);
    }
}
