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

/** NetworkControllerInterface is implemented by network controllers. A network
 * controller is a class that uses information about network state and traffic
 * to estimate network parameters such as round trip time and bandwidth. Network
 * controllers does not guarantee thread safety, the interface must be used in a
 * non-concurrent fashion.
 *
 * Base type for network controller,
 * based on WebRTC api/transport/network_control.h in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
public interface NetworkControllerInterface
{
    /** Called when network availabilty changes */
    NetworkControlUpdate onNetworkAvailability(NetworkAvailability msg);

    /** Called when the receiving or sending endpoint changes address.*/
    NetworkControlUpdate onNetworkRouteChange(NetworkRouteChange msg);

    /** Called periodically with a periodicy as specified by
     * {@link NetworkControllerFactoryInterface#getProcessInterval()}.
     */
    NetworkControlUpdate onProcessInterval(ProcessInterval msg);

    /** Called when remotely calculated bitrate is received. */
    NetworkControlUpdate onRemoteBitrateReport(RemoteBitrateReport msg);

    /** Called round trip time has been calculated by protocol specific mechanisms. */
    NetworkControlUpdate onRoundTripTimeUpdate(RoundTripTimeUpdate msg);

    /** Called when a packet is sent on the network. */
    NetworkControlUpdate onSentPacket(SentPacket sentPacket);

    // Omitted: onReceivedPacket: Not used

    /** Called when the stream specific configuration has been updated. */
    NetworkControlUpdate onStreamsConfig(StreamsConfig msg);

    /** Called when target transfer rate constraints has been changed. */
    NetworkControlUpdate onTargetRateConstraints(TargetRateConstraints constraints);

    /** Called when a protocol specific calculation of packet loss has been made. */
    NetworkControlUpdate onTransportLossReport(TransportLossReport msg);

    /** Called with per packet feedback regarding receive time. */
    NetworkControlUpdate onTransportPacketsFeedback(TransportPacketsFeedback report);

    // Omitted: onNetworkStateUpdate: Not used
}
