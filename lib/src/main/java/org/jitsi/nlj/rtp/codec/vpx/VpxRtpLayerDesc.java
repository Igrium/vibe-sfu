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

package org.jitsi.nlj.rtp.codec.vpx;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.util.Bandwidth;

import java.util.HashMap;
import java.util.Map;

/**
 * An RtpLayerDesc of the type needed to describe VP8 and VP9 scalability.
 */
public class VpxRtpLayerDesc extends RtpLayerDesc
{
    private static final VpxRtpLayerDesc[] EMPTY_LAYERS = new VpxRtpLayerDesc[0];

    /**
     * The {@link RtpLayerDesc}s on which this layer definitely depends.
     */
    private final VpxRtpLayerDesc[] dependencyLayers;

    /**
     * The {@link RtpLayerDesc}s on which this layer possibly depends.
     * (The intended use case is K-SVC mode.)
     */
    private final VpxRtpLayerDesc[] softDependencyLayers;

    /**
     * @return the "id" of this layer within this encoding. This is a server-side id and should
     * not be confused with any encoding id defined in the client (such as the
     * rid).
     */
    private final int layerId;

    /**
     * A local index of this track.
     */
    private final int index;

    /**
     * Whether softDependencyLayers are to be used.
     */
    private boolean useSoftDependencies = true;

    /**
     * @param eid the index of this instance's encoding in the source encoding array.
     * @param tid the temporal layer ID of this instance, or negative for unknown.
     * @param sid the spatial layer ID of this instance, or negative for unknown.
     * @param height the max height of the bitstream that this instance represents. The actual
     * height may be less due to bad network or system load.  {@link RtpLayerDesc#NO_HEIGHT} for unknown.
     * XXX we should be able to sniff the actual height from the RTP packets.
     * @param frameRate the max frame rate (in fps) of the bitstream that this instance
     * represents. The actual frame rate may be less due to bad network or
     * system load.  {@link RtpLayerDesc#NO_FRAME_RATE} for unknown.
     * @param dependencyLayers the {@link RtpLayerDesc}s on which this layer definitely depends.
     * @param softDependencyLayers the {@link RtpLayerDesc}s on which this layer possibly depends
     * (the intended use case is K-SVC mode).
     */
    public VpxRtpLayerDesc(
        int eid,
        int tid,
        int sid,
        int height,
        double frameRate,
        VpxRtpLayerDesc[] dependencyLayers,
        VpxRtpLayerDesc[] softDependencyLayers
    )
    {
        super(eid, tid, sid, height, frameRate);
        if (!(tid < 8))
        {
            throw new IllegalArgumentException("Invalid temporal ID " + tid);
        }
        if (!(sid < 8))
        {
            throw new IllegalArgumentException("Invalid spatial ID " + sid);
        }
        this.dependencyLayers = dependencyLayers;
        this.softDependencyLayers = softDependencyLayers;
        this.layerId = getIndex(0, sid, tid);
        this.index = getIndex(eid, sid, tid);
    }

    public VpxRtpLayerDesc(int eid, int tid, int sid, int height, double frameRate)
    {
        this(eid, tid, sid, height, frameRate, EMPTY_LAYERS, EMPTY_LAYERS);
    }

    public VpxRtpLayerDesc(
        int eid, int tid, int sid, int height, double frameRate, VpxRtpLayerDesc[] dependencyLayers)
    {
        this(eid, tid, sid, height, frameRate, dependencyLayers, EMPTY_LAYERS);
    }

    public VpxRtpLayerDesc[] getDependencyLayers()
    {
        return dependencyLayers;
    }

    public VpxRtpLayerDesc[] getSoftDependencyLayers()
    {
        return softDependencyLayers;
    }

