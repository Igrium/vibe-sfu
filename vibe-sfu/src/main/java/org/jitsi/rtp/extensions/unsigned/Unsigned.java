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

package org.jitsi.rtp.extensions.unsigned;

/**
 * Many times fields in packets are defined as unsigned.  Since
 * Java doesn't have unsigned types, we store those types as larger
 * than they actually are ({@code byte} and {@code short} held as
 * {@code int}, and {@code int} held as {@code long}).  These
 * helpers are to do the conversion correctly such that
 * we get a properly 'unsigned' version of the parsed field.
 * No helper is needed for the reverse operation (a simple cast
 * is sufficient).
 */
public final class Unsigned
{
    private Unsigned()
    {
    }

    public static int toPositiveInt(byte b)
    {
        return b & 0xFF;
    }

    public static int toPositiveInt(short s)
    {
        return s & 0xFFFF;
    }

    public static long toPositiveLong(int i)
    {
        return i & 0xFFFFFFFFL;
    }

    // TODO: i think these should be able to make the above functions obsolete
    public static short toPositiveShort(Number n)
    {
        if (n instanceof Byte)
        {
            return (short) (n.byteValue() & 0xFF);
        }
        return n.shortValue();
    }

    public static int toPositiveInt(Number n)
    {
        if (n instanceof Byte)
        {
            return n.byteValue() & 0xFF;
        }
        else if (n instanceof Short)
        {
            return n.shortValue() & 0xFFFF;
        }
        return n.intValue();
    }

    public static long toPositiveLong(Number n)
    {
        if (n instanceof Byte)
        {
            return n.byteValue() & 0xFF;
        }
        else if (n instanceof Short)
        {
            return n.shortValue() & 0xFFFF;
        }
        else if (n instanceof Integer)
        {
            return n.intValue() & 0xFFFFFFFFL;
        }
        return n.longValue();
    }
}
