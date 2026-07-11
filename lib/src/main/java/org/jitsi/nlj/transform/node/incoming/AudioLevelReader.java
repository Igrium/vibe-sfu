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
package org.jitsi.nlj.transform.node.incoming;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.AudioLevelListener;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.rtp.AudioRtpPacket;
import org.jitsi.nlj.rtp.RtpExtensionType;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.ObserverNode;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.extensions.unsigned.Unsigned;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.rtp.header_extensions.AudioLevelHeaderExtension;

import java.util.function.Predicate;

/**
 * https://tools.ietf.org/html/rfc6464#section-3
 */
public class AudioLevelReader
{
    public static final int MUTED_LEVEL = 127;

    /**
     * Replaces the upstream {@code jitsi-metaconfig}-based config. Since this port does not use
     * {@code jitsi-metaconfig}/HOCON, this hardcodes the upstream defaults (see {@code reference.conf}'s
     * {@code jmt.audio.level} section).
     */
    private static final int forwardedSilencePacketsLimit = 3;
    private static final boolean discardSilence = true;

    /**
     *  Process packets without cryptex pre-SRTP to allow the "skip decryption" optimization if they are to be
     *  dropped.
     */
    private final AudioLevelReaderNode preDecryptNode =
        new AudioLevelReaderNode("AudioLevelReader_pre_srtp", packetInfo -> !packetInfo.isOriginalHadCryptex());
    private final AudioLevelReaderNode postDecryptNode =
        new AudioLevelReaderNode("AudioLevelReader_post_srtp", PacketInfo::isOriginalHadCryptex);

    private Integer audioLevelExtId;
    private AudioLevelListener audioLevelListener;
    private int forwardedSilencePackets = 0;
    private final Stats stats = new Stats();

    /**
     * Whether we should forcibly mute this audio stream (by setting shouldDiscard to true).
     */
    private boolean forceMute = false;

    public AudioLevelReader(ReadOnlyStreamInformationStore streamInformationStore)
    {
        streamInformationStore.onRtpExtensionMapping(RtpExtensionType.SSRC_AUDIO_LEVEL, id -> audioLevelExtId = id);
    }

    public AudioLevelReaderNode getPreDecryptNode()
    {
        return preDecryptNode;
    }

    public AudioLevelReaderNode getPostDecryptNode()
    {
        return postDecryptNode;
    }

    public AudioLevelListener getAudioLevelListener()
    {
        return audioLevelListener;
    }

    public void setAudioLevelListener(AudioLevelListener audioLevelListener)
    {
        this.audioLevelListener = audioLevelListener;
    }

    public boolean isForceMute()
    {
        return forceMute;
    }

    public void setForceMute(boolean forceMute)
    {
        this.forceMute = forceMute;
    }

    public class AudioLevelReaderNode extends ObserverNode
    {
        private final Predicate<PacketInfo> shouldProcess;

        public AudioLevelReaderNode(String name, Predicate<PacketInfo> shouldProcess)
        {
            super(name);
            this.shouldProcess = shouldProcess;
        }

        @Override
        protected void observe(PacketInfo packetInfo)
        {
            if (!shouldProcess.test(packetInfo))
            {
                return;
            }

            if (!(packetInfo.getPacket() instanceof AudioRtpPacket))
            {
                return;
            }
            AudioRtpPacket audioRtpPacket = (AudioRtpPacket) packetInfo.getPacket();

            Integer audioLevelId = audioLevelExtId;
            if (audioLevelId != null)
            {
                RtpPacket.HeaderExtension ext = audioRtpPacket.getHeaderExtension(audioLevelId);
                if (ext != null)
                {
                    stats.audioLevel();

                    int level = AudioLevelHeaderExtension.getAudioLevel(ext);
                    boolean silence = level == MUTED_LEVEL;

                    if (!silence)
                    {
                        stats.nonSilence(AudioLevelHeaderExtension.getVad(ext));
                    }
                    if (silence && discardSilence && forwardedSilencePackets > forwardedSilencePacketsLimit)
                    {
                        packetInfo.setShouldDiscard(true);
                        stats.discardedSilence();
                    }
                    else if (AudioLevelReader.this.forceMute)
                    {
                        packetInfo.setShouldDiscard(true);
                        stats.discardedForceMute();
                    }
                    else
                    {
                        forwardedSilencePackets = silence ? forwardedSilencePackets + 1 : 0;
                        AudioLevelListener listener = audioLevelListener;
                        if (listener != null)
                        {
                            if (listener.onLevelReceived(audioRtpPacket.getSsrc(), Unsigned.toPositiveLong(127 - level)))
                            {
                                packetInfo.setShouldDiscard(true);
                                stats.discardedRanking();
                            }
                        }
                    }
                }
            }
        }

        @Override
        public NodeStatsBlock getNodeStats()
        {
            NodeStatsBlock block = super.getNodeStats();
            block.addString("audio_level_ext_id", String.valueOf(audioLevelExtId));
            block.addNumber("num_audio_levels", stats.numAudioLevels);
            block.addNumber("num_silence_packets_discarded", stats.numDiscardedSilence);
            block.addNumber("num_force_mute_discarded", stats.numDiscardedForceMute);
            block.addNumber("num_ranking_discarded", stats.numDiscardedRanking);
            block.addNumber("num_non_silence", stats.numNonSilence);
            block.addNumber("num_non_silence_with_vad", stats.numNonSilenceWithVad);
            block.addBoolean("force_mute", forceMute);
            return block;
        }

        @Override
        public ObjectNode statsJson()
        {
            ObjectNode json = super.statsJson();
            json.put("num_audio_levels", stats.numAudioLevels);
            json.put("num_silence_packets_discarded", stats.numDiscardedSilence);
            json.put("num_force_mute_discarded", stats.numDiscardedForceMute);
            json.put("num_ranking_discarded", stats.numDiscardedRanking);
            return json;
        }

        @Override
        public void trace(Runnable f)
        {
            f.run();
        }
    }

    private static class Stats
    {
        long numAudioLevels = 0;
        long numDiscardedSilence = 0;
        long numDiscardedForceMute = 0;
        long numDiscardedRanking = 0;
        long numNonSilence = 0;
        long numNonSilenceWithVad = 0;

        /** A packet contained an audio level header */
        void audioLevel()
        {
            numAudioLevels++;
        }

        /** A packet was discarded because it was silence. */
        void discardedSilence()
        {
            numDiscardedSilence++;
        }

        /** A packet was discarded because it was force-muted. */
        void discardedForceMute()
        {
            numDiscardedForceMute++;
        }

        /** A packet was discarded due to insufficient energy ranking or active speaker status. */
        void discardedRanking()
        {
            numDiscardedRanking++;
        }

        /** A non-silence packet was received (with or without the Voice Activity Detection flag). */
        void nonSilence(boolean hasVad)
        {
            numNonSilence++;
            if (hasVad)
            {
                numNonSilenceWithVad++;
            }
        }
    }
}
