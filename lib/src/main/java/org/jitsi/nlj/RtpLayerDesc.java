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
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.BitrateTracker;
import org.jitsi.nlj.util.DataSize;

import java.time.Duration;

/**
 * Keeps track of its subjective quality index,
 * its last stable bitrate and other useful things for adaptivity/routing.
 *
 * @author George Politis
 */
public abstract class RtpLayerDesc
{
    /**
     * The index value that is used to represent that forwarding is suspended.
     */
    public static final int SUSPENDED_INDEX = -1;

    /**
     * The encoding ID value that is used to represent that forwarding is suspended.
     */
    public static final int SUSPENDED_ENCODING_ID = -1;

    /**
     * A value used to designate the absence of height information.
     */
    public static final int NO_HEIGHT = -1;

    /**
     * A value used to designate the absence of frame rate information.
     */
    public static final double NO_FRAME_RATE = -1.0;

    /**
     * TODO(port): upstream sizes the default {@link BitrateTracker} window/bucket via
     * {@code org.jitsi.nlj.transform.node.incoming.BitrateCalculator}, which in turn depends on the (not yet
     * ported) BWE engine config to pick a window/bucket size (GoogleCc: 5s/100ms, GoogleCc2: 1s/20ms; the upstream
     * default engine is GoogleCc2). Until {@code BitrateCalculator} is ported, hardcode the GoogleCc2 defaults from
     * {@code reference.conf} ({@code jmt.bwe.estimator.GoogleCc2.default-window-size = 1 seconds},
     * {@code jmt.bwe.estimator.GoogleCc2.default-bucket-size = 20 ms}) here instead.
     */
    private static final Duration DEFAULT_BITRATE_TRACKER_WINDOW_SIZE = Duration.ofSeconds(1);
    private static final Duration DEFAULT_BITRATE_TRACKER_BUCKET_SIZE = Duration.ofMillis(20);

    /**
     * The index of this instance's encoding in the source encoding array.
     */
    private final int eid;

    /**
     * The temporal layer ID of this instance.
     */
    private final int tid;

    /**
     * The spatial layer ID of this instance.
     */
    private final int sid;

    /**
     * The max "height" of the bitstream that this instance represents. The actual
     * height may be less due to bad network or system load.  {@link #NO_HEIGHT} for unknown.
     *
     * In order to handle portrait-mode video correctly, this should actually be the smaller of
     * the bitstream's height or width.
     *
     * Where possible we sniff the actual height from the RTP packets.
     */
    private int height;

    /**
     * The max frame rate (in fps) of the bitstream that this instance
     * represents. The actual frame rate may be less due to bad network or
     * system load.  {@link #NO_FRAME_RATE} for unknown.
     */
    private double frameRate;

    protected RtpLayerDesc(int eid, int tid, int sid, int height, double frameRate)
    {
        this.eid = eid;
        this.tid = tid;
        this.sid = sid;
        this.height = height;
        this.frameRate = frameRate;
    }

    public abstract RtpLayerDesc copy(int height, int tid, boolean inherit);

    public RtpLayerDesc copy()
    {
        return copy(this.height, this.tid, true);
    }

    public int getEid()
    {
        return eid;
    }

    public int getTid()
    {
        return tid;
    }

    public int getSid()
    {
        return sid;
    }

    public int getHeight()
    {
        return height;
    }

    public void setHeight(int height)
    {
        this.height = height;
    }

    public double getFrameRate()
    {
        return frameRate;
    }

    public void setFrameRate(double frameRate)
    {
        this.frameRate = frameRate;
    }

    /**
     * The {@link BitrateTracker} instance used to calculate the receiving bitrate of this RTP layer.
     */
    protected BitrateTracker bitrateTracker =
        new BitrateTracker(DEFAULT_BITRATE_TRACKER_WINDOW_SIZE, DEFAULT_BITRATE_TRACKER_BUCKET_SIZE);

    private Bandwidth targetBitrate;

    public Bandwidth getTargetBitrate()
    {
        return targetBitrate;
    }

    public void setTargetBitrate(Bandwidth targetBitrate)
    {
        this.targetBitrate = targetBitrate;
    }

    /**
     * @return the "id" of this layer within this encoding. This is a server-side id and should
     * not be confused with any encoding id defined in the client (such as the
     * rid).
     */
    public abstract int getLayerId();

    /**
     * A local index of this track.
     */
    public abstract int getIndex();

    /**
     * Inherit a {@link BitrateTracker} object
     */
    void inheritStatistics(BitrateTracker tracker)
    {
        bitrateTracker = tracker;
    }

    /**
     * Inherit another layer description's {@link BitrateTracker} object.
     */
    protected void inheritFrom(RtpLayerDesc other)
    {
        inheritStatistics(other.bitrateTracker);
        targetBitrate = other.targetBitrate;
    }

    /**
     * @param packetSize
     * @param nowMs
     * @return true if this caused the rate being tracked to transition from zero to nonzero
     */
    public boolean updateBitrate(DataSize packetSize, long nowMs)
    {
        boolean wasInactive = hasZeroBitrate(nowMs);
        // Update rate stats (this should run after padding termination).
        bitrateTracker.update(packetSize, nowMs);
        return wasInactive && packetSize.getBits() > 0L;
    }

    /**
     * Gets the cumulative bitrate (in bps) of this {@link RtpLayerDesc} and its dependencies.
     *
     * @param nowMs
     * @return the cumulative bitrate (in bps) of this {@link RtpLayerDesc} and its dependencies.
     */
    public abstract Bandwidth getBitrate(long nowMs);

    /**
     * Recursively checks this layer and its dependencies to see if the bitrate is zero.
     * Note that unlike calcBitrate this does not avoid double-visiting layers; the overhead
     * of the hash table is usually more than the cost of any double-visits.
     */
    public abstract boolean hasZeroBitrate(long nowMs);

    public ObjectNode debugState()
    {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("frameRate", frameRate);
        node.put("height", height);
        node.put("index", getIndex());
        node.put("bitrate_bps", getBitrate(System.currentTimeMillis()).getBps());
        node.put("target_bitrate", targetBitrate != null ? targetBitrate.getBps() : 0);
        node.put("indexString", indexString());
        return node;
    }

    public abstract String indexString();

    /**
     * Calculate the "index" of a layer based on its encoding, spatial, and temporal ID.
     * This is a server-side id and should not be confused with any encoding id defined
     * in the client (such as the rid) or the encodingId.  This is used by the videobridge's
     * adaptive source projection for filtering.
     */
    public static int getIndex(int eid, int sid, int tid)
    {
        int e = eid < 0 ? 0 : eid;
        int s = sid < 0 ? 0 : sid;
        int t = tid < 0 ? 0 : tid;

        return (e << 6) | (s << 3) | t;
    }

    /**
     * Get an encoding ID from a layer index.  If the index is {@link #SUSPENDED_INDEX},
     * {@link #SUSPENDED_ENCODING_ID} will be returned.
     */
    public static int getEidFromIndex(int index)
    {
        return index >> 6;
    }

    /**
     * Get a spatial ID from a layer index.  If the index is {@link #SUSPENDED_INDEX},
     * the value is unspecified.
     */
    public static int getSidFromIndex(int index)
    {
        return (index & 0x38) >> 3;
    }

    /**
     * Get a temporal ID from a layer index.  If the index is {@link #SUSPENDED_INDEX},
     * the value is unspecified.
     */
    public static int getTidFromIndex(int index)
    {
        return index & 0x7;
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
        return "E" + getEidFromIndex(index) + "S" + getSidFromIndex(index) + "T" + getTidFromIndex(index);
    }
}
