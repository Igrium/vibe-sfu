/*
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

package com.igrium.sfu;

/**
 * The state of the ICE connection of an {@link SfuPeerConnection}, reported via
 * {@link SfuPeerConnectionObserver#onIceConnectionStateChange(IceConnectionState)}.
 */
public enum IceConnectionState
{
    /** The connection has been created but connectivity establishment has not started. */
    NEW,
    /** Remote transport parameters have been received and connectivity checks are running. */
    CHECKING,
    /** ICE connectivity has been established. */
    CONNECTED,
    /** ICE failed to establish connectivity. */
    FAILED,
    /** The connection has been closed. */
    CLOSED
}
