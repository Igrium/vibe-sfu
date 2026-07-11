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
package org.jitsi.nlj.transform.node.incoming;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.Event;
import org.jitsi.nlj.MediaSourceDesc;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.RtpEncodingDesc;
import org.jitsi.nlj.SetMediaSourcesEvent;
import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.format.Vp8PayloadType;
import org.jitsi.nlj.format.Vp9PayloadType;
import org.jitsi.nlj.rtp.ParsedVideoPacket;
import org.jitsi.nlj.rtp.RtpExtensionType;
import org.jitsi.nlj.rtp.codec.VideoCodecParser;
import org.jitsi.nlj.rtp.codec.av1.Av1DDParser;
import org.jitsi.nlj.rtp.codec.vp8.Vp8Packet;
import org.jitsi.nlj.rtp.codec.vp8.Vp8Parser;
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet;
import org.jitsi.nlj.rtp.codec.vp9.Vp9Parser;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.TransformerNode;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Parse video packets at a codec level
 */
public class VideoParser extends TransformerNode
{
    private final ReadOnlyStreamInformationStore streamInformationStore;
    private final DiagnosticContext diagnosticContext;
    private final Logger logger;
    private final Stats stats = new Stats();

    private MediaSourceDesc[] sources = new MediaSourceDesc[0];
    private MediaSourceDesc[] signaledSources = sources;

    private Integer av1DDExtId;

    private final Map<Long, VideoCodecParser> videoCodecParsers = new HashMap<>();

    public VideoParser(
        ReadOnlyStreamInformationStore streamInformationStore,
        Logger parentLogger,
        DiagnosticContext diagnosticContext)
    {
        super("Video parser");
        this.streamInformationStore = streamInformationStore;
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.diagnosticContext = diagnosticContext;

        streamInformationStore.onRtpExtensionMapping(
            RtpExtensionType.AV1_DEPENDENCY_DESCRIPTOR,
            id -> av1DDExtId = id
        );
    }

    @Override
    protected PacketInfo transform(PacketInfo packetInfo)
    {
        RtpPacket packet = packetInfo.packetAs();
        Integer av1DDExtId = this.av1DDExtId; // So null checks work
        PayloadType payloadType = streamInformationStore.getRtpPayloadTypes().get((byte) packet.getPayloadType());
        if (payloadType == null)
        {
            logger.error(
                "Unrecognized video payload type " + packet.getPayloadType() +
                    ", cannot parse video information"
            );
            stats.numPacketsDroppedUnknownPt++;
            return null;
        }

        VideoCodecParser videoCodecParser;
        ParsedVideoPacket parsedPacket;
        try
        {
            if (payloadType instanceof Vp8PayloadType)
            {
                ParseResult result = parseNormalPayload(packetInfo, Vp8Packet::new, Vp8Parser.class,
                    source -> new Vp8Parser(source, logger));
                videoCodecParser = result.parser;
                parsedPacket = result.packet;
            }
            else if (payloadType instanceof Vp9PayloadType)
            {
                ParseResult result = parseNormalPayload(packetInfo, Vp9Packet::new, Vp9Parser.class,
                    source -> new Vp9Parser(source, logger));
                videoCodecParser = result.parser;
                parsedPacket = result.packet;
            }
            else if (av1DDExtId != null && packet.getHeaderExtension(av1DDExtId) != null)
            {
                Av1DDParser parser = checkParserType(packetInfo, Av1DDParser.class,
                    source -> new Av1DDParser(source, logger, diagnosticContext));
                videoCodecParser = parser;

                ParsedVideoPacket av1DDPacket = null;
                if (parser != null)
                {
                    av1DDPacket = parser.createFrom(packet, av1DDExtId);
                    packetInfo.setPacket(av1DDPacket);
                    packetInfo.resetPayloadVerification();
                }

                parsedPacket = av1DDPacket;
            }
            else
            {
                VideoCodecParser curParser = videoCodecParsers.get(packet.getSsrc());
                if (curParser != null)
                {
                    Class<?> curParserClass = curParser.getClass();
                    PayloadType finalPayloadType = payloadType;
                    logger.debug(() -> "Removing videoCodecParser on " + finalPayloadType.getClass() + " packet, " +
                        "current videoCodecParser is " + curParserClass);
                    MediaSourceDesc source = MediaSourceDesc.findRtpSource(sources, packet);
                    if (source != null)
                    {
                        resetSource(source);
                        for (RtpEncodingDesc enc : source.getRtpEncodings())
                        {
                            videoCodecParsers.remove(enc.getPrimarySSRC());
                        }
                    }
                    packetInfo.setLayeringChanged(true);
                }
                return packetInfo;
            }

            if (videoCodecParser != null)
            {
                videoCodecParser.parse(packetInfo);
            }
        }
        catch (Exception e)
        {
            int len = Math.min(packet.getLength(), 80);
            logger.error(
                "Exception parsing video packet.  Packet data is: " +
                    ByteArrayExtensions.toHex(packet.getBuffer(), packet.getOffset(), len),
                e
            );
            return null;
        }

        /* Some codecs mark keyframes in every packet of the keyframe - only count the start of the frame,
         * so the count is correct. */
        /* Alternately we could keep track of keyframes we've already seen, by timestamp, but that seems
         * unnecessary. */
        if (parsedPacket != null && parsedPacket.isKeyframe() && parsedPacket.isStartOfFrame())
        {
            logger.debug(() -> "Received a keyframe for ssrc " + packet.getSsrc() + " at seq " +
                packet.getSequenceNumber());
            stats.numKeyframes++;
        }
        if (packetInfo.isLayeringChanged())
        {
            logger.debug(() -> "Layering structure changed for ssrc " + packet.getSsrc() + " at seq " +
                packet.getSequenceNumber());
            stats.numLayeringChanges++;
        }

        return packetInfo;
    }

