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
package org.jitsi.nlj;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.rtp.SsrcAssociationType;
import org.jitsi.nlj.stats.NodeStatsBlock;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Keeps track of information specific to an RTP encoded stream
 * (and its associated secondary sources).
 *
 * @author Jonathan Lennox
 */
public class RtpEncodingDesc
{
    /**
     * The primary SSRC for this encoding.
     */
    private final long primarySSRC;

    /**
     * The ID of this encoding.
     */
    private final int eid;

    /**
     * The ssrcs associated with this encoding (for example, RTX or FLEXFEC)
     * Maps ssrc -> type {@link SsrcAssociationType} (rtx, etc.)
     */
    private final Map<Long, SsrcAssociationType> secondarySsrcs = new HashMap<>();

    private int nominalHeight;

    /* package-private (matches Kotlin's `internal var layers`) */
    RtpLayerDesc[] layers;

    public RtpEncodingDesc(long primarySSRC, RtpLayerDesc[] initialLayers, int eid)
    {
        this.primarySSRC = primarySSRC;
        this.eid = eid;
        validateLayerEids(initialLayers);
        // NOTE: mirrors upstream's Kotlin property-initializer semantics: the initial assignment of `layers`
        // bypasses the custom setter (setLayers()) below, which is only invoked on later re-assignment (see
        // MediaSourceDesc.setEncodingLayers()).
        this.nominalHeight = getNominalHeight(initialLayers);
        this.layers = initialLayers;
    }

    public RtpEncodingDesc(long primarySSRC, RtpLayerDesc[] initialLayers)
    {
        this(primarySSRC, initialLayers, requireEid(initialLayers));
    }

    public RtpEncodingDesc(long primarySSRC, int eid)
    {
        this(primarySSRC, new RtpLayerDesc[0], eid);
    }

    private static int requireEid(RtpLayerDesc[] initialLayers)
    {
        if (initialLayers.length == 0)
        {
            throw new IllegalArgumentException("initialLayers may not be empty if no explicit EID is provided");
        }
        return initialLayers[0].getEid();
    }

    public long getPrimarySSRC()
    {
        return primarySSRC;
    }

    public int getEid()
    {
        return eid;
    }

    public RtpLayerDesc[] getLayers()
    {
        return layers;
    }

    public void addSecondarySsrc(long ssrc, SsrcAssociationType type)
    {
        secondarySsrcs.put(ssrc, type);
    }

    /**
     * All SSRCs (primary and secondary) associated with this encoding.
     */
    public java.util.Collection<Long> getSsrcs()
    {
        HashSet<Long> set = new HashSet<>();
        set.add(primarySSRC);
        set.addAll(secondarySsrcs.keySet());
        return set;
    }

    private void validateLayerEids(RtpLayerDesc[] layers)
    {
        for (RtpLayerDesc layer : layers)
        {
            if (layer.getEid() != eid)
            {
                throw new IllegalArgumentException(
                    "Cannot add layer with EID " + layer.getEid() + " to encoding with EID " + eid
                );
            }
        }
    }

    public void setLayers(RtpLayerDesc[] newLayers)
    {
        validateLayerEids(newLayers);
        /* Check if the new layer set is a single spatial layer that doesn't specify a height - if so, we
         * want to apply the nominal height to them.
         */
        boolean useNominalHeight = nominalHeight != RtpLayerDesc.NO_HEIGHT
            && allMatch(newLayers, l -> l.getSid() == 0)
            && allMatch(newLayers, l -> l.getHeight() == RtpLayerDesc.NO_HEIGHT);
        /* Copy the rate statistics objects from the old layers to the new layers
         * with matching layer IDs.
         */
        /* Note: because layer arrays are sorted by ID we could avoid creating this
         * intermediate map object, and do this in a single pass in O(1) space.
         * The number of layers is small enough that this more complicated code
         * is probably unnecessary, though.
         */
        Map<Integer, RtpLayerDesc> oldLayerMap = new HashMap<>();
        if (this.layers != null)
        {
            for (RtpLayerDesc oldLayer : this.layers)
            {
                oldLayerMap.put(oldLayer.getLayerId(), oldLayer);
            }
        }
        for (RtpLayerDesc newLayer : newLayers)
        {
            RtpLayerDesc oldLayer = oldLayerMap.get(newLayer.getLayerId());
            if (oldLayer != null)
            {
                newLayer.inheritFrom(oldLayer);
            }
            if (useNominalHeight)
            {
                newLayer.setHeight(nominalHeight);
            }
        }
        if (!useNominalHeight)
        {
            int newNominalHeight = getNominalHeight(newLayers);
            if (newNominalHeight != RtpLayerDesc.NO_HEIGHT)
            {
                nominalHeight = newNominalHeight;
            }
        }
        this.layers = newLayers;
    }

