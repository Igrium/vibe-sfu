/*
 * Copyright @ 2015 Atlassian Pty Ltd
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

package org.jitsi.nlj.rtp.bandwidthestimation2;

import org.jitsi.nlj.util.Bandwidth;

/**
 * Input to Rate Control.
 */
public class RateControlInput
{
    public BandwidthUsage bwState;
    public Bandwidth estimatedThroughput;

    public RateControlInput(BandwidthUsage bwState, Bandwidth estimatedThroughput)
    {
        this.bwState = bwState;
        this.estimatedThroughput = estimatedThroughput;
    }

    /**
     * Assigns the values of the fields of {@code source} to the respective
     * fields of this {@link RateControlInput}.
     *
     * @param source the {@link RateControlInput} the values of the fields of
     * which are to be assigned to the respective fields of this
     * {@link RateControlInput}
     */
    public void copy(RateControlInput source)
    {
        bwState = source.bwState;
        estimatedThroughput = source.estimatedThroughput;
    }
}
