/*
 * Copyright @ 2021 - present 8x8, Inc.
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
package org.jitsi.videobridge.cc.allocation;

import org.jitsi.nlj.RtpLayerDesc;

import java.util.AbstractList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable representation of the layers to be considered when allocating bandwidth for an endpoint. The order is
 * ascending by preference (and not necessarily bitrate).
 *
 * (Deviation: upstream implements {@code List<LayerSnapshot>} by delegation ({@code : List<LayerSnapshot> by
 * layers}). Java has no delegation syntax, so this extends {@link AbstractList} backed by the {@link #layers} list,
 * which gives the same read-only {@code List} behavior.)
 */
public class Layers extends AbstractList<Layers.LayerSnapshot>
{
    public static final Layers noLayers = new Layers(Collections.emptyList(), -1, -1);

    private final List<LayerSnapshot> layers;

    /** The index of the "preferred" layer, i.e. the layer up to which we allocate eagerly. */
    private final int preferredIndex;

    /**
     * The index of the layer which will be selected if oversending is enabled. If set to -1, oversending is
     * disabled.
     */
    private final int oversendIndex;

    public Layers(List<LayerSnapshot> layers, int preferredIndex, int oversendIndex)
    {
        this.layers = layers;
        this.preferredIndex = preferredIndex;
        this.oversendIndex = oversendIndex;
    }

    public int getPreferredIndex()
    {
        return preferredIndex;
    }

    public int getOversendIndex()
    {
        return oversendIndex;
    }

    public LayerSnapshot getPreferredLayer()
    {
        return (preferredIndex >= 0 && preferredIndex < layers.size()) ? layers.get(preferredIndex) : null;
    }

    public LayerSnapshot getOversendLayer()
    {
        return (oversendIndex >= 0 && oversendIndex < layers.size()) ? layers.get(oversendIndex) : null;
    }

    public LayerSnapshot getIdealLayer()
    {
        return layers.isEmpty() ? null : layers.get(layers.size() - 1);
    }

    @Override
    public LayerSnapshot get(int index)
    {
        return layers.get(index);
    }

    @Override
    public int size()
    {
        return layers.size();
    }

    /**
     * Saves the bitrate of a specific {@link RtpLayerDesc} at a specific point in time.
     *
     * (Deviation: upstream is a Kotlin {@code data class}; ported as a plain immutable holder.)
     */
    public static class LayerSnapshot
    {
        private final RtpLayerDesc layer;
        private final long bitrate;

        public LayerSnapshot(RtpLayerDesc layer, long bitrate)
        {
            this.layer = layer;
            this.bitrate = bitrate;
        }

        public RtpLayerDesc getLayer()
        {
            return layer;
        }

        public long getBitrate()
        {
            return bitrate;
        }
    }
}
