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

import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.util.NodeStatsBlockExtensions;

import java.util.Random;

/**
 * A {@link Node} which drops packets randomly with a certain probability and a uniform distribution.
 */
public class PacketLossNode extends FilterNode
{
    private final PacketLossConfig config;
    private final Random random = new Random(System.currentTimeMillis());
    private boolean inBurst = false;
    private int packetsSeen = 0;
    private int currentBurstPacketsDropped = 0;

    public PacketLossNode(PacketLossConfig config)
    {
        super("PacketLossNode(" + config + ")");
        this.config = config;
    }

    @Override
    protected boolean accept(PacketInfo packetInfo)
    {
        return acceptBurst() && random.nextDouble() >= config.getUniformRate();
    }

    private boolean acceptBurst()
    {
        if (!config.isBurstEnabled())
        {
            return true;
        }

        packetsSeen++;
        if (packetsSeen % config.getBurstInterval() == 0)
        {
            inBurst = true;
        }

        if (inBurst)
        {
            currentBurstPacketsDropped++;
            if (currentBurstPacketsDropped == config.getBurstSize())
            {
                inBurst = false;
                currentBurstPacketsDropped = 0;
            }
            return false;
        }
        else
        {
            return true;
        }
    }

    // (Deviation: upstream's trace() has an empty body here -- unlike other Node subclasses in this file set,
    // which call f.invoke()/f.run(). This is how PacketLossNode.kt is written upstream, not a porting artifact.)
    @Override
    public void trace(Runnable f)
    {
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock stats = super.getNodeStats();
        stats.addNumber("configured_uniform_rate", config.getUniformRate());
        stats.addNumber("configured_burst_size", config.getBurstSize());
        stats.addNumber("configured_burst_interval", config.getBurstInterval());
        NodeStatsBlockExtensions.addRatio(stats, "actual_drop_rate", "num_discarded_packets", "num_input_packets");
        return stats;
    }

    @Override
    protected NodeStatsBlock getNodeStatsToAggregate()
    {
        NodeStatsBlock stats = super.getNodeStatsToAggregate();
        NodeStatsBlockExtensions.addRatio(stats, "actual_drop_rate", "num_discarded_packets", "num_input_packets");
        return stats;
    }
}