    /**
     * (Library addition) Directly replace this encoding's layers, bypassing the eid-validation /
     * nominal-height / inherit-from-old-layers logic performed by {@link #setLayers}. Mirrors upstream's
     * Kotlin {@code internal var layers}, which callers in the same module (e.g. Vp8Parser: {@code enc.layers =
     * newLayers}) assign to directly. {@code layers} itself can't be made accessible the same way in Java
     * (package-private only reaches {@code org.jitsi.nlj}, not the {@code org.jitsi.nlj.rtp.codec.*} subpackages
     * that need this), so this method exists to give those callers the same raw-assignment semantics.
     */
    public void setLayersDirect(RtpLayerDesc[] layers)
    {
        this.layers = layers;
    }

    private static boolean allMatch(RtpLayerDesc[] layers, java.util.function.Predicate<RtpLayerDesc> predicate)
    {
        for (RtpLayerDesc layer : layers)
        {
            if (!predicate.test(layer))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the "id" of a layer within this source, across all encodings. This is a server-side id and should
     * not be confused with any encoding id defined in the client (such as the
     * rid). This server-side id is used in the layer lookup table that is
     * maintained in {@link MediaSourceDesc}.
     */
    public long encodingId(RtpLayerDesc layer)
    {
        return calcEncodingId(primarySSRC, layer.getLayerId());
    }

    /**
     * Get the secondary ssrc for this encoding that corresponds to the given
     * type
     * @param type the type of the secondary ssrc (e.g. RTX)
     * @return the ssrc for the encoding that corresponds to the given type,
     * if it exists; otherwise -1
     */
    public long getSecondarySsrc(SsrcAssociationType type)
    {
        for (Map.Entry<Long, SsrcAssociationType> entry : secondarySsrcs.entrySet())
        {
            if (entry.getValue() == type)
            {
                return entry.getKey();
            }
        }
        return -1;
    }

    /**
     * Clone an existing encoding desc, inheriting layer descs' statistics,
     * modifying only specific values.
     */
    public RtpEncodingDesc copy(long primarySSRC, RtpLayerDesc[] layers)
    {
        RtpEncodingDesc copy = new RtpEncodingDesc(primarySSRC, layers, eid);
        this.secondarySsrcs.forEach(copy::addSecondarySsrc);
        return copy;
    }

    public RtpEncodingDesc copy()
    {
        RtpLayerDesc[] layersCopy = new RtpLayerDesc[this.layers.length];
        for (int i = 0; i < this.layers.length; i++)
        {
            layersCopy[i] = this.layers[i].copy();
        }
        return copy(this.primarySSRC, layersCopy);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        StringBuilder layersString = new StringBuilder();
        for (int i = 0; i < layers.length; i++)
        {
            if (i > 0)
            {
                layersString.append("\n    ");
            }
            layersString.append(layers[i]);
        }
        return "primary_ssrc=" + primarySSRC + ",secondary_ssrcs=" + secondarySsrcs + "," +
            "layers=\n    " + layersString;
    }

    /**
     * Gets a boolean indicating whether the SSRC specified in the
     * arguments is used by this encoding.
     *
     * @param ssrc the SSRC to match.
     */
    public boolean hasSsrc(long ssrc)
    {
        if (primarySSRC == ssrc)
        {
            return true;
        }
        return secondarySsrcs.containsKey(ssrc);
    }

    /**
     * Extracts a {@link NodeStatsBlock} from an {@link RtpEncodingDesc}.
     */
    public ObjectNode debugState()
    {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("rtx_ssrc", getSecondarySsrc(SsrcAssociationType.RTX));
        node.put("fec_ssrc", getSecondarySsrc(SsrcAssociationType.FEC));
        node.put("eid", eid);
        node.put("nominal_height", nominalHeight);
        for (RtpLayerDesc layer : layers)
        {
            node.set(layer.indexString(), layer.debugState());
        }
        return node;
    }

    public static long calcEncodingId(long ssrc, int layerId)
    {
        return ssrc | ((long) layerId << 32);
    }

    /**
     * Get the "nominal" height of a set of layers - if they all indicate the same spatial layer and same height.
     */
    private static int getNominalHeight(RtpLayerDesc[] layers)
    {
        if (layers.length == 0)
        {
            return RtpLayerDesc.NO_HEIGHT;
        }
        int firstHeight = layers[0].getHeight();
        boolean allSidZero = allMatch(layers, l -> l.getSid() == 0);
        boolean allSidMinusOne = allMatch(layers, l -> l.getSid() == -1);
        if (!(allSidZero || allSidMinusOne))
        {
            return RtpLayerDesc.NO_HEIGHT;
        }
        for (RtpLayerDesc layer : layers)
        {
            if (layer.getHeight() != firstHeight)
            {
                return RtpLayerDesc.NO_HEIGHT;
            }
        }
        return firstHeight;
    }
}
