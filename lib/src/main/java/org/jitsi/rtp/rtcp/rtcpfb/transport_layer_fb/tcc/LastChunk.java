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

package org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.List;

/**
 * This class is a port of TransportFeedback::LastChunk in
 * transport_feedback.h/transport_feedback.cc in Chrome
 * https://cs.chromium.org/chromium/src/third_party/webrtc/modules/rtp_rtcp/source/rtcp_packet/transport_feedback.h?l=95&rcl=20393ee9b7ba622f254908646a9c31bf87349fc7
 *
 * Because of this, it explicitly does NOT try to conform
 * to Kotlin style or idioms, instead striving to match the
 * Chrome code as closely as possible in an effort to make
 * future updates easier.
 *
 * NOTE: {@code Chunk} and {@code DeltaSize} were {@code typealias}es for {@code Int} in the Kotlin
 * source; they are represented directly as {@code int} here.
 */
@SuppressFBWarnings(
    value = "NM_METHOD_NAMING_CONVENTION",
    justification = "This class is a port and use the original names."
)
public class LastChunk
{
    private static final int kMaxRunLengthCapacity = 0x1FFF;
    private static final int kMaxOneBitCapacity = 14;
    private static final int kMaxTwoBitCapacity = 7;
    private static final int kMaxVectorCapacity = kMaxOneBitCapacity;
    private static final int kLarge = 2;

    private int size_ = 0;
    private boolean all_same_ = true;
    private boolean has_large_delta_ = false;
    private final int[] delta_sizes_ = new int[kMaxVectorCapacity];

    public boolean Empty()
    {
        return size_ == 0;
    }

    public void Clear()
    {
        size_ = 0;
        all_same_ = true;
        has_large_delta_ = false;
    }

    // Return if delta sizes still can be encoded into single chunk with added
    // |delta_size|.
    public boolean CanAdd(int deltaSize)
    {
        if (size_ < kMaxTwoBitCapacity)
        {
            return true;
        }
        if (size_ < kMaxOneBitCapacity && !has_large_delta_ && deltaSize != kLarge)
        {
            return true;
        }
        if (size_ < kMaxRunLengthCapacity && all_same_ && delta_sizes_[0] == deltaSize)
        {
            return true;
        }
        return false;
    }

    // Add |delta_size|, assumes |CanAdd(delta_size)|,
    public void Add(int deltaSize)
    {
        if (size_ < kMaxVectorCapacity)
        {
            delta_sizes_[size_] = deltaSize;
        }
        size_++;
        all_same_ = all_same_ && deltaSize == delta_sizes_[0];
        has_large_delta_ = has_large_delta_ || deltaSize == kLarge;
    }

    // Encode chunk as large as possible removing encoded delta sizes.
    // Assume CanAdd() == false for some valid delta_size.
    public int Emit()
    {
        if (all_same_)
        {
            int chunk = EncodeRunLength();
            Clear();
            return chunk;
        }
        if (size_ == kMaxOneBitCapacity)
        {
            int chunk = EncodeOneBit();
            Clear();
            return chunk;
        }
        int chunk = EncodeTwoBit(kMaxTwoBitCapacity);
        // Remove |kMaxTwoBitCapacity| encoded delta sizes:
        // Shift remaining delta sizes and recalculate all_same_ && has_large_delta_.
        size_ -= kMaxTwoBitCapacity;
        all_same_ = true;
        has_large_delta_ = false;
        for (int i = 0; i < size_; i++)
        {
            int deltaSize = delta_sizes_[kMaxTwoBitCapacity + i];
            delta_sizes_[i] = deltaSize;
            all_same_ = all_same_ && deltaSize == delta_sizes_[0];
            has_large_delta_ = has_large_delta_ || deltaSize == kLarge;
        }

        return chunk;
    }

    // Encode all stored delta_sizes into single chunk, pad with 0s if needed.
    public int EncodeLast()
    {
        if (all_same_)
        {
            return EncodeRunLength();
        }
        else if (size_ <= kMaxTwoBitCapacity)
        {
            return EncodeTwoBit(size_);
        }
        else
        {
            return EncodeOneBit();
        }
    }

    // Decode up to |max_size| delta sizes from |chunk|.
    public void Decode(int chunk, int max_size)
    {
        if ((chunk & 0x8000) == 0)
        {
            DecodeRunLength(chunk, max_size);
        }
        else if ((chunk & 0x4000) == 0)
        {
            DecodeOneBit(chunk, max_size);
        }
        else
        {
            DecodeTwoBit(chunk, max_size);
        }
    }

    // Appends content of the Lastchunk to |deltas|.
    public void AppendTo(List<Integer> deltas)
    {
        if (all_same_)
        {
            for (int i = 0; i < size_; i++)
            {
                deltas.add(delta_sizes_[0]);
            }
        }
        else
        {
            for (int i = 0; i < size_; i++)
            {
                deltas.add(delta_sizes_[i]);
            }
        }
    }

    // private:

    /**
     *
     * Run Length Status Vector Chunk
     *
     * 0                   1
     * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |T| S |       Run Length        |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     *
     * T = 0
     * S = symbol
     * Run Length = Unsigned integer denoting the run length of the symbol
     */
    private int EncodeRunLength()
    {
        return (delta_sizes_[0] << 13) | size_;
    }

    private void DecodeRunLength(int chunk, int max_count)
    {
        size_ = Math.min(chunk & 0x1fff, max_count);
        int delta_size = (chunk >>> 13) & 0x03;
        has_large_delta_ = delta_size >= kLarge;
        all_same_ = true;
        // To make it consistent with Add function, populate delta_sizes beyond 1st.
        for (int i = 0; i < Math.min(size_, kMaxVectorCapacity); i++)
        {
            delta_sizes_[i] = delta_size;
        }
    }

    /**
     *  One Bit Status Vector Chunk
     *
     * 0                   1
     * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |T|S|       symbol list         |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     *
     * T = 1
     * S = 0
     * Symbol list = 14 entries where 0 = not received, 1 = received 1-byte delta.
     */
    private int EncodeOneBit()
    {
        int chunk = 0x8000;
        for (int i = 0; i < size_; i++)
        {
            chunk = chunk | (delta_sizes_[i] << (kMaxOneBitCapacity - 1 - i));
        }
        return chunk;
    }

    private void DecodeOneBit(int chunk, int max_size)
    {
        size_ = Math.min(kMaxOneBitCapacity, max_size);
        has_large_delta_ = false;
        all_same_ = false;
        for (int i = 0; i < size_; i++)
        {
            delta_sizes_[i] = (chunk >>> (kMaxOneBitCapacity - 1 - i)) & 0x01;
        }
    }

    /**
     * Two Bit Status Vector Chunk
     *
     * 0                   1
     * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |T|S|       symbol list         |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

     * T = 1
     * S = 1
     * symbol list = 7 entries of two bits each.
     */
    private int EncodeTwoBit(int size)
    {
        int chunk = 0xC000;
        for (int i = 0; i < size; i++)
        {
            chunk = chunk | (delta_sizes_[i] << (2 * (kMaxTwoBitCapacity - 1 - i)));
        }
        return chunk;
    }

    private void DecodeTwoBit(int chunk, int max_size)
    {
        size_ = Math.min(kMaxTwoBitCapacity, max_size);
        has_large_delta_ = true;
        all_same_ = false;
        for (int i = 0; i < size_; i++)
        {
            delta_sizes_[i] = (chunk >>> (2 * (kMaxTwoBitCapacity - 1 - i))) & 0x03;
        }
    }
}
