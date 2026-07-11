/*
 * Copyright @ 2018 - Present, 8x8 Inc
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

import org.jitsi.nlj.util.Bandwidth;

/**
 * (Deviation: upstream declares both {@code RtpReceiver} and {@code RtpReceiverEventHandler} in a single
 * {@code RtpReceiver.kt} file; Java requires one public top-level class per file, so this is split out.)
 */
public interface RtpReceiverEventHandler
{
    /**
     * We received an audio level indication from the remote endpoint.
     */
    default boolean audioLevelReceived(long sourceSsrc, long level)
    {
        return false;
    }

    /**
     * The estimation of the available send bandwidth changed.
     */
    default void bandwidthEstimationChanged(Bandwidth newValue)
    {
    }
}
