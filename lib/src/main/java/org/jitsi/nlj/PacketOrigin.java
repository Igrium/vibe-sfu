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

/**
 * The origin of a packet in the system; used to track outgoing packets in {@code OutgoingStatisticsTracker}
 * to measure the bitrates of each type of data.
 *
 * Currently only used for RTP, so RTCP, SCTP, and Datachannel will all be either Routed or Misc.
 */
public enum PacketOrigin
{
    Routed,
    Retransmission,
    Probing,
    Padding,
    Synthesized,
    Misc
    /* TODO: Add RTCP, SCTP, and datachannel if needed */
}
