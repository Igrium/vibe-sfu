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
package org.jitsi.nlj.transform.node.outgoing;

import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.rtp.RtpExtensionType;
import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket;
import org.jitsi.nlj.transform.node.ModifierNode;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.rtp.RtpPacket;

import java.util.HashSet;
import java.util.Set;

/**
 * Strip all hop-by-hop header extensions. By default, this leaves ssrc-audio-level and video-orientation,
 * plus the AV1 dependency descriptor if the packet is an Av1DDPacket.
 */
public class HeaderExtStripper extends ModifierNode
{
    public static final Set<RtpExtensionType> defaultRetainedExtTypes = new HashSet<>();
    static
    {
        defaultRetainedExtTypes.add(RtpExtensionType.SSRC_AUDIO_LEVEL);
        defaultRetainedExtTypes.add(RtpExtensionType.VIDEO_ORIENTATION);
    }

    private final Set<Integer> retainedExts = new HashSet<>();
    private final Set<Integer> retainedExtsWithAv1DD = new HashSet<>();
    private Set<RtpExtensionType> retainedExtTypes = defaultRetainedExtTypes;

    public HeaderExtStripper(ReadOnlyStreamInformationStore streamInformationStore)
    {
        super("Strip header extensions");

        for (RtpExtensionType rtpExtensionType : retainedExtTypes)
        {
            streamInformationStore.onRtpExtensionMapping(rtpExtensionType, id -> {
                if (id != null)
                {
                    retainedExts.add(id);
                    retainedExtsWithAv1DD.add(id);
                }
            });
        }
        streamInformationStore.onRtpExtensionMapping(RtpExtensionType.AV1_DEPENDENCY_DESCRIPTOR, id -> {
            if (id != null)
            {
                retainedExtsWithAv1DD.add(id);
            }
        });
    }

    public void addRtpExtensionToRetain(RtpExtensionType extensionType)
    {
        Set<RtpExtensionType> newTypes = new HashSet<>(retainedExtTypes);
        newTypes.add(extensionType);
        retainedExtTypes = newTypes;
    }

    @Override
    protected PacketInfo modify(PacketInfo packetInfo)
    {
        RtpPacket rtpPacket = packetInfo.packetAs();

        Set<Integer> retained = rtpPacket instanceof Av1DDPacket ? retainedExtsWithAv1DD : retainedExts;

        // TODO: we should also retain any extensions that were not signaled.
        rtpPacket.removeHeaderExtensionsExcept(retained);

        return packetInfo;
    }

    @Override
    protected void trace(Runnable f)
    {
        f.run();
    }
}
