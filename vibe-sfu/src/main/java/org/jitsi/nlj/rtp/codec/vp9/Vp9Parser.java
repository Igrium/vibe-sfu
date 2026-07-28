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

package org.jitsi.nlj.rtp.codec.vp9;

import org.jitsi.nlj.MediaSourceDesc;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.RtpEncodingDesc;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.rtp.codec.VideoCodecParser;
import org.jitsi.nlj.rtp.codec.vpx.VpxRtpLayerDesc;
import org.jitsi.nlj.util.StateChangeLogger;
import org.jitsi.rtp.extensions.ByteArrayBufferExtensions;
import org.jitsi.utils.logging2.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Some {@link Vp9Packet} fields are not able to be determined by looking at a single VP9 packet (for example the
 * scalability structure is only carried in keyframes).  This class updates the layer descriptions with information
 * from frames, and also diagnoses packet format variants that the Jitsi videobridge won't be able to route.
 */
public class Vp9Parser extends VideoCodecParser
{
    private final Logger logger;

    private final StateChangeLogger pictureIdState;
    private final StateChangeLogger extendedPictureIdState;
    private int numSpatialLayers = -1;

    /** Encodings we've actually seen, and the layers seen for each one.
     * Used to clear out inferred-from-signaling encoding information, and to synthesize temporal layers
     * for flexible-mode encodings. */
    private final Map<Long, Map<Integer, Integer>> ssrcsInfo = new HashMap<>();

    public Vp9Parser(MediaSourceDesc source, Logger parentLogger)
    {
        super(source);
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.pictureIdState = new StateChangeLogger("missing picture id", logger);
        this.extendedPictureIdState = new StateChangeLogger("missing extended picture ID", logger);
    }

    @Override
    public void parse(PacketInfo packetInfo)
    {
        Vp9Packet vp9Packet = packetInfo.<Vp9Packet>packetAs();

        Map<Integer, Integer> layerMap = ssrcsInfo.computeIfAbsent(vp9Packet.getSsrc(), k -> new HashMap<>());

        Integer existingTid = layerMap.get(vp9Packet.getSpatialLayerIndex());
        if (existingTid != null)
        {
            layerMap.put(vp9Packet.getSpatialLayerIndex(), Math.max(existingTid, vp9Packet.getTemporalLayerIndex()));
        }
        else
        {
            layerMap.put(vp9Packet.getSpatialLayerIndex(), vp9Packet.getTemporalLayerIndex());
        }

        if (vp9Packet.hasScalabilityStructure())
        {
            // TODO: handle case where new SS is from a packet older than the
            //  latest SS we've seen.
            int packetSpatialLayers = vp9Packet.getScalabilityStructureNumSpatial();
            if (packetSpatialLayers != -1)
            {
                if (numSpatialLayers != -1 && numSpatialLayers != packetSpatialLayers)
                {
                    packetInfo.setLayeringChanged(true);
                }
                numSpatialLayers = packetSpatialLayers;
            }
            RtpEncodingDesc enc = findRtpEncodingDesc(vp9Packet);
            RtpEncodingDesc ss = enc != null ? vp9Packet.getScalabilityStructure(enc.getEid()) : null;

            if (ss != null)
            {
                RtpLayerDesc[] layers;
                if (vp9Packet.isFlexibleMode())
                {
                    /* In flexible mode, the number of temporal layers isn't announced in the keyframe.
                     * Thus, add temporal layer information to the source's encoding layers based on the temporal
                     * layers we've seen previously.
                     */
                    List<RtpLayerDesc> layersList = new ArrayList<>(Arrays.asList(ss.getLayers()));

                    for (Map.Entry<Integer, Integer> entry : layerMap.entrySet())
                    {
                        addTemporalLayers(layersList, entry.getKey(), entry.getValue());
                    }
                    layers = layersList.toArray(new RtpLayerDesc[0]);
                }
                else
                {
                    layers = ss.getLayers();
                }

                source.setEncodingLayers(layers, vp9Packet.getSsrc());

                for (RtpEncodingDesc otherEnc : source.getRtpEncodings())
                {
                    if (!ssrcsInfo.containsKey(otherEnc.getPrimarySSRC()))
                    {
                        source.setEncodingLayers(new RtpLayerDesc[0], otherEnc.getPrimarySSRC());
                    }
                }
            }
        }
        if (vp9Packet.getSpatialLayerIndex() > 0 && vp9Packet.isInterPicturePredicted())
        {
            /* Check if this layer is using K-SVC. */
            /* Note: In K-SVC mode, this entirely ignores the bitrate of lower-layer keyframes
             * when calculating layers' bitrates.  These values are small enough this is probably
             * fine, but revisit this if it turns out to be a problem.
             */
            for (RtpLayerDesc layer : findRtpLayerDescs(vp9Packet))
            {
                if (layer instanceof VpxRtpLayerDesc)
                {
                    ((VpxRtpLayerDesc) layer).setUseSoftDependencies(vp9Packet.usesInterLayerDependency());
                }
            }
        }

        if (vp9Packet.isFlexibleMode() && findRtpLayerDescs(vp9Packet).isEmpty())
        {
            List<RtpLayerDesc> layers = new ArrayList<>(
                Arrays.asList(source.getEncodingLayers(vp9Packet.getSsrc())));
            /* In flexible mode, the number of temporal layers isn't announced in the keyframe.
             * Thus, add temporal layer information to the source's encoding layers as we see packets with
             * temporal layers.
             */
            boolean changed = addTemporalLayers(layers, vp9Packet.getSpatialLayerIndex(), vp9Packet.getTemporalLayerIndex());
            if (changed)
            {
                source.setEncodingLayers(layers.toArray(new RtpLayerDesc[0]), vp9Packet.getSsrc());
                packetInfo.setLayeringChanged(true);
            }
        }

        pictureIdState.setState(
            vp9Packet.hasPictureId(),
            vp9Packet,
            () -> "Packet Data: " + ByteArrayBufferExtensions.toHex(vp9Packet, 80)
        );
        extendedPictureIdState.setState(
            vp9Packet.hasExtendedPictureId(),
            vp9Packet,
            () -> "Packet Data: " + ByteArrayBufferExtensions.toHex(vp9Packet, 80)
        );
    }

    /** Add temporal layers to the list of layers.  Needed if VP9 is encoded in flexible mode, because
     * in flexible mode the scalability structure doesn't describe the temporal layers.
     */
    private boolean addTemporalLayers(List<RtpLayerDesc> layers, int sid, int maxTid)
    {
        boolean changed = false;

        for (int tid = 1; tid <= maxTid; tid++)
        {
            RtpLayerDesc layer = findLayer(layers, sid, tid);
            if (layer == null)
            {
                RtpLayerDesc prevLayer = findLayer(layers, sid, tid - 1);
                if (prevLayer != null)
                {
                    RtpLayerDesc newLayer = prevLayer.copy(prevLayer.getHeight(), tid, false);
                    layers.add(newLayer);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static RtpLayerDesc findLayer(List<RtpLayerDesc> layers, int sid, int tid)
    {
        for (RtpLayerDesc layer : layers)
        {
            if (layer.getSid() == sid && layer.getTid() == tid)
            {
                return layer;
            }
        }
        return null;
    }
}
