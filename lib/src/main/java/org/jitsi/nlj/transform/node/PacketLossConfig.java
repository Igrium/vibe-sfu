/*
 * Copyright @ 2020 - Present, 8x8 Inc
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
package org.jitsi.nlj.transform.node;

/**
 * Replaces the upstream {@code jitsi-metaconfig}-based config. Since this port does not use
 * {@code jitsi-metaconfig}/HOCON, this hardcodes the upstream defaults for both possible {@code base} paths
 * (see {@code reference.conf}'s {@code jmt.debug.packet-loss.incoming}/{@code .outgoing} sections, both of which
 * default to all-zero / disabled).
 */
public class PacketLossConfig
{
    private final String base;
    private final double uniformRate;
    private final int burstSize;
    private final int burstInterval;

    public PacketLossConfig(String base)
    {
        this.base = base;
        this.uniformRate = 0.0;
        this.burstSize = 0;
        this.burstInterval = 0;
    }

    public String getBase()
    {
        return base;
    }

    public double getUniformRate()
    {
        return uniformRate;
    }

    public int getBurstSize()
    {
        return burstSize;
    }

    public int getBurstInterval()
    {
        return burstInterval;
    }

    public boolean isBurstEnabled()
    {
        return burstSize > 0 && burstInterval > 0;
    }

    public boolean isEnabled()
    {
        return isBurstEnabled() || uniformRate > 0;
    }

    @Override
    public String toString()
    {
        return "uniform rate " + (uniformRate * 100) + "%, burst size " + burstSize + ", burst interval " + burstInterval;
    }
}
