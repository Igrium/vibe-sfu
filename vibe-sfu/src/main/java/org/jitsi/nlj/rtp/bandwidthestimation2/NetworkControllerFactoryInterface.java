/*
 * Copyright @ 2019 - present 8x8, Inc.
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

import java.time.Duration;

/** NetworkControllerFactoryInterface is an interface for creating a network
 * controller.
 *
 * Base type for network controller,
 * based on WebRTC api/transport/network_control.h in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
public interface NetworkControllerFactoryInterface
{
    /** Used to create a new network controller, requires an observer to be
     * provided to handle callbacks.
     */
    NetworkControllerInterface create(NetworkControllerConfig config);

    /** Returns the interval by which the network controller expects
     * OnProcessInterval calls.
     */
    Duration getProcessInterval();
}