    /**
     * Clone an existing layer desc, inheriting its statistics if {@code inherit},
     * modifying only specific values.
     */
    @Override
    public VpxRtpLayerDesc copy(int height, int tid, boolean inherit)
    {
        VpxRtpLayerDesc copy = new VpxRtpLayerDesc(
            this.getEid(),
            tid,
            this.getSid(),
            height,
            this.getFrameRate(),
            this.dependencyLayers,
            this.softDependencyLayers
        );
        if (inherit)
        {
            copy.inheritFrom(this);
        }
        return copy;
    }

    public boolean isUseSoftDependencies()
    {
        return useSoftDependencies;
    }

    public void setUseSoftDependencies(boolean useSoftDependencies)
    {
        this.useSoftDependencies = useSoftDependencies;
    }

    @Override
    public int getLayerId()
    {
        return layerId;
    }

    @Override
    public int getIndex()
    {
        return index;
    }

    /**
     * Inherit another layer description's {@link org.jitsi.nlj.util.BitrateTracker} object.
     */
    @Override
    protected void inheritFrom(RtpLayerDesc other)
    {
        super.inheritFrom(other);
        if (other instanceof VpxRtpLayerDesc)
        {
            useSoftDependencies = ((VpxRtpLayerDesc) other).useSoftDependencies;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return "subjective_quality=" + index + ",temporal_id=" + getTid() + ",spatial_id=" + getSid() +
            ",height=" + getHeight() + ",frameRate=" + getFrameRate();
    }

    /**
     * Gets the cumulative bitrate (in bps) of this {@link RtpLayerDesc} and its dependencies.
     *
     * This is left open for use in testing.
     *
     * @param nowMs
     * @return the cumulative bitrate (in bps) of this {@link RtpLayerDesc} and its dependencies.
     */
    @Override
    public Bandwidth getBitrate(long nowMs)
    {
        return Bandwidth.sum(calcBitrate(nowMs, new HashMap<>()).values());
    }

    /**
     * Recursively adds the bitrate (in bps) of this {@link RtpLayerDesc} and
     * its dependencies in the map passed in as an argument.
     *
     * This is necessary to ensure we don't double-count layers in cases
     * of multiple dependencies.
     *
     * @param nowMs
     */
    private Map<Integer, Bandwidth> calcBitrate(long nowMs, Map<Integer, Bandwidth> rates)
    {
        if (rates.containsKey(index))
        {
            return rates;
        }
        rates.put(index, bitrateTracker.getRate(nowMs));

        for (VpxRtpLayerDesc dep : dependencyLayers)
        {
            dep.calcBitrate(nowMs, rates);
        }

        if (useSoftDependencies)
        {
            for (VpxRtpLayerDesc dep : softDependencyLayers)
            {
                dep.calcBitrate(nowMs, rates);
            }
        }

        return rates;
    }

    /**
     * Returns true if this layer, alone, has a zero bitrate.
     */
    private boolean layerHasZeroBitrate(long nowMs)
    {
        return bitrateTracker.getAccumulatedSize(nowMs).getBits() == 0L;
    }

    /**
     * Recursively checks this layer and its dependencies to see if the bitrate is zero.
     * Note that unlike {@link #calcBitrate} this does not avoid double-visiting layers; the overhead
     * of the hash table is usually more than the cost of any double-visits.
     *
     * This is left open for use in testing.
     */
    @Override
    public boolean hasZeroBitrate(long nowMs)
    {
        if (!layerHasZeroBitrate(nowMs))
        {
            return false;
        }
        for (VpxRtpLayerDesc dep : dependencyLayers)
        {
            if (!dep.layerHasZeroBitrate(nowMs))
            {
                return false;
            }
        }
        if (useSoftDependencies)
        {
            for (VpxRtpLayerDesc dep : softDependencyLayers)
            {
                if (!dep.layerHasZeroBitrate(nowMs))
                {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Extracts debug state from an {@link RtpLayerDesc}.
     */
    @Override
    public ObjectNode debugState()
    {
        ObjectNode node = super.debugState();
        node.put("tid", getTid());
        node.put("sid", getSid());
        return node;
    }

    @Override
    public String indexString()
    {
        return indexString(index);
    }
}
