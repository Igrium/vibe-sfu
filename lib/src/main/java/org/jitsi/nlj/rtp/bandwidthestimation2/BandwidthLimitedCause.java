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

/* Reason that bandwidth estimate is limited. Bandwidth estimate can be limited
 * by either delay based bwe, or loss based bwe when it increases/decreases the
 * estimate.
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/probe_controller.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
public enum BandwidthLimitedCause
{
    kLossLimitedBweIncreasing,
    kLossLimitedBwe,
    kDelayBasedLimited,
    kDelayBasedLimitedDelayIncreased,
    kRttBasedBackOffHighRtt
}
