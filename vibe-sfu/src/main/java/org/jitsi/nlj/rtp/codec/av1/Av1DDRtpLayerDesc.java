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
package org.jitsi.nlj.rtp.codec.av1;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.util.Bandwidth;

/**
 * An RtpLayerDesc of the type needed to describe AV1 DD scalability.
 */
public class Av1DDRtpLayerDesc extends RtpLayerDesc
{
    /**
     * The index value that is used to represent that forwarding is suspended.
     */
    public static final int SUSPENDED_INDEX = -1;

    public static final int SUSPENDED_DT = -1;

    /**
     * The decoding target of this instance, or negative for unknown.
     */
    private final int dt;

    private final int layerId;
    private final int index;

    /**
     * @param eid the index of this instance's encoding in the source encoding array.
     * @param dt the decoding target of this instance, or negative for unknown.
     * @param tid the temporal layer ID of this instance.
     * @param sid the spatial layer ID of this instance.
     * @param height the max height of the bitstream that this instance represents. The actual
     * height may be less due to bad network or system load.
     * @param frameRate the max frame rate (in fps) of the bitstream that this instance
     * represents. The actual frame rate may be less due to bad network or system load.
     */
    public Av1DDRtpLayerDesc(int eid, int dt, int tid, int sid, int height, double frameRate)
    {
        super(eid, tid, sid, height, frameRate);
        this.dt = dt;
        this.layerId = dt;
        this.index = getIndex(eid, dt);
    }

    public int getDt()
    {
        return dt;
    }

    @Override
    public RtpLayerDesc copy(int height, int tid, boolean inherit)
    {
        Av1DDRtpLayerDesc copy = new Av1DDRtpLayerDesc(getEid(), dt, tid, getSid(), height, getFrameRate());
        if (inherit)
        {
            copy.inheritFrom(this);
        }
        return copy;
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

    @Override
    public Bandwidth getBitrate(long nowMs)
    {
        return bitrateTracker.getRate(nowMs);
    }

    @Override
    public boolean hasZeroBitrate(long nowMs)
    {
        return bitrateTracker.getAccumulatedSize(nowMs).getBits() == 0L;
    }

    /**
     * Extracts debug state from an {@link RtpLayerDesc}.
     */
    @Override
    public ObjectNode debugState()
    {
        ObjectNode node = super.debugState();
        node.put("dt", dt);
        return node;
    }

    @Override
    public String indexString()
    {
        return indexString(index);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return "subjective_quality=" + index + ",DT=" + dt + ",height=" + getHeight() +
            ",frameRate=" + getFrameRate();
    }

    /**
     * Calculate the "index" of a layer based on its encoding and decode target.
     * This is a server-side id and should not be confused with any encoding id defined
     * in the client (such as the rid) or the encodingId.  This is used by the videobridge's
     * adaptive source projection for filtering.
     */
    public static int getIndex(int eid, int dt)
    {
        int e = eid < 0 ? 0 : eid;
        int d = dt < 0 ? 0 : dt;

        return (e << 6) | d;
    }

    /**
     * Get a decode target ID from a layer index.  If the index is {@link #SUSPENDED_INDEX},
     * the value is unspecified.
     */
    public static int getDtFromIndex(int index)
    {
        return index == SUSPENDED_INDEX ? SUSPENDED_DT : index & 0x3f;
    }

    /**
     * Get a string description of a layer index.
     */
    public static String indexString(int index)
    {
        if (index == SUSPENDED_INDEX)
        {
            return "SUSP";
        }
        return "E" + getEidFromIndex(index) + "DT" + getDtFromIndex(index);
    }
}
