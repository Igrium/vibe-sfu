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

package org.jitsi.videobridge.util;

import java.time.Clock;
import java.time.Duration;

public class BooleanStateTimeTracker
{
    private final Clock clock;

    private Duration totalTimeOn = Duration.ofMillis(0);
    private Duration totalTimeOff = Duration.ofMillis(0);
    private boolean state;
    private java.time.Instant mostRecentStateChange;
    private final Object lock = new Object();

    public BooleanStateTimeTracker()
    {
        this(false, Clock.systemUTC());
    }

    public BooleanStateTimeTracker(boolean initialState, Clock clock)
    {
        this.state = initialState;
        this.clock = clock;
        this.mostRecentStateChange = clock.instant();
    }

    public boolean getState()
    {
        return state;
    }

    public Duration totalTimeOn()
    {
        synchronized (lock)
        {
            if (state)
            {
                return totalTimeOn.plus(Duration.between(mostRecentStateChange, clock.instant()));
            }
            return totalTimeOn;
        }
    }

    public Duration totalTimeOff()
    {
        synchronized (lock)
        {
            if (!state)
            {
                return totalTimeOff.plus(Duration.between(mostRecentStateChange, clock.instant()));
            }
            return totalTimeOff;
        }
    }

    public void setState(boolean state)
    {
        if (state)
        {
            on();
        }
        else
        {
            off();
        }
    }

    public void on()
    {
        synchronized (lock)
        {
            if (!state)
            {
                state = true;
                totalTimeOff = totalTimeOff.plus(Duration.between(mostRecentStateChange, clock.instant()));
                mostRecentStateChange = clock.instant();
            }
        }
    }

    public void off()
    {
        synchronized (lock)
        {
            if (state)
            {
                state = false;
                totalTimeOn = totalTimeOn.plus(Duration.between(mostRecentStateChange, clock.instant()));
                mostRecentStateChange = clock.instant();
            }
        }
    }
}
