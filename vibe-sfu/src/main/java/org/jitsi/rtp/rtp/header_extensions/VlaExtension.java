/*
 * Copyright @ 2024-Present 8x8, Inc
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
package org.jitsi.rtp.rtp.header_extensions;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.util.BitReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A parser for the Video Layers Allocation RTP header extension.
 * https://webrtc.googlesource.com/src/+/refs/heads/main/docs/native-code/rtp-hdrext/video-layers-allocation00
 */
@SuppressFBWarnings(value = "SF_SWITCH_NO_DEFAULT", justification = "False positive")
public final class VlaExtension
{
    private VlaExtension()
    {
    }

    /** Kotlin's {@code typealias ParsedVla = List<Stream>}. */
    public static List<Stream> parse(RtpPacket.HeaderExtension ext)
    {
        boolean empty = ext.getDataLengthBytes() == 1 && ext.getBuffer()[ext.getDataOffset()] == (byte) 0;
        if (empty)
        {
            return Collections.emptyList();
        }

        BitReader reader = new BitReader(ext.getBuffer(), ext.getDataOffset(), ext.getDataLengthBytes());
        reader.skipBits(2); // RID
        int ns = reader.bits(2) + 1;
        int slBm = reader.bits(4);
        int[] slBms = new int[4];
        for (int i = 0; i < 4; i++)
        {
            slBms[i] = i < ns ? slBm : 0;
        }
        if (slBm == 0)
        {
            slBms[0] = reader.bits(4);
            if (ns > 1)
            {
                slBms[1] = reader.bits(4);
                if (ns > 2)
                {
                    slBms[2] = reader.bits(4);
                    if (ns > 3)
                    {
                        slBms[3] = reader.bits(4);
                    }
                }
            }
            if (ns == 1 || ns == 3)
            {
                reader.skipBits(4);
            }
        }

        int slCount = 0;
        for (int bm : slBms)
        {
            slCount += Integer.bitCount(bm);
        }
        int tlCountLenBytes = slCount / 4 + (slCount % 4 != 0 ? 1 : 0);

        BitReader tlCountReader = reader.clone(tlCountLenBytes);
        reader.skipBits(tlCountLenBytes * 8);

        List<Stream> streams = new ArrayList<>(ns);
        for (int streamIdx = 0; streamIdx < ns; streamIdx++)
        {
            List<SpatialLayer> spatialLayers = new ArrayList<>();
            Stream stream = new Stream(streamIdx, spatialLayers);
            streams.add(stream);

            for (int slIdx = 0; slIdx < 4; slIdx++)
            {
                if ((slBms[streamIdx] & (1 << slIdx)) != 0)
                {
                    int tlCount = tlCountReader.bits(2) + 1;
                    List<Long> targetBitrates = new ArrayList<>(tlCount);
                    for (int i = 0; i < tlCount; i++)
                    {
                        targetBitrates.add(reader.leb128());
                    }
                    spatialLayers.add(new SpatialLayer(slIdx, targetBitrates, null));
                }
            }
        }

        outer:
        for (int streamIdx = 0; streamIdx < ns; streamIdx++)
        {
            for (int slIdx = 0; slIdx < 4; slIdx++)
            {
                if ((slBms[streamIdx] & (1 << slIdx)) != 0)
                {
                    if (reader.remainingBits() < 40)
                    {
                        continue outer;
                    }
                    SpatialLayer sl = streams.get(streamIdx).getSpatialLayers().get(slIdx);
                    sl.setRes(new ResolutionAndFrameRate(
                        reader.bits(16) + 1,
                        reader.bits(16) + 1,
                        reader.bits(8)
                    ));
                }
            }
        }

        return streams;
    }

    public static final class ResolutionAndFrameRate
    {
        private final int width;
        private final int height;
        private final int maxFramerate;

        public ResolutionAndFrameRate(int width, int height, int maxFramerate)
        {
            this.width = width;
            this.height = height;
            this.maxFramerate = maxFramerate;
        }

        public int getWidth()
        {
            return width;
        }

        public int getHeight()
        {
            return height;
        }

        public int getMaxFramerate()
        {
            return maxFramerate;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
            {
                return true;
            }
            if (!(o instanceof ResolutionAndFrameRate))
            {
                return false;
            }
            ResolutionAndFrameRate that = (ResolutionAndFrameRate) o;
            return width == that.width && height == that.height && maxFramerate == that.maxFramerate;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(width, height, maxFramerate);
        }

        @Override
        public String toString()
        {
            return "ResolutionAndFrameRate(width=" + width + ", height=" + height +
                ", maxFramerate=" + maxFramerate + ")";
        }
    }

    public static final class SpatialLayer
    {
        private final int id;
        /** The target bitrates for each temporal layer in this spatial layer */
        private final List<Long> targetBitratesKbps;
        private ResolutionAndFrameRate res;

        public SpatialLayer(int id, List<Long> targetBitratesKbps, ResolutionAndFrameRate res)
        {
            this.id = id;
            this.targetBitratesKbps = targetBitratesKbps;
            this.res = res;
        }

        public int getId()
        {
            return id;
        }

        public List<Long> getTargetBitratesKbps()
        {
            return targetBitratesKbps;
        }

        public ResolutionAndFrameRate getRes()
        {
            return res;
        }

        public void setRes(ResolutionAndFrameRate res)
        {
            this.res = res;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
            {
                return true;
            }
            if (!(o instanceof SpatialLayer))
            {
                return false;
            }
            SpatialLayer that = (SpatialLayer) o;
            return id == that.id
                && Objects.equals(targetBitratesKbps, that.targetBitratesKbps)
                && Objects.equals(res, that.res);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(id, targetBitratesKbps, res);
        }

        @Override
        public String toString()
        {
            return "SpatialLayer(id=" + id + ", targetBitratesKbps=" + targetBitratesKbps +
                ", width=" + (res != null ? res.getWidth() : null) +
                ", height=" + (res != null ? res.getHeight() : null) +
                ", maxFramerate=" + (res != null ? res.getMaxFramerate() : null) + ")";
        }
    }

    public static final class Stream
    {
        private final int id;
        private final List<SpatialLayer> spatialLayers;

        public Stream(int id, List<SpatialLayer> spatialLayers)
        {
            this.id = id;
            this.spatialLayers = spatialLayers;
        }

        public int getId()
        {
            return id;
        }

        public List<SpatialLayer> getSpatialLayers()
        {
            return spatialLayers;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
            {
                return true;
            }
            if (!(o instanceof Stream))
            {
                return false;
            }
            Stream that = (Stream) o;
            return id == that.id && Objects.equals(spatialLayers, that.spatialLayers);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(id, spatialLayers);
        }

        @Override
        public String toString()
        {
            return "Stream(id=" + id + ", spatialLayers=" + spatialLayers + ")";
        }
    }
}
