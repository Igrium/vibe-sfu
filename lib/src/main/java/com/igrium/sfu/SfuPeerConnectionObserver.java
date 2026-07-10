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
 * Receives lifecycle and data events from an {@link SfuPeerConnection}.
 *
 * <p>All methods have empty default implementations, so hosts implement only what
 * they need. Callbacks are invoked on internal library threads; implementations
 * must not block for long and must hand heavy work off to their own executors.
 *
 * <p>Media-related callbacks (remote tracks, decrypted RTP, bandwidth estimation)
 * will be added when the media pipeline lands; this interface currently covers the
 * transport + data-channel slice.
 */
public interface SfuPeerConnectionObserver
{
    /**
     * The ICE connection state changed.
     */
    default void onIceConnectionStateChange(IceConnectionState state)
    {
    }

    /**
     * The connection is fully established: ICE is connected <em>and</em> the DTLS
     * handshake has completed. Data channels can be created/used from this point.
     */
    default void onConnected()
    {
    }

    /**
     * The transport has been lost (ICE failure).
     */
    default void onDisconnected()
    {
    }

    /**
     * The remote peer opened a data channel. The channel is already open and
     * usable when this fires.
     */
    default void onDataChannel(DataChannelTrack track)
    {
    }

    /**
     * A locally-created data channel finished opening (the remote side
     * acknowledged it).
     */
    default void onDataChannelOpen(DataChannelTrack track)
    {
    }

    /**
     * A string message arrived on a data channel.
     */
    default void onDataChannelStringMessage(DataChannelTrack track, String message)
    {
    }

    /**
     * A binary message arrived on a data channel.
     */
    default void onDataChannelBinaryMessage(DataChannelTrack track, byte[] message)
    {
    }

    /**
     * The connection has been fully torn down (after {@link SfuPeerConnection#close()}).
     */
    default void onClosed()
    {
    }

    /**
     * An unrecoverable error occurred in the pipeline.
     */
    default void onError(Throwable t)
    {
    }
}
