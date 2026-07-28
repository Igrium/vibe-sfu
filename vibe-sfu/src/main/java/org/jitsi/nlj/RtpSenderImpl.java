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
import org.jitsi.nlj.rtcp.KeyframeRequester;
import org.jitsi.nlj.rtcp.NackHandler;
import org.jitsi.nlj.rtcp.RtcpEventNotifier;
import org.jitsi.nlj.rtcp.RtcpSrUpdater;
import org.jitsi.nlj.rtp.ClassicTransportCcEngine;
import org.jitsi.nlj.rtp.LossListener;
import org.jitsi.nlj.rtp.RtpExtensionType;
import org.jitsi.nlj.rtp.TransportCcEngine;
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimatorConfig;
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimatorConfig.BandwidthEstimatorEngine;
import org.jitsi.nlj.rtp.bandwidthestimation.GoogleCcEstimator;
import org.jitsi.nlj.rtp.bandwidthestimation2.GoogCcTransportCcEngine;
import org.jitsi.nlj.srtp.SrtpTransformers;
import org.jitsi.nlj.transform.NodeDebugStateVisitor;
import org.jitsi.nlj.transform.NodeEventVisitor;
import org.jitsi.nlj.transform.NodeTeardownVisitor;
import org.jitsi.nlj.transform.PipelineDsl;
import org.jitsi.nlj.transform.node.AudioRedHandler;
import org.jitsi.nlj.transform.node.ConsumerNode;
import org.jitsi.nlj.transform.node.Node;
import org.jitsi.nlj.transform.node.ObserverNode;
import org.jitsi.nlj.transform.node.PacketCacher;
import org.jitsi.nlj.transform.node.PacketLossConfig;
import org.jitsi.nlj.transform.node.PacketLossNode;
import org.jitsi.nlj.transform.node.PacketStreamStatsNode;
import org.jitsi.nlj.transform.node.PluggableTransformerNode;
import org.jitsi.nlj.transform.node.SrtcpEncryptNode;
import org.jitsi.nlj.transform.node.SrtpEncryptNode;
import org.jitsi.nlj.transform.node.ToggleablePcapWriter;
import org.jitsi.nlj.transform.node.outgoing.AbsSendTime;
import org.jitsi.nlj.transform.node.outgoing.HeaderExtEncoder;
import org.jitsi.nlj.transform.node.outgoing.HeaderExtStripper;
import org.jitsi.nlj.transform.node.outgoing.MidStamper;
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsTracker;
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsTracker.OutgoingStatisticsSnapshot;
import org.jitsi.nlj.transform.node.outgoing.ProbingDataSender;
import org.jitsi.nlj.transform.node.outgoing.RetransmissionSender;
import org.jitsi.nlj.transform.node.outgoing.SentRtcpStats;
import org.jitsi.nlj.transform.node.outgoing.TccSeqNumTagger;
import org.jitsi.nlj.stats.PacketStreamStats;
import org.jitsi.nlj.util.BufferPool;
import org.jitsi.nlj.util.PacketInfoQueue;
import org.jitsi.nlj.util.StreamInformationStore;
import org.jitsi.nlj.util.Util;
import org.jitsi.rtp.rtcp.RtcpPacket;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.queue.CountingErrorHandler;

