/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.util;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.DebugStateMode;
import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.rtp.RtpExtension;
import org.jitsi.nlj.rtp.RtpExtensionType;
import org.jitsi.nlj.rtp.SsrcAssociationType;
import org.jitsi.utils.MediaType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class StreamInformationStoreImpl implements StreamInformationStore
{
    private final Object extensionsLock = new Object();
    private final Map<RtpExtensionType, List<Consumer<Integer>>> extensionHandlers = new java.util.HashMap<>();
    private final List<RtpExtension> _rtpExtensions = new CopyOnWriteArrayList<>();
    private final List<Consumer<Boolean>> extmapAllowMixedHandlers = new java.util.ArrayList<>();

    private final Object payloadTypesLock = new Object();
    private final List<Consumer<Map<Byte, PayloadType>>> payloadTypeHandlers = new java.util.ArrayList<>();
    private final Map<Byte, PayloadType> _rtpPayloadTypes = new ConcurrentHashMap<>();
    private final Map<Byte, PayloadType> rtpPayloadTypes = Collections.unmodifiableMap(_rtpPayloadTypes);

    private final SsrcAssociationStore localSsrcAssociations = new SsrcAssociationStore();
    private final SsrcAssociationStore remoteSsrcAssociations = new SsrcAssociationStore();

    private final ReceiveSsrcStore receiveSsrcStore = new ReceiveSsrcStore(localSsrcAssociations);

    // Support for FIR, PLI, REMB and TCC is declared per-payload type, but currently our code is not payload-type
    // aware. So until this changes we will just check if any of the PTs supports the relevant feedback.
    // We always assume support for FIR.
    private boolean supportsFir = true;
    private boolean supportsPli = false;
    private boolean supportsRemb = false;
    private boolean supportsTcc = false;

    private boolean extmapAllowMixed = false;

    @Override
    public List<RtpExtension> getRtpExtensions()
    {
        return _rtpExtensions;
    }

    @Override
    public void addRtpExtensionMapping(RtpExtension rtpExtension)
    {
        synchronized (extensionsLock)
        {
            _rtpExtensions.add(rtpExtension);
            List<Consumer<Integer>> handlers = extensionHandlers.get(rtpExtension.getType());
            if (handlers != null)
            {
                handlers.forEach(h -> h.accept((int) rtpExtension.getId()));
            }
        }
    }

    @Override
    public void clearRtpExtensions()
    {
        synchronized (extensionsLock)
        {
            _rtpExtensions.clear();
            extensionHandlers.values().forEach(handlers -> handlers.forEach(h -> h.accept(null)));
        }
    }

    @Override
    public void onRtpExtensionMapping(RtpExtensionType rtpExtensionType, Consumer<Integer> handler)
    {
        synchronized (extensionsLock)
        {
            extensionHandlers.computeIfAbsent(rtpExtensionType, k -> new java.util.ArrayList<>()).add(handler);
            _rtpExtensions.stream()
                .filter(ext -> ext.getType() == rtpExtensionType)
                .findFirst()
                .ifPresent(ext -> handler.accept((int) ext.getId()));
        }
    }

    @Override
    public boolean getExtmapAllowMixed()
    {
        return extmapAllowMixed;
    }

    @Override
    public void setExtmapAllowMixed(boolean allow)
    {
        synchronized (extensionsLock)
        {
            boolean changed = extmapAllowMixed != allow;
            extmapAllowMixed = allow;
            if (changed)
            {
                extmapAllowMixedHandlers.forEach(h -> h.accept(allow));
            }
        }
    }

    @Override
    public void onExtmapAllowMixedChanged(Consumer<Boolean> handler)
    {
        synchronized (extensionsLock)
        {
            extmapAllowMixedHandlers.add(handler);
            handler.accept(extmapAllowMixed);
        }
    }

    @Override
    public Map<Byte, PayloadType> getRtpPayloadTypes()
    {
        return rtpPayloadTypes;
    }

    @Override
    public void addRtpPayloadType(PayloadType payloadType)
    {
        synchronized (payloadTypesLock)
        {
            _rtpPayloadTypes.put(payloadType.getPt(), payloadType);
            supportsPli = rtpPayloadTypes.values().stream()
                .anyMatch(pt -> PayloadType.supportsPli(pt.getRtcpFeedbackSet()));
            supportsRemb = rtpPayloadTypes.values().stream()
                .anyMatch(pt -> PayloadType.supportsRemb(pt.getRtcpFeedbackSet()));
            supportsTcc = rtpPayloadTypes.values().stream()
                .anyMatch(pt -> PayloadType.supportsTcc(pt.getRtcpFeedbackSet()));
            payloadTypeHandlers.forEach(h -> h.accept(_rtpPayloadTypes));
        }
    }

    @Override
    public void clearRtpPayloadTypes()
    {
        synchronized (payloadTypesLock)
        {
            _rtpPayloadTypes.clear();
            supportsPli = false;
            supportsRemb = false;
            supportsTcc = false;
            payloadTypeHandlers.forEach(h -> h.accept(_rtpPayloadTypes));
        }
    }

    @Override
    public void onRtpPayloadTypesChanged(Consumer<Map<Byte, PayloadType>> handler)
    {
        synchronized (payloadTypesLock)
        {
            payloadTypeHandlers.add(handler);
            handler.accept(_rtpPayloadTypes);
        }
    }

    // NOTE(brian): Currently, we only have a use case to do a mapping of
    // secondary -> primary for local SSRCs and primary -> secondary for
    // remote SSRCs
    @Override
    public Long getLocalPrimarySsrc(long secondarySsrc)
    {
        return localSsrcAssociations.getPrimarySsrc(secondarySsrc);
    }

    @Override
    public Long getRemoteSecondarySsrc(long primarySsrc, SsrcAssociationType associationType)
    {
        return remoteSsrcAssociations.getSecondarySsrc(primarySsrc, associationType);
    }

    @Override
    public void addSsrcAssociation(SsrcAssociation ssrcAssociation)
    {
        if (ssrcAssociation instanceof LocalSsrcAssociation)
        {
            localSsrcAssociations.addAssociation(ssrcAssociation);
        }
        else if (ssrcAssociation instanceof RemoteSsrcAssociation)
        {
            remoteSsrcAssociations.addAssociation(ssrcAssociation);
        }
    }

    @Override
    public void addReceiveSsrc(long ssrc, MediaType mediaType)
    {
        receiveSsrcStore.addReceiveSsrc(ssrc, mediaType);
    }

    @Override
    public void removeReceiveSsrc(long ssrc)
    {
        receiveSsrcStore.removeReceiveSsrc(ssrc);
    }

    @Override
    public Set<Long> getReceiveSsrcs()
    {
        return receiveSsrcStore.getReceiveSsrcs();
    }

    @Override
    public Set<Long> getPrimaryMediaSsrcs()
    {
        return receiveSsrcStore.getPrimaryMediaSsrcs();
    }

    @Override
    public boolean getSupportsPli()
    {
        return supportsPli;
    }

    @Override
    public boolean getSupportsFir()
    {
        return supportsFir;
    }

    @Override
    public boolean getSupportsRemb()
    {
        return supportsRemb;
    }

    @Override
    public boolean getSupportsTcc()
    {
        return supportsTcc;
    }

    @Override
    public ObjectNode debugState(DebugStateMode mode)
    {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("supports_pli", supportsPli);
        node.put("supports_fir", supportsFir);
        ObjectNode rtpExtNode = JsonNodeFactory.instance.objectNode();
        getRtpExtensions().forEach(ext -> rtpExtNode.put(String.valueOf(ext.getId()), ext.getType().toString()));
        node.set("rtp_extensions", rtpExtNode);
        ObjectNode rtpPayloadNode = JsonNodeFactory.instance.objectNode();
        getRtpPayloadTypes().forEach((pt, payloadType) -> rtpPayloadNode.put(pt.toString(), payloadType.toString()));
        node.set("rtp_payload_types", rtpPayloadNode);
        if (mode == DebugStateMode.FULL)
        {
            node.put("local_ssrc_associations", localSsrcAssociations.toString());
            node.put("remote_ssrc_associations", remoteSsrcAssociations.toString());
            node.set("receive_ssrc_store", receiveSsrcStore.debugState());
        }
        return node;
    }
}