    /**
     * A normal payload is one where we choose the subclass of the ParsedVideoPacket and VideoCodecParser
     * based on the payload type, as opposed to the header extension (like AV1).  If the packet doesn't
     * satisfy {@link ParsedVideoPacket#meetsRoutingNeeds()} but it has an AV1 DD header extension, we will parse
     * this packet as AV1 rather than as its normal type.
     */
    private <PT extends ParsedVideoPacket, T extends VideoCodecParser> ParseResult parseNormalPayload(
        PacketInfo packetInfo,
        Packet.OtherTypeCreator<PT> otherTypeCreator,
        Class<T> parserClass,
        Function<MediaSourceDesc, T> parserConstructor)
    {
        ParsedVideoPacket parsedPacket = packetInfo.getPacket().toOtherType(otherTypeCreator);
        if (!parsedPacket.meetsRoutingNeeds())
        {
            // See if we can parse this packet as AV1
            RtpPacket packet = packetInfo.packetAs();
            Integer av1DDExtId = this.av1DDExtId; // So null checks work
            if (av1DDExtId != null && packet.getHeaderExtension(av1DDExtId) != null)
            {
                Av1DDParser parser = checkParserType(packetInfo, Av1DDParser.class,
                    source -> new Av1DDParser(source, logger, diagnosticContext));

                ParsedVideoPacket av1DDPacket = null;
                if (parser != null)
                {
                    av1DDPacket = parser.createFrom(packet, av1DDExtId);
                    packetInfo.setPacket(av1DDPacket);
                    packetInfo.resetPayloadVerification();
                }

                return new ParseResult(av1DDPacket, parser);
            }
        }
        packetInfo.setPacket(parsedPacket);
        packetInfo.resetPayloadVerification();

        T parser = checkParserType(packetInfo, parserClass, parserConstructor);

        return new ParseResult(parsedPacket, parser);
    }

    private <T extends VideoCodecParser> T checkParserType(
        PacketInfo packetInfo,
        Class<T> clazz,
        Function<MediaSourceDesc, T> constructor)
    {
        RtpPacket packet = packetInfo.packetAs();
        VideoCodecParser parser = videoCodecParsers.get(packet.getSsrc());
        if (clazz.isInstance(parser))
        {
            return clazz.cast(parser);
        }

        MediaSourceDesc source = MediaSourceDesc.findRtpSource(sources, packet);
        if (source == null)
        {
            // VideoQualityLayerLookup will drop this packet later, so no need to warn about it now
            return null;
        }
        VideoCodecParser finalParser = parser;
        logger.debug(() -> "Creating new " + clazz.getSimpleName() + " for source " + source.getSourceName() +
            ", current videoCodecParser is " + (finalParser != null ? finalParser.getClass().getSimpleName() : null));
        resetSource(source);
        packetInfo.setLayeringChanged(true);
        T newParser = constructor.apply(source);
        for (RtpEncodingDesc enc : source.getRtpEncodings())
        {
            videoCodecParsers.put(enc.getPrimarySSRC(), newParser);
        }

        return newParser;
    }

