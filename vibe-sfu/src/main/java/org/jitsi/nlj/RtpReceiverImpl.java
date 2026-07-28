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
import org.jitsi.nlj.rtcp.CompoundRtcpParser;
import org.jitsi.nlj.rtcp.RembHandler;
import org.jitsi.nlj.rtcp.RtcpEventNotifier;
import org.jitsi.nlj.rtcp.RtcpRrGenerator;
import org.jitsi.nlj.rtp.AudioRtpPacket;
import org.jitsi.nlj.rtp.LossListener;
import org.jitsi.nlj.rtp.TransportCcEngine;
import org.jitsi.nlj.rtp.VideoRtpPacket;
import org.jitsi.nlj.srtp.SrtpTransformers;
import org.jitsi.nlj.stats.RtpReceiverStats;
import org.jitsi.nlj.transform.NodeDebugStateVisitor;
import org.jitsi.nlj.transform.NodeEventVisitor;
import org.jitsi.nlj.transform.NodeTeardownVisitor;
import org.jitsi.nlj.transform.PipelineDsl;
import org.jitsi.nlj.transform.node.ConsumerNode;
import org.jitsi.nlj.transform.node.Node;
import org.jitsi.nlj.transform.node.PacketLossConfig;
import org.jitsi.nlj.transform.node.PacketLossNode;
import org.jitsi.nlj.transform.node.PacketStreamStatsNode;
import org.jitsi.nlj.transform.node.RtpParser;
import org.jitsi.nlj.transform.node.SrtcpDecryptNode;
import org.jitsi.nlj.transform.node.SrtpDecryptNode;
import org.jitsi.nlj.transform.node.ToggleablePcapWriter;
import org.jitsi.nlj.transform.node.incoming.AudioLevelReader;
import org.jitsi.nlj.transform.node.incoming.BitrateCalculator;
import org.jitsi.nlj.transform.node.incoming.DiscardableDiscarder;
import org.jitsi.nlj.transform.node.incoming.DuplicateTermination;
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsTracker;
import org.jitsi.nlj.transform.node.incoming.PaddingTermination;
import org.jitsi.nlj.transform.node.incoming.RemoteBandwidthEstimator;
import org.jitsi.nlj.transform.node.incoming.RetransmissionRequesterNode;
import org.jitsi.nlj.transform.node.incoming.RtcpTermination;
import org.jitsi.nlj.transform.node.incoming.RtxHandler;
import org.jitsi.nlj.transform.node.incoming.TccGeneratorNode;
import org.jitsi.nlj.transform.node.incoming.VideoBitrateCalculator;
import org.jitsi.nlj.transform.node.incoming.VideoMuteNode;
import org.jitsi.nlj.transform.node.incoming.VideoParser;
import org.jitsi.nlj.transform.node.incoming.VideoQualityLayerLookup;
import org.jitsi.nlj.transform.node.incoming.VlaReaderNode;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.BufferPool;
import org.jitsi.nlj.util.PacketInfoQueue;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.extensions.PacketExtensions;
import org.jitsi.rtp.rtcp.RtcpPacket;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.queue.CountingErrorHandler;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public class RtpReceiverImpl extends RtpReceiver
{
    /**
     * (Deviation: replaces the upstream {@code jitsi-metaconfig}-based {@code queueSize} config. This port does not
     * use {@code jitsi-metaconfig}/HOCON, so this hardcodes the upstream {@code reference.conf} default for
     * {@code jmt.transceiver.recv.queue-size}.)
     */
    private static final int queueSize = 1024;

    /**
     * Configuration for the packet loss to introduce in the receive pipeline (for debugging/testing purposes).
     */
    private static final PacketLossConfig packetLossConfig = new PacketLossConfig("jmt.debug.packet-loss.incoming");

    public static CountingErrorHandler queueErrorCounter = new CountingErrorHandler();

    private static final String PACKET_QUEUE_ENTRY_EVENT = "Entered RTP receiver incoming queue";
    private static final String PACKET_QUEUE_EXIT_EVENT = "Exited RTP receiver incoming queue";

    private final String id;

    /**
     * A function to be used when the receiver wants to send RTCP packets to the
     * participant it's receiving data from (NACK packets, for example)
     */
    private final Consumer<RtcpPacket> rtcpSender;

    /**
     * The executor this class will use for its primary work (i.e. critical path
     * packet processing).  This {@link RtpReceiver} will execute a blocking queue read
     * on this executor.
     */
    private final ExecutorService executor;

    private final Logger logger;
    private volatile boolean running = true;
    private final Node inputTreeRoot;
    private final PacketInfoQueue incomingPacketQueue;
    private final SrtpDecryptNode srtpDecryptWrapper = new SrtpDecryptNode();
    private final SrtcpDecryptNode srtcpDecryptWrapper = new SrtcpDecryptNode();
    private final TccGeneratorNode tccGenerator;
    private final RemoteBandwidthEstimator remoteBandwidthEstimator;
    private final AudioLevelReader audioLevelReader;

    private final VideoMuteNode videoMuteNode = new VideoMuteNode();

    private final DiscardableDiscarder silenceDiscarder = new DiscardableDiscarder("Silence discarder", false);
    private final DiscardableDiscarder paddingOnlyDiscarder = new DiscardableDiscarder("Padding-only discarder", true);
    private final IncomingStatisticsTracker statsTracker;
    private final PacketStreamStatsNode packetStreamStats;
    private final RtcpRrGenerator rtcpRrGenerator;
    private final RtcpTermination rtcpTermination;
    private final RetransmissionRequesterNode retransmissionRequester;
    private final RembHandler rembHandler;
    private final ToggleablePcapWriter toggleablePcapWriter;
    private final VideoBitrateCalculator videoBitrateCalculator;
    private final BitrateCalculator audioBitrateCalculator = new BitrateCalculator("Audio bitrate calculator");

    private final VideoParser videoParser;

    private final ReadOnlyStreamInformationStore streamInformationStore;
    private final RtpReceiverEventHandler eventHandler;

    @Override
    public boolean isReceivingAudio()
    {
        return audioBitrateCalculator.isActive();
    }

    @Override
    public boolean isReceivingVideo()
    {
        return videoBitrateCalculator.isActive();
    }

    @Override
    public void addLossListener(LossListener lossListener)
    {
        tccGenerator.addLossListener(lossListener);
    }

    /**
     * {@link #getPacketHandler()}/{@link #setPacketHandler(PacketHandler)} will be invoked with RTP packets that
     * have made it through the entire receive pipeline.  Some external entity should assign it to a
     * {@link PacketHandler} with appropriate logic.
     */
    private PacketHandler packetHandler;

    @Override
    public PacketHandler getPacketHandler()
    {
        return packetHandler;
    }

    @Override
    public void setPacketHandler(PacketHandler packetHandler)
    {
        this.packetHandler = packetHandler;
    }

    /**
     * The {@link #packetHandler} can be re-assigned at any time, but it should maintain
     * its place in the receive pipeline.  To support both keeping it in the same
     * place and allowing it to be re-assigned, we wrap it with this.
     */
    private final ConsumerNode packetHandlerWrapper = new ConsumerNode("Input pipeline termination node")
    {
        {
            this.aggregationKey = this.name;
        }

        @Override
        protected void consume(PacketInfo packetInfo)
        {
            // When there's no handler set we're effectively dropping packets, so their buffers
            // should be returned.
            if (packetHandler != null)
            {
                packetHandler.processPacket(packetInfo);
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

    public RtpReceiverImpl(
        String id,
        Consumer<RtcpPacket> rtcpSender,
        RtcpEventNotifier rtcpEventNotifier,
        ExecutorService executor,
        ScheduledExecutorService backgroundExecutor,
        ReadOnlyStreamInformationStore streamInformationStore,
        RtpReceiverEventHandler eventHandler,
        Logger parentLogger,
        DiagnosticContext diagnosticContext)
    {
        this.id = id;
        this.rtcpSender = rtcpSender;
        this.executor = executor;
        this.streamInformationStore = streamInformationStore;
        this.eventHandler = eventHandler;
        this.logger = parentLogger.createChildLogger(RtpReceiverImpl.class.getName());

        this.incomingPacketQueue = new PacketInfoQueue(
            "rtp-receiver-incoming-packet-queue",
            executor,
            this::handleIncomingPacket,
            queueSize
        );

        this.tccGenerator = new TccGeneratorNode(rtcpSender, streamInformationStore, logger);
        this.remoteBandwidthEstimator = new RemoteBandwidthEstimator(streamInformationStore, logger, diagnosticContext);
        this.audioLevelReader = new AudioLevelReader(streamInformationStore);
        this.audioLevelReader.setAudioLevelListener(new AudioLevelListener()
        {
            @Override
            public boolean onLevelReceived(long sourceSsrc, long level)
            {
                return eventHandler.audioLevelReceived(sourceSsrc, level);
            }
        });

        this.statsTracker = new IncomingStatisticsTracker(streamInformationStore);
        this.packetStreamStats = new PacketStreamStatsNode(diagnosticContext, "receive");
        this.rtcpRrGenerator = new RtcpRrGenerator(
            backgroundExecutor,
            rtcpSender,
            statsTracker,
            () -> {
                RtcpPacket remb = remoteBandwidthEstimator.createRemb();
                return remb != null ? Collections.singletonList(remb) : Collections.emptyList();
            }
        );
        this.rtcpTermination = new RtcpTermination(rtcpEventNotifier, logger);
        this.retransmissionRequester = new RetransmissionRequesterNode(rtcpSender, backgroundExecutor, logger);
        this.rembHandler = new RembHandler(streamInformationStore, logger);
        this.rembHandler.addListener(new TransportCcEngine.BandwidthListener()
        {
            @Override
            public void bandwidthEstimationChanged(Bandwidth newValue)
            {
                eventHandler.bandwidthEstimationChanged(newValue);
            }
        });
        this.toggleablePcapWriter = new ToggleablePcapWriter(logger, id + "-rx");
        this.videoBitrateCalculator = new VideoBitrateCalculator(parentLogger);

        this.videoParser = new VideoParser(streamInformationStore, logger, diagnosticContext);

        logger.debug(() -> "using executor " + executor.hashCode());

        if (packetLossConfig.isEnabled())
        {
            logger.warn("Will simulate packet loss: " + packetLossConfig);
        }

        rtcpEventNotifier.addRtcpEventListener(rtcpRrGenerator);
        rtcpEventNotifier.addRtcpEventListener(rembHandler);

        incomingPacketQueue.setErrorHandler(queueErrorCounter);

        inputTreeRoot = PipelineDsl.pipeline(builder -> {
            builder.node(packetStreamStats);
            builder.demux("SRTP/SRTCP", demuxer -> {
                PipelineDsl.packetPath(demuxer, path -> {
                    path.setName("SRTP");
                    path.setPredicate(PacketExtensions::looksLikeRtp);
                    path.setPath(PipelineDsl.pipeline(rtp -> {
                        rtp.node(new PacketLossNode(packetLossConfig), packetLossConfig::isEnabled);
                        rtp.node(new RtpParser(streamInformationStore, logger));
                        // TODO: temporarily putting the audioLevelReader node here such that we can determine
                        // whether or not a packet should be discarded before doing SRTP. audioLevelReader has been
                        // moved here (instead of introducing a different class to read audio levels) to avoid
                        // parsing the RTP header extensions twice (which is expensive). In the future we will parse
                        // and cache the header extensions to make this lookup more efficient, at which time we could
                        // move audioLevelReader back to where it was (in the audio path) and add a new node here
                        // which would check for different discard conditions (i.e. checking the audio level for
                        // silence)
                        rtp.node(audioLevelReader.getPreDecryptNode());
                        rtp.node(videoMuteNode);
                        rtp.node(srtpDecryptWrapper);
                        rtp.node(tccGenerator);
                        rtp.node(remoteBandwidthEstimator);
                        // This reads audio levels from packets that use cryptex. TODO: should it go in the Audio
                        // path?
                        rtp.node(audioLevelReader.getPostDecryptNode());
                        rtp.node(toggleablePcapWriter.newObserverNode(false, "rx_rtp"));
                        rtp.node(statsTracker);
                        rtp.node(new PaddingTermination(logger));
                        rtp.demux("Media Type", mediaTypeDemuxer -> {
                            PipelineDsl.packetPath(mediaTypeDemuxer, audioPath -> {
                                audioPath.setName("Audio");
                                audioPath.setPredicate(packet -> packet instanceof AudioRtpPacket);
                                audioPath.setPath(PipelineDsl.pipeline(audio -> {
                                    audio.node(silenceDiscarder);
                                    audio.node(audioBitrateCalculator);
                                    audio.node(packetHandlerWrapper);
                                }));
                            });
                            PipelineDsl.packetPath(mediaTypeDemuxer, videoPath -> {
                                videoPath.setName("Video");
                                videoPath.setPredicate(packet -> packet instanceof VideoRtpPacket);
                                videoPath.setPath(PipelineDsl.pipeline(video -> {
                                    video.node(new RtxHandler(streamInformationStore, logger));
                                    video.node(new DuplicateTermination());
                                    video.node(retransmissionRequester);
                                    video.node(paddingOnlyDiscarder);
                                    video.node(videoParser);
                                    video.node(new VideoQualityLayerLookup(logger));
                                    video.node(videoBitrateCalculator);
                                    video.node(new VlaReaderNode(streamInformationStore, logger));
                                    video.node(packetHandlerWrapper);
                                }));
                            });
                        });
                    }));
                });
                PipelineDsl.packetPath(demuxer, path -> {
                    path.setName("SRTCP");
                    path.setPredicate(PacketExtensions::looksLikeRtcp);
                    path.setPath(PipelineDsl.pipeline(rtcp -> {
                        rtcp.node(srtcpDecryptWrapper);
                        rtcp.node(toggleablePcapWriter.newObserverNode(false, "rx_rtcp"));
                        rtcp.node(new CompoundRtcpParser(logger));
                        rtcp.node(rtcpTermination);
                        rtcp.node(packetHandlerWrapper);
                    }));
                });
            });
        });
    }

    public RtpReceiverImpl(
        String id,
        Consumer<RtcpPacket> rtcpSender,
        RtcpEventNotifier rtcpEventNotifier,
        ExecutorService executor,
        ScheduledExecutorService backgroundExecutor,
        ReadOnlyStreamInformationStore streamInformationStore,
        RtpReceiverEventHandler eventHandler,
        Logger parentLogger)
    {
        this(
            id,
            rtcpSender,
            rtcpEventNotifier,
            executor,
            backgroundExecutor,
            streamInformationStore,
            eventHandler,
            parentLogger,
            new DiagnosticContext()
        );
    }

    private boolean handleIncomingPacket(PacketInfo packet)
    {
        if (running)
        {
            packet.addEvent(PACKET_QUEUE_EXIT_EVENT);
            processPacket(packet);
            return true;
        }
        else
        {
            BufferPool.returnBuffer(packet.getPacket().getBuffer());
            return false;
        }
    }

    @Override
    protected void doProcessPacket(PacketInfo packetInfo)
    {
        inputTreeRoot.processPacket(packetInfo);
    }

    @Override
    public ObjectNode debugState(DebugStateMode mode)
    {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        new NodeDebugStateVisitor(o, mode).visit(inputTreeRoot);
        return o;
    }

    @Override
    public void enqueuePacket(PacketInfo p)
    {
        if (running)
        {
            p.addEvent(PACKET_QUEUE_ENTRY_EVENT);
            incomingPacketQueue.add(p);
        }
        else
        {
            BufferPool.returnBuffer(p.getPacket().getBuffer());
        }
    }

    @Override
    public void setSrtpTransformers(SrtpTransformers srtpTransformers)
    {
        srtpDecryptWrapper.setTransformer(srtpTransformers.getSrtpDecryptTransformer());
        srtcpDecryptWrapper.setTransformer(srtpTransformers.getSrtcpDecryptTransformer());
    }

    @Override
    public void handleEvent(Event event)
    {
        new NodeEventVisitor(event).visit(inputTreeRoot);
    }

    @Override
    public RtpReceiverStats getStats()
    {
        return new RtpReceiverStats(statsTracker.getSnapshot(), packetStreamStats.snapshot(), videoParser.getStats());
    }

    @Override
    public void forceMuteAudio(boolean shouldMute)
    {
        audioLevelReader.setForceMute(shouldMute);
    }

    @Override
    public void forceMuteVideo(boolean shouldMute)
    {
        videoMuteNode.setForceMute(shouldMute);
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

    @Override
    public void stop()
    {
        running = false;
        rtcpRrGenerator.setRunning(false);
        retransmissionRequester.stop();
    }

    @Override
    public void tearDown()
    {
        logger.debug("Tearing down");
        new NodeTeardownVisitor().visit(inputTreeRoot);
        incomingPacketQueue.close();
        toggleablePcapWriter.disable();
    }

    @Override
    public void onRttUpdate(double newRttMs)
    {
        remoteBandwidthEstimator.onRttUpdate(newRttMs);
    }
}
