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
package org.jitsi.rtp.extensions;

import org.jitsi.rtp.util.BufferPool;

import java.nio.ByteBuffer;

/**
 * Translation of {@code extensions/ByteBuffer.kt}.
 */
public final class ByteBufferExtensions
{
    private ByteBufferExtensions()
    {
    }

    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

    /**
     * Return a (deep) copy of this ByteBuffer.
     * The position and mark from the original will NOT
     * be carried over (no mark will be set on the copy
     * and its position will be 0).  The mark of the original
     * will not be touched; the position of the original will
     * end up what it was before this call was made, BUT, it's value
     * will be modified during {@link #clone(ByteBuffer)}.
     */
    public static ByteBuffer clone(ByteBuffer buffer)
    {
        int startPosition = buffer.position();
        ByteBuffer clone = ByteBuffer.wrap(BufferPool.getArray(buffer.capacity()));
        buffer.rewind();
        clone.put(buffer);
        buffer.position(startPosition);
        clone.flip();
        // TODO(brian): handle if this was a readonly buffer
        return clone;
    }

    /**
     * Move this {@link ByteBuffer}'s position back one Byte
     */
    public static void rewindOneByte(ByteBuffer buffer)
    {
        buffer.position(buffer.position() - 1);
    }

    /**
     * Put the right-most 3 bytes of {@code value}
     * into the buffer
     */
    public static void put3Bytes(ByteBuffer buffer, int value)
    {
        buffer.put((byte) ((value & 0x00FF0000) >>> 16));
        buffer.put((byte) ((value & 0x0000FF00) >>> 8));
        buffer.put((byte) (value & 0x000000FF));
    }

    public static void put3Bytes(ByteBuffer buffer, int index, int value)
    {
        buffer.put(index, (byte) ((value & 0x00FF0000) >>> 16));
        buffer.put(index + 1, (byte) ((value & 0x0000FF00) >>> 8));
        buffer.put(index + 2, (byte) (value & 0x000000FF));
    }

    /**
     * Reads the next 3 bytes into the right-most
     * 3 bytes of an Int
     */
    public static int get3Bytes(ByteBuffer buffer)
    {
        int byte1 = (buffer.get() & 0xFF) << 16;
        int byte2 = (buffer.get() & 0xFF) << 8;
        int byte3 = buffer.get() & 0xFF;
        return byte1 | byte2 | byte3;
    }

    public static int get3Bytes(ByteBuffer buffer, int index)
    {
        int byte1 = (buffer.get(index) & 0xFF) << 16;
        int byte2 = (buffer.get(index + 1) & 0xFF) << 8;
        int byte3 = buffer.get(index + 2) & 0xFF;
        return byte1 | byte2 | byte3;
    }

    /**
     * Put the right-most {@code numBits} bits from {@code src} into the byte at {@code byteIndex}
     * starting at position {@code destBitPos}.  {@code destBitPos} is a 0-based index of the
     * bit in the byte at {@code byteIndex}, where 0 is the MSB and 7 is the LSB.
     */
    public static void putBits(ByteBuffer buffer, int byteIndex, int destBitPos, byte src, int numBits)
    {
        byte b = buffer.get(byteIndex);
        b = ByteExtensions.putBits(b, destBitPos, numBits, src);
        buffer.put(byteIndex, b);
    }

    public static void putBitAsBoolean(ByteBuffer buffer, int byteIndex, int destBitPos, boolean isSet)
    {
        byte b = buffer.get(byteIndex);
        b = ByteExtensions.putBit(b, destBitPos, isSet);
        buffer.put(byteIndex, b);
    }