    @Override
    public void handleEvent(Event event)
    {
        if (event instanceof SetMediaSourcesEvent)
        {
            SetMediaSourcesEvent e = (SetMediaSourcesEvent) event;
            sources = e.getMediaSourceDescs();
            signaledSources = e.getSignaledMediaSourceDescs();
            Set<Long> ssrcsSeen = new HashSet<>();
            for (MediaSourceDesc source : sources)
            {
                for (RtpEncodingDesc enc : source.getRtpEncodings())
                {
                    VideoCodecParser parser = videoCodecParsers.get(enc.getPrimarySSRC());
                    if (parser != null)
                    {
                        parser.source = source;
                    }
                    ssrcsSeen.add(enc.getPrimarySSRC());
                }
            }
            videoCodecParsers.keySet().removeIf(ssrc -> !ssrcsSeen.contains(ssrc));
        }
        super.handleEvent(event);
    }

    private void resetSource(MediaSourceDesc source)
    {
        MediaSourceDesc signaledSource =
            MediaSourceDesc.findRtpSourceByPrimary(signaledSources, source.getPrimarySSRC());
        if (signaledSource == null)
        {
            logger.warn("Unable to find signaled source corresponding to " + source.getPrimarySSRC());
            return;
        }
        logger.debug(() -> "Resetting source " + source.getSourceName() + " to signaled source: " + signaledSource);
        for (RtpEncodingDesc signaledEncoding : signaledSource.getRtpEncodings())
        {
            source.setEncodingLayers(signaledEncoding.getLayers(), signaledEncoding.getPrimarySSRC());
        }
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        stats.addToNodeStatsBlock(block);
        return block;
    }

    @Override
    public ObjectNode statsJson()
    {
        ObjectNode json = super.statsJson();
        stats.addToJson(json);
        return json;
    }

    public Stats.Snapshot getStats()
    {
        return stats.snapshot();
    }

    private static final class ParseResult
    {
        final ParsedVideoPacket packet;
        final VideoCodecParser parser;

        ParseResult(ParsedVideoPacket packet, VideoCodecParser parser)
        {
            this.packet = packet;
            this.parser = parser;
        }
    }

    public static class Stats
    {
        public int numKeyframes = 0;
        public int numLayeringChanges = 0;
        public int numPacketsDroppedUnknownPt = 0;

        public Snapshot snapshot()
        {
            return new Snapshot(numKeyframes, numLayeringChanges, numPacketsDroppedUnknownPt);
        }

        public void addToNodeStatsBlock(NodeStatsBlock nodeStatsBlock)
        {
            nodeStatsBlock.addNumber("num_packets_dropped_unknown_pt", numPacketsDroppedUnknownPt);
            nodeStatsBlock.addNumber("num_keyframes", numKeyframes);
            nodeStatsBlock.addNumber("num_layering_changes", numLayeringChanges);
        }

        public void addToJson(ObjectNode o)
        {
            o.put("num_packets_dropped_unknown_pt", numPacketsDroppedUnknownPt);
            o.put("num_keyframes", numKeyframes);
            o.put("num_layering_changes", numLayeringChanges);
        }

        public static class Snapshot
        {
            private final int numKeyframes;
            private final int numLayeringChanges;
            private final int numPacketsDroppedUnknownPt;

            public Snapshot(int numKeyframes, int numLayeringChanges, int numPacketsDroppedUnknownPt)
            {
                this.numKeyframes = numKeyframes;
                this.numLayeringChanges = numLayeringChanges;
                this.numPacketsDroppedUnknownPt = numPacketsDroppedUnknownPt;
            }

            public int getNumKeyframes()
            {
                return numKeyframes;
            }

            public int getNumLayeringChanges()
            {
                return numLayeringChanges;
            }

            public int getNumPacketsDroppedUnknownPt()
            {
                return numPacketsDroppedUnknownPt;
            }

            public ObjectNode toJson()
            {
                ObjectNode o = JsonNodeFactory.instance.objectNode();
                o.put("num_packets_dropped_unknown_pt", numPacketsDroppedUnknownPt);
                o.put("num_keyframes", numKeyframes);
                o.put("num_layering_changes", numLayeringChanges);
                return o;
            }
        }
    }
}
