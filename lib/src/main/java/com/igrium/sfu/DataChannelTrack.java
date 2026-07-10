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

import org.jitsi.videobridge.datachannel.DataChannel;

/**
 * A WebRTC data channel belonging to an {@link SfuPeerConnection}.
 *
 * <p>Channel lifecycle and inbound messages are reported through the connection's
 * {@link SfuPeerConnectionObserver} ({@code onDataChannelOpen}, {@code onDataChannelMessage}).
 * Outbound messages are sent with {@link #sendString(String)} and {@link #sendBinary(byte[])}.
 */
public final class DataChannelTrack
{
    private final SfuPeerConnection connection;
    private final String label;
    private final DataChannelOptions options;

    /** The underlying stack channel; assigned once the channel is (or can be) opened. */
    private volatile DataChannel dataChannel;

    DataChannelTrack(SfuPeerConnection connection, String label, DataChannelOptions options)
    {
        this.connection = connection;
        this.label = label;
        this.options = options;
    }

    DataChannelTrack(SfuPeerConnection connection, DataChannel dataChannel)
    {
        this.connection = connection;
        this.label = dataChannel.getLabel();
        this.options = null;
        this.dataChannel = dataChannel;
    }

    /** The label this channel was created with. */
    public String getLabel()
    {
        return label;
    }

    /**
     * The SCTP stream id of this channel, or -1 if the channel has not been
     * assigned to a stream yet (SCTP association not yet established).
     */
    public int getSid()
    {
        DataChannel dc = dataChannel;
        return dc != null ? dc.getSid() : -1;
    }

    /**
     * Whether this channel is open: for locally-created channels this means the remote
     * side has acknowledged the channel; remotely-created channels are open on arrival.
     */
    public boolean isOpen()
    {
        DataChannel dc = dataChannel;
        return dc != null && dc.isReady();
    }

    /** The peer connection this channel belongs to. */
    public SfuPeerConnection getConnection()
    {
        return connection;
    }

    /**
     * Sends a string (UTF-8) message on this channel.
     * @throws IllegalStateException if the channel is not open yet.
     */
    public void sendString(String message)
    {
        requireChannel().sendString(message);
    }

    /**
     * Sends a binary message on this channel.
     * @throws IllegalStateException if the channel is not open yet.
     */
    public void sendBinary(byte[] data)
    {
        requireChannel().sendBinary(data);
    }

    DataChannelOptions options()
    {
        return options;
    }

    DataChannel channel()
    {
        return dataChannel;
    }

    void setChannel(DataChannel dataChannel)
    {
        this.dataChannel = dataChannel;
    }

    private DataChannel requireChannel()
    {
        DataChannel dc = dataChannel;
        if (dc == null)
        {
            throw new IllegalStateException("Data channel '" + label + "' is not open yet");
        }
        return dc;
    }
}
