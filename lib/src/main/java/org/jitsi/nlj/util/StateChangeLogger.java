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

package org.jitsi.nlj.util;

import org.jitsi.utils.logging2.Logger;

import java.util.function.Supplier;

/**
 * Class that logs when state first becomes false, or changes from false to true,
 * without logging on every packet.
 */
public class StateChangeLogger
{
    private final String desc;
    private final Logger logger;

    private Boolean state;

    public StateChangeLogger(String desc, Logger logger)
    {
        this.desc = desc;
        this.logger = logger;
    }

    public void setState(boolean newState, Object instance, Supplier<String> instanceDesc)
    {
        if (state == null || state != newState)
        {
            if (!newState)
            {
                logger.info(() -> "Packet " + instance + " has " + desc + ".  " + instanceDesc.get());
            }
            else if (state != null)
            {
                logger.info(() -> "Packet " + instance + " source no longer has " + desc + ".  " + instanceDesc.get());
            }
            state = newState;
        }
    }
}