    /**
     * Print the entire contents of the {@link ByteBuffer} as hex
     * digits
     */
    public static String toHex(ByteBuffer buffer)
    {
        StringBuilder result = new StringBuilder();

        int prevPosition = buffer.position();
        for (int i = 0; i < buffer.limit(); i++)
        {
            int octet = buffer.get(i);
            int firstIndex = (octet & 0xF0) >>> 4;
            int secondIndex = octet & 0x0F;
            result.append(HEX_CHARS[firstIndex]);
            result.append(HEX_CHARS[secondIndex]);
            if ((i + 1) % 16 == 0)
            {
                result.append("\n");
            }
            else if ((i + 1) % 4 == 0)
            {
                result.append(" ");
            }
        }
        buffer.position(prevPosition);

        return result.toString();
    }

    /**
     * Returns a newly constructed {@link ByteBuffer} whose position 0 will
     * start at {@code startPosition} in the current buffer and whose limit
     * and capacity will be {@code size}
     */
    public static ByteBuffer subBuffer(ByteBuffer buffer, int startPosition, int size)
    {
        if (startPosition + size > buffer.limit())
        {
            throw new RuntimeException(
                "SubBuffer goes beyond the buffer's limit " +
                    "(limit " + buffer.limit() + ", requested end of buffer " + (startPosition + size) + ")"
            );
        }
        ByteBuffer dup = buffer.duplicate();
        dup.position(startPosition).limit(startPosition + size);
        return dup.slice();
    }

    /**
     * Returns a newly constructed {@link ByteBuffer} whose position 0 will
     * start at {@code startPosition} in the current buffer and whose limit
     * and capacity will be the amount of bytes between {@code startPosition} and
     * the current buffer's {@code limit()}
     */
    public static ByteBuffer subBuffer(ByteBuffer buffer, int startPosition)
    {
        return subBuffer(buffer, startPosition, buffer.limit() - startPosition);
    }

    /**
     * Put {@code buf} into this buffer starting at {@code index}
     */
    public static ByteBuffer put(ByteBuffer buffer, int index, ByteBuffer buf)
    {
        int currentPosition = buffer.position();
        buffer.position(index);
        buffer.put(buf);
        buffer.position(currentPosition);
        return buffer;
    }

    /**
     * Shifts the data from {@code startPos} to {@code endPos} {@code numBytes} to the right.
     * Note that this method may increase the given buffer's limit, up to
     * its capacity.
     *
     * Note that {@code startPos} and {@code endPos} are zero-based.
     */
    public static void shiftDataRight(ByteBuffer buffer, int startPos, int endPos, int numBytes)
    {
        if (endPos + numBytes >= buffer.limit())
        {
            if (buffer.capacity() > endPos + numBytes)
            {
                buffer.limit(endPos + numBytes + 1);
            }
        }
        for (int index = endPos; index >= startPos; index--)
        {
            buffer.put(index + numBytes, buffer.get(index));
        }
    }

    public static void shiftDataLeft(ByteBuffer buffer, int startPos, int endPos, int numBytes)
    {
        for (int index = startPos; index <= endPos; index++)
        {
            buffer.put(index - numBytes, buffer.get(index));
        }
    }

    /**
     * Compare the contents of two ByteBuffers, each starting from their position 0
     */
    public static int compareToFromBeginning(ByteBuffer buffer, ByteBuffer other)
    {
        ByteBuffer thisRewound = buffer.duplicate();
        thisRewound.rewind();
        ByteBuffer otherRewound = other.duplicate();
        otherRewound.rewind();
        return thisRewound.compareTo(otherRewound);
    }

    /**
     * Return a new ByteBuffer that includes the contents of this one
     * plus {@code other}
     */
    public static ByteBuffer plus(ByteBuffer buffer, ByteBuffer other)
    {
        ByteBuffer newBuf = ByteBuffer.wrap(BufferPool.getArray(buffer.limit() + other.limit()));
        newBuf.rewind();
        ByteBuffer bufRewound = buffer.duplicate();
        bufRewound.rewind();
        newBuf.put(bufRewound);
        ByteBuffer otherRewound = other.duplicate();
        otherRewound.rewind();
        newBuf.put(otherRewound);
        newBuf.flip();

        return newBuf;
    }
}
