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
package org.jitsi.videobridge.ice;

import org.ice4j.ice.harvest.SinglePortUdpHarvester;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;

import java.util.List;

public class Harvesters
{
    private static final Logger logger = new LoggerImpl(Harvesters.class.getName());

    private static volatile Harvesters instance;
    private static final Object instanceLock = new Object();

    public final List<SinglePortUdpHarvester> singlePortHarvesters;

    private Harvesters(List<SinglePortUdpHarvester> singlePortHarvesters)
    {
        this.singlePortHarvesters = singlePortHarvesters;
    }

    /* We're unhealthy if there are no single port harvesters. */
    public boolean isHealthy()
    {
        return !singlePortHarvesters.isEmpty();
    }

    private void closeInternal()
    {
        for (SinglePortUdpHarvester h : singlePortHarvesters)
        {
            h.close();
        }
    }

    public static void init()
    {
        // Trigger the lazy init.
        getInstance();
    }

    public static void close()
    {
        getInstance().closeInternal();
    }

    public static Harvesters getInstance()
    {
        Harvesters result = instance;
        if (result == null)
        {
            synchronized (instanceLock)
            {
                result = instance;
                if (result == null)
                {
                    List<SinglePortUdpHarvester> singlePortHarvesters =
                        SinglePortUdpHarvester.createHarvesters(IceConfig.config.port);
                    if (singlePortHarvesters.isEmpty())
                    {
                        logger.warn("No single-port harvesters created.");
                    }
                    result = new Harvesters(singlePortHarvesters);
                    instance = result;
                }
            }
        }
        return result;
    }
}
