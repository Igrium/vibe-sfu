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

package org.jitsi.rtp.util;

/**
 * All {@code byte[]}s that the library needs for packets will be acquired via {@link #getArray(int)}.
 *
 * When we're done with a buffer, we'll pass it to {@link #returnArray(byte[])}.  These methods
 * can be overridden so that a user of this library can use a pool for the buffers.
 *
 * @author Brian Baldino
 */
public final class BufferPool
{
    private BufferPool()
    {
    }

    @FunctionalInterface
    public interface ArrayGetter
    {
        byte[] getArray(int size);
    }

    @FunctionalInterface
    public interface ArrayReturner
    {
        void returnArray(byte[] array);
    }

    public static ArrayGetter getArray = size -> new byte[size];

    public static ArrayReturner returnArray = array -> { };

    public static byte[] getArray(int size)
    {
        return getArray.getArray(size);
    }

    public static void returnArray(byte[] array)
    {
        returnArray.returnArray(array);
    }
}