import java.time.Duration;
import java.util.Collection;
import java.util.function.Function;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public class RtpSenderImpl extends RtpSender
{
    public static CountingErrorHandler queueErrorCounter = new CountingErrorHandler();

    private static final String PACKET_QUEUE_ENTRY_EVENT = "Entered RTP sender incoming queue";
    private static final String PACKET_QUEUE_EXIT_EVENT = "Exited RTP sender incoming queue";

    /**
     * (Deviation: replaces the upstream {@code jitsi-metaconfig}-based {@code queueSize} config. This port does not
     * use {@code jitsi-metaconfig}/HOCON, so this hardcodes the upstream {@code reference.conf} default for
     * {@code jmt.transceiver.send.queue-size}.)
     */
    private static final int queueSize = 1024;

    /**
     * Configuration for the packet loss to introduce in the send pipeline (for debugging/testing purposes).
     */
    private static final PacketLossConfig packetLossConfig = new PacketLossConfig("jmt.debug.packet-loss.outgoing");

    private final String id;
    private final RtcpEventNotifier rtcpEventNotifier;

    /**
     * The executor this class will use for its primary work (i.e. critical path
     * packet processing).  This {@link RtpSender} will execute a blocking queue read
     * on this executor.
     */
    private final ExecutorService executor;

    /**
     * A {@link ScheduledExecutorService} which can be used for less important
     * background tasks, or tasks that need to execute at some fixed delay/rate
     */
    private final ScheduledExecutorService backgroundExecutor;

    private final StreamInformationStore streamInformationStore;

    private final Logger logger;
    private final Node outgoingRtpRoot;
    private final Node outgoingRtxRoot;
    private final Node outgoingRtcpRoot;
    private final PacketInfoQueue incomingPacketQueue;
    private volatile boolean running = true;
    private Long localVideoSsrc = null;
    private Long localAudioSsrc = null;

    /**
     * Resolves the sdes:mid to stamp on an outgoing packet given its (rewritten) SSRC, or null to not stamp a mid.
     * Used under SSRC rewriting for mid-based demuxing on the receiving client.
     */
    private final Function<Long, String> getMidBySsrc;

    // TODO(brian): this is changed to a handler instead of a queue because we want to use
    // a PacketQueue, and the handler for a PacketQueue must be set at the time of creation.
    // since we want the handler to be another entity (something in jvb) we just use
    // a generic handler here and then the bridge can put it into its PacketQueue and have
    // its handler (likely in another thread) grab the packet and send it out
    private PacketHandler outgoingPacketHandler;

    private final TransportCcEngine transportCcEngine;

    private final SrtpEncryptNode srtpEncryptWrapper = new SrtpEncryptNode();
    private final SrtcpEncryptNode srtcpEncryptWrapper = new SrtcpEncryptNode();
    private final ToggleablePcapWriter toggleablePcapWriter;
    private final PacketCacher outgoingPacketCache = new PacketCacher();
    private final HeaderExtStripper headerExtensionStripper;
    private final AbsSendTime absSendTime;
    private final OutgoingStatisticsTracker statsTracker;
    private final PacketStreamStatsNode packetStreamStats;
    private final RtcpSrUpdater rtcpSrUpdater;
    private final KeyframeRequester keyframeRequester;

    /**
     * (Deviation: not {@code final} like upstream's Kotlin {@code val}. The {@code GoogleCc2} branch of
     * {@code transportCcEngine}'s construction below captures this field in a probe-sending callback before it is
     * assigned -- legal in Kotlin (the closure only reads it once construction completes), but javac's
     * definite-assignment analysis rejects a blank {@code final} field read from a lambda defined earlier in the
     * same constructor. Dropping {@code final} preserves the exact upstream construction order without changing
     * runtime behavior, since the callback is never invoked before construction finishes.)
     */
    private ProbingDataSender probingDataSender;

    private final NackHandler nackHandler;

    private final ConsumerNode outputPipelineTerminationNode = new ConsumerNode("Output pipeline termination node")
    {
        {
            this.aggregationKey = this.name;
        }

        @Override
        protected void consume(PacketInfo packetInfo)
        {
            // While there's no handler set we're effectively dropping packets, so their buffers
            // should be returned.
            if (outgoingPacketHandler != null)
            {
                outgoingPacketHandler.processPacket(packetInfo);
            }
            else
            {
                packetDiscarded(packetInfo);
            }
        }

        @Override
        protected void trace(Runnable f)
        {
            f.run();
        }
    };

    public RtpSenderImpl(
        String id,
        RtcpEventNotifier rtcpEventNotifier,
        ExecutorService executor,
        ScheduledExecutorService backgroundExecutor,
        StreamInformationStore streamInformationStore,
        Logger parentLogger,
        DiagnosticContext diagnosticContext,
        Function<Long, String> getMidBySsrc)
    {
        this.id = id;
        this.rtcpEventNotifier = rtcpEventNotifier;
        this.executor = executor;
        this.backgroundExecutor = backgroundExecutor;
        this.streamInformationStore = streamInformationStore;
        this.getMidBySsrc = getMidBySsrc;
        this.logger = parentLogger.createChildLogger(RtpSenderImpl.class.getName());

        this.incomingPacketQueue = new PacketInfoQueue(
            "rtp-sender-incoming-packet-queue",
            executor,
            this::handlePacket,
            queueSize
        );

        switch (BandwidthEstimatorConfig.engine)
        {
            case GoogleCc:
                this.transportCcEngine =
                    new ClassicTransportCcEngine(new GoogleCcEstimator(diagnosticContext, logger), logger);
                break;
            case GoogleCc2:
                this.transportCcEngine = new GoogCcTransportCcEngine(
                    diagnosticContext,
                    logger,
                    backgroundExecutor,
                    (dataSize, probingData) ->
                        probingDataSender.sendProbing(null, (int) dataSize.getBytes(), probingData)
                );
                break;
            default:
                throw new IllegalStateException("Unknown BandwidthEstimatorEngine: " + BandwidthEstimatorConfig.engine);
        }
        this.transportCcEngine.start();

        this.toggleablePcapWriter = new ToggleablePcapWriter(logger, id + "-tx");
        this.headerExtensionStripper = new HeaderExtStripper(streamInformationStore);
        this.absSendTime = new AbsSendTime(streamInformationStore);
        this.statsTracker = new OutgoingStatisticsTracker(diagnosticContext);
        this.packetStreamStats = new PacketStreamStatsNode(diagnosticContext, "send");
        this.rtcpSrUpdater = new RtcpSrUpdater(statsTracker);
        this.keyframeRequester = new KeyframeRequester(streamInformationStore, logger);

        logger.debug(() -> "Sender " + id + " using executor " + executor.hashCode());

        if (packetLossConfig.isEnabled())
        {
            logger.warn("Will simulate packet loss: " + packetLossConfig);
        }

        incomingPacketQueue.setErrorHandler(queueErrorCounter);

        outgoingRtpRoot = PipelineDsl.pipeline(builder -> {
            builder.node(new PluggableTransformerNode("RTP pre-processor", this::getPreProcesor));
            builder.node(new AudioRedHandler(streamInformationStore, logger));
            builder.node(headerExtensionStripper);
            builder.node(outgoingPacketCache);
            builder.node(absSendTime);
            // Placed right after absSendTime, which is where the RTX pipeline joins the main pipeline, so that both
            // media packets and their retransmissions get the mid stamped.
            builder.node(new MidStamper(streamInformationStore, getMidBySsrc));
            builder.node(statsTracker);
            builder.node(new TccSeqNumTagger(transportCcEngine, streamInformationStore));
            builder.node(new HeaderExtEncoder(streamInformationStore, logger));
            builder.node(toggleablePcapWriter.newObserverNode(true, "tx_rtp"));
            builder.node(srtpEncryptWrapper);
            builder.node(packetStreamStats.createNewNode());
            builder.node(new PacketLossNode(packetLossConfig), packetLossConfig::isEnabled);
            builder.node(outputPipelineTerminationNode);
        });

        outgoingRtxRoot = PipelineDsl.pipeline(builder -> {
            builder.node(new RetransmissionSender(streamInformationStore, logger));
            // We want RTX packets to hook into the main RTP pipeline starting at AbsSendTime
            builder.node(absSendTime);
        });

        nackHandler = new NackHandler(outgoingPacketCache.getPacketCache(), outgoingRtxRoot, logger);
        rtcpEventNotifier.addRtcpEventListener(nackHandler);
        rtcpEventNotifier.addRtcpEventListener(transportCcEngine);

        // TODO: are we setting outgoing rtcp sequence numbers correctly? just add a simple node here to rewrite them
        outgoingRtcpRoot = PipelineDsl.pipeline(builder -> {
            builder.node(new PluggableTransformerNode("RTCP pre-processor", this::getPreProcesor));
            builder.node(keyframeRequester);
            builder.node(new SentRtcpStats());
            // TODO(brian): not sure this is a great idea.  it works as a catch-call but can also be error-prone
            // (i found i was accidentally clobbering the sender ssrc for SRs which caused issues).  I think
            // it'd be better to notify everything creating RTCP the bridge SSRCs and then everything should be
            // responsible for setting it themselves
            builder.simpleNode("RTCP sender ssrc setter", packetInfo -> {
                Long senderSsrc = localVideoSsrc;
                if (senderSsrc == null)
                {
                    return packetInfo;
                }
                RtcpPacket rtcpPacket = packetInfo.packetAs();
                if (rtcpPacket.getSenderSsrc() == 0L)
                {
                    rtcpPacket.setSenderSsrc(senderSsrc);
                }
                return packetInfo;
            });
            builder.node(rtcpSrUpdater);
            builder.node(toggleablePcapWriter.newObserverNode(true, "tx_rtcp"));
            builder.node(new ObserverNode("RTCP sent notifier")
            {
                @Override
                protected void observe(PacketInfo packetInfo)
                {
                    if (packetInfo.getPacket() instanceof RtcpPacket)
                    {
                        rtcpEventNotifier.notifyRtcpSent((RtcpPacket) packetInfo.getPacket());
                    }
                }

                @Override
                public void trace(Runnable f)
                {
                }
            });
            builder.node(srtcpEncryptWrapper);
            builder.node(packetStreamStats.createNewNode());
            builder.node(new PacketLossNode(packetLossConfig), packetLossConfig::isEnabled);
            builder.node(outputPipelineTerminationNode);
        });

        probingDataSender = new ProbingDataSender(
            outgoingPacketCache.getPacketCache(),
            outgoingRtxRoot,
            absSendTime,
            diagnosticContext,
            streamInformationStore,
            logger
        );
    }

    @Override
    public void onRttUpdate(double newRttMs)
    {
        nackHandler.onRttUpdate(newRttMs);
        keyframeRequester.onRttUpdate(newRttMs);
        transportCcEngine.onRttUpdate(Duration.ofNanos((long) (newRttMs * 1e6)));
    }

    /**
     * Insert packets into the incoming packet queue
     */
    @Override
    protected void doProcessPacket(PacketInfo packetInfo)
    {
        if (running)
        {
            packetInfo.addEvent(PACKET_QUEUE_ENTRY_EVENT);
            incomingPacketQueue.add(packetInfo);
        }
        else
        {
            BufferPool.returnBuffer(packetInfo.getPacket().getBuffer());
        }
    }

    @Override
    public int sendProbing(Collection<Long> mediaSsrcs, int numBytes)
    {
        return probingDataSender.sendProbing(mediaSsrcs, numBytes, null);
    }

    @Override
    public void onOutgoingPacket(PacketHandler handler)
    {
        outgoingPacketHandler = handler;
    }

    @Override
    public void setSrtpTransformers(SrtpTransformers srtpTransformers)
    {
        srtpEncryptWrapper.setTransformer(srtpTransformers.getSrtpEncryptTransformer());
        srtcpEncryptWrapper.setTransformer(srtpTransformers.getSrtcpEncryptTransformer());
    }

    @Override
    public void requestKeyframe(String requesterID, Long mediaSsrc)
    {
        keyframeRequester.requestKeyframe(requesterID, mediaSsrc);
    }

    @Override
    public void addLossListener(LossListener lossListener)
    {
        transportCcEngine.addLossListener(lossListener);
    }

    @Override
    public void setFeature(Features feature, boolean enabled)
    {
        switch (feature)
        {
            case TRANSCEIVER_PCAP_DUMP:
                if (enabled)
                {
                    toggleablePcapWriter.enable();
                }
                else
                {
                    toggleablePcapWriter.disable();
                }
                break;
        }
    }

    @Override
    public boolean isFeatureEnabled(Features feature)
    {
        switch (feature)
        {
            case TRANSCEIVER_PCAP_DUMP:
                return toggleablePcapWriter.isEnabled();
            default:
                return false;
        }
    }

    /**
     * Handles packets that have gone through the incoming queue and sends them
     * through the sender pipeline
     */
    private boolean handlePacket(PacketInfo packetInfo)
    {
        if (running)
        {
            packetInfo.addEvent(PACKET_QUEUE_EXIT_EVENT);

            Node root = packetInfo.getPacket() instanceof RtcpPacket ? outgoingRtcpRoot : outgoingRtpRoot;
            root.processPacket(packetInfo);
            return true;
        }
        else
        {
            BufferPool.returnBuffer(packetInfo.getPacket().getBuffer());
            return false;
        }
    }

    @Override
    public OutgoingStatisticsSnapshot getStreamStats()
    {
        return statsTracker.getSnapshot();
    }

    @Override
    public PacketStreamStats.Snapshot getPacketStreamStats()
    {
        return packetStreamStats.snapshot();
    }

    @Override
    public TransportCcEngine.StatisticsSnapshot getTransportCcEngineStats()
    {
        return transportCcEngine.getStatistics();
    }

    @Override
    public void addBandwidthListener(TransportCcEngine.BandwidthListener listener)
    {
        transportCcEngine.addBandwidthListener(listener);
    }

    @Override
    public void removeBandwidthListener(TransportCcEngine.BandwidthListener listener)
    {
        transportCcEngine.removeBandwidthListener(listener);
    }

    @Override
    public void handleEvent(Event event)
    {
        if (event instanceof SetLocalSsrcEvent)
        {
            SetLocalSsrcEvent setLocalSsrcEvent = (SetLocalSsrcEvent) event;
            if (setLocalSsrcEvent.getMediaType() == MediaType.VIDEO)
            {
                localVideoSsrc = setLocalSsrcEvent.getSsrc();
            }
            else if (setLocalSsrcEvent.getMediaType() == MediaType.AUDIO)
            {
                localAudioSsrc = setLocalSsrcEvent.getSsrc();
            }
        }
        new NodeEventVisitor(event).reverseVisit(outputPipelineTerminationNode);
        probingDataSender.handleEvent(event);
    }

    @Override
    public ObjectNode debugState(DebugStateMode mode)
    {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        if (mode == DebugStateMode.FULL)
        {
            Util.appendAll(o, super.getNodeStats().toJson());
            o.set("packet_queue", incomingPacketQueue.getDebugState());
            o.put("running", running);
        }
        Util.appendAll(o, nackHandler.getNodeStats().toJson());
        o.set("probing_data_sender", probingDataSender.debugState(mode));
        o.put("local_video_ssrc", localVideoSsrc != null ? localVideoSsrc.toString() : "null");
        o.put("local_audio_ssrc", localAudioSsrc != null ? localAudioSsrc.toString() : "null");
        o.set("transport_cc_engine", transportCcEngine.getStatistics().toJson());
        new NodeDebugStateVisitor(o, mode).reverseVisit(outputPipelineTerminationNode);
        return o;
    }

    @Override
    public void stop()
    {
        running = false;
    }

    @Override
    public void tearDown()
    {
        logger.debug("Tearing down");
        new NodeTeardownVisitor().reverseVisit(outputPipelineTerminationNode);
        incomingPacketQueue.close();
        toggleablePcapWriter.disable();
    }

    @Override
    public void addRtpExtensionToRetain(RtpExtensionType extensionType)
    {
        headerExtensionStripper.addRtpExtensionToRetain(extensionType);
    }
}
