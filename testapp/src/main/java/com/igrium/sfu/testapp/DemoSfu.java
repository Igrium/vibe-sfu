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

package com.igrium.sfu.testapp;

import com.igrium.sfu.DataChannelTrack;
import com.igrium.sfu.IceConnectionState;
import com.igrium.sfu.SfuPeerConnection;
import com.igrium.sfu.SfuPeerConnectionObserver;
import com.igrium.sfu.sdp.SdpUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jitsi.videobridge.transport.TransportDescription;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal demo SFU built on the jitsi-sfu library.
 *
 * <p>Serves a browser test client at {@code http://localhost:8080/} and accepts
 * WebRTC (data-channel-only) offers at {@code POST /offer}. Every string/binary
 * message received on any peer's data channel is relayed to all other connected
 * peers — the smallest possible "selective forwarding" demo.
 */
public final class DemoSfu
{
    private static final int HTTP_PORT = Integer.getInteger("demo.http.port", 8080);

    /** Connected peers by id. */
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final AtomicInteger nextPeerId = new AtomicInteger(1);

    private static final class Peer
    {
        final String id;
        final SfuPeerConnection connection;
        volatile DataChannelTrack track;

        Peer(String id, SfuPeerConnection connection)
        {
            this.id = id;
            this.connection = connection;
        }
    }

    public static void main(String[] args) throws Exception
    {
        new DemoSfu().start();
    }

    private void start() throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::handleIndex);
        server.createContext("/offer", this::handleOffer);
        server.createContext("/debug", this::handleDebug);
        server.start();
        System.out.println("Demo SFU listening on http://localhost:" + HTTP_PORT + "/");
    }

    private void handleIndex(HttpExchange exchange) throws IOException
    {
        if (!"GET".equals(exchange.getRequestMethod()))
        {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        byte[] body;
        try (InputStream in = DemoSfu.class.getResourceAsStream("/web/index.html"))
        {
            body = in.readAllBytes();
        }
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(body);
        }
    }

    /** Dumps the debug state of all connected peers as JSON. */
    private void handleDebug(HttpExchange exchange) throws IOException
    {
        com.fasterxml.jackson.databind.node.ObjectNode root =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        for (Peer peer : peers.values())
        {
            root.set(peer.id, peer.connection.getDebugState());
        }
        byte[] body = root.toPrettyString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(body);
        }
    }

    /**
     * Accepts a browser offer (SDP as the request body) and responds with our SDP
     * answer.
     */
    private void handleOffer(HttpExchange exchange) throws IOException
    {
        if (!"POST".equals(exchange.getRequestMethod()))
        {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        try
        {
            String offerSdp = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String answerSdp = connectPeer(offerSdp);
            byte[] body = answerSdp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/sdp");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody())
            {
                out.write(body);
            }
        }
        catch (Exception e)
        {
            System.err.println("Failed to handle offer: " + e);
            e.printStackTrace();
            byte[] body = String.valueOf(e).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody())
            {
                out.write(body);
            }
        }
    }

    /**
     * Creates a new peer connection (as the answerer) for a browser offer and
     * returns the SDP answer.
     */
    private String connectPeer(String offerSdp)
    {
        String peerId = "peer-" + nextPeerId.getAndIncrement();
        SdpUtils.ParsedSdp offer = SdpUtils.parse(offerSdp);

        SfuPeerConnection connection = new SfuPeerConnection(
            peerId,
            SfuPeerConnection.Role.ANSWERER,
            new Observer(peerId),
            new org.jitsi.utils.logging2.LoggerImpl(DemoSfu.class.getName()));
        Peer peer = new Peer(peerId, connection);
        peers.put(peerId, peer);

        connection.setRemoteDescription(offer.transport);
        TransportDescription local = connection.getLocalDescription();
        String answer = SdpUtils.buildAnswer(local, offer);
        System.out.println("[" + peerId + "] answering offer (" + offer.transport.candidates.size()
            + " remote candidates, " + local.candidates.size() + " local candidates)");
        return answer;
    }

    /** Relays a message from one peer to all other peers with an open channel. */
    private void relayString(String fromPeerId, String message)
    {
        for (Peer other : peers.values())
        {
            DataChannelTrack track = other.track;
            if (!other.id.equals(fromPeerId) && track != null && track.isOpen())
            {
                track.sendString(message);
            }
        }
    }

    private void relayBinary(String fromPeerId, byte[] message)
    {
        for (Peer other : peers.values())
        {
            DataChannelTrack track = other.track;
            if (!other.id.equals(fromPeerId) && track != null && track.isOpen())
            {
                track.sendBinary(message);
            }
        }
    }

    private class Observer implements SfuPeerConnectionObserver
    {
        private final String peerId;

        Observer(String peerId)
        {
            this.peerId = peerId;
        }

        @Override
        public void onIceConnectionStateChange(IceConnectionState state)
        {
            System.out.println("[" + peerId + "] ICE state: " + state);
        }

        @Override
        public void onConnected()
        {
            System.out.println("[" + peerId + "] connected (DTLS established)");
        }

        @Override
        public void onDataChannel(DataChannelTrack track)
        {
            System.out.println("[" + peerId + "] data channel opened: " + track.getLabel());
            Peer peer = peers.get(peerId);
            if (peer != null)
            {
                peer.track = track;
            }
        }

        @Override
        public void onDataChannelStringMessage(DataChannelTrack track, String message)
        {
            System.out.println("[" + peerId + "] message: " + message);
            relayString(peerId, message);
        }

        @Override
        public void onDataChannelBinaryMessage(DataChannelTrack track, byte[] message)
        {
            relayBinary(peerId, message);
        }

        @Override
        public void onDisconnected()
        {
            System.out.println("[" + peerId + "] disconnected");
            Peer peer = peers.remove(peerId);
            if (peer != null)
            {
                peer.connection.close();
            }
        }

        @Override
        public void onClosed()
        {
            peers.remove(peerId);
        }

        @Override
        public void onError(Throwable t)
        {
            System.err.println("[" + peerId + "] error: " + t);
        }
    }
}
