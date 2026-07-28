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

package org.jitsi.nlj.codec.vpx;

public class VpxUtils
{
    /**
     * The bitmask for the TL0PICIDX field.
     */
    public static final int TL0PICIDX_MASK = 0xff;

    /**
     * The bitmask for the extended picture id field.
     */
    public static final int EXTENDED_PICTURE_ID_MASK = 0x7fff;

    /**
     * Returns the delta between two VP8/VP9 extended picture IDs, taking into account
     * rollover.  This will return the 'shortest' delta between the two
     * picture IDs in the form of the number you'd add to b to get a. e.g.:
     * getExtendedPictureIdDelta(1, 10) -&gt; -9 (10 + -9 = 1)
     * getExtendedPictureIdDelta(1, 32760) -&gt; 9 (32760 + 9 = 1)
     * @return the delta between two extended picture IDs (modulo 2^15).
     */
    public static int getExtendedPictureIdDelta(int a, int b)
    {
        int diff = a - b;
        if (diff < -(1 << 14))
        {
            return diff + (1 << 15);
        }
        else if (diff > (1 << 14))
        {
            return diff - (1 << 15);
        }
        else
        {
            return diff;
        }
    }

    /**
     * Apply a delta to a given extended picture ID and return the result (taking
     * rollover into account)
     * @param start the starting extended picture ID
     * @param delta the delta to be applied
     * @return the extended picture ID resulting from doing "start + delta"
     */
    public static int applyExtendedPictureIdDelta(int start, int delta)
    {
        return (start + delta) & EXTENDED_PICTURE_ID_MASK;
    }

    /**
     * Returns the delta between two VP8/VP9 Tl0PicIdx values, taking into account
     * rollover.  This will return the 'shortest' delta between the two
     * picture IDs in the form of the number you'd add to b to get a. e.g.:
     * getTl0PicIdxDelta(1, 10) -&gt; -9 (10 + -9 = 1)
     * getTl0PicIdxDelta(1, 250) -&gt; 7 (250 + 7 = 1)
     *
     * If either value is -1 (meaning tl0picidx not found) return 0.
     * @return the delta between two extended picture IDs (modulo 2^8).
     */
    public static int getTl0PicIdxDelta(int a, int b)
    {
        if (a < 0 || b < 0)
        {
            return 0;
        }
        int diff = a - b;
        if (diff < -(1 << 7))
        {
            return diff + (1 << 8);
        }
        else if (diff > (1 << 7))
        {
            return diff - (1 << 8);
        }
        else
        {
            return diff;
        }
    }

    /**
     * Apply a delta to a given Tl0PidIcx and return the result (taking
     * rollover into account)
     * @param start the starting Tl0PicIdx
     * @param delta the delta to be applied
     * @return the Tl0PicIdx resulting from doing "start + delta"
     */
    public static int applyTl0PicIdxDelta(int start, int delta)
    {
        return (start + delta) & TL0PICIDX_MASK;
    }
}
