/*
 * Copyright @ 2019-present 8x8, Inc
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
// This file uses WebRTC's naming style for enums

package org.jitsi.nlj.rtp.bandwidthestimation2;

/** Loss-based bandwidth estimation,
 * based on WebRTC modules/congestion_controller/goog_cc/loss_based_bwe_v2.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * (Deviation: upstream's LossBasedBweV2.kt declares this as an additional top-level
 * declaration in the same file; ported here as its own top-level file since Java allows
 * only one public top-level type per file and other files reference it unqualified.)
 */
public enum LossBasedState
{
    kIncreasing,

    // TODO(bugs.webrtc.org/12707): Remove one of the increasing states once we
    // have decided if padding is usefull for ramping up when BWE is loss
    // limited.
    kIncreaseUsingPadding,
    kDecreasing,
    kDelayBasedEstimate
}
