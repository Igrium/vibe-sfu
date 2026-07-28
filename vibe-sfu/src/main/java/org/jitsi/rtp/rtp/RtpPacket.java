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

package org.jitsi.rtp.rtp;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.rtp.rtp.header_extensions.HeaderExtensionHelpers;
import org.jitsi.rtp.rtp.header_extensions.HeaderExtensionParser;
import org.jitsi.rtp.rtp.header_extensions.OneByteHeaderExtensionParser;
import org.jitsi.rtp.rtp.header_extensions.TwoByteHeaderExtensionParser;
import org.jitsi.rtp.util.BufferPool;
import org.jitsi.rtp.util.FieldParsers;
import org.jitsi.rtp.util.RtpUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 *
 * https://tools.ietf.org/html/rfc3550#section-5.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|X|  CC   |M|     PT      |       sequence number         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           timestamp                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           synchronization source (SSRC) identifier            |
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * |            contributing source (CSRC) identifiers             |
 * |                             ....                              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |              ...extensions (if present)...                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                   payload                                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
@SuppressFBWarnings(
    value = { "EI_EXPOSE_REP2" },
    justification = "We intentionally pass a reference to our buffer when using observableWhenChanged."
)
public class RtpPacket extends Packet
{
    /**
     * The size of the header for individual extensions.  Currently we only
     * support 1 byte header extensions
     */
    public static final int HEADER_EXT_HEADER_SIZE = 1;

    /**
     * How much space to leave in the beginning of new RTP packets. Having space in the beginning allows us to
     * implement adding RTP header extensions efficiently (by keeping the RTP payload in place and shifting the
     * header left).
     */
    public static final int BYTES_TO_LEAVE_AT_START_OF_PACKET = 10;

    public RtpPacket(byte[] buffer, int offset, int length)
    {
        super(buffer, offset, length);
        _payloadType = RtpHeader.getPayloadType(buffer, offset);
        _sequenceNumber = RtpHeader.getSequenceNumber(buffer, offset);
        _timestamp = RtpHeader.getTimestamp(buffer, offset);
        _ssrc = RtpHeader.getSsrc(buffer, offset);
        headerLength = RtpHeader.getTotalLength(buffer, offset);
        if (headerLength > length)
        {
            throw new IllegalArgumentException("RTP packet header length " + headerLength + " > length " + length);
        }
    }

    public RtpPacket(byte[] buffer)
    {
        this(buffer, 0, buffer.length);
    }

    public int getVersion()
    {
        return RtpHeader.getVersion(buffer, offset);
    }

    public void setVersion(int value)
    {
        RtpHeader.setVersion(buffer, offset, value);
    }

    public boolean getHasPadding()
    {
        return RtpHeader.hasPadding(buffer, offset);
    }

    public void setHasPadding(boolean value)
    {
        RtpHeader.setPadding(buffer, offset, value);
    }

    private boolean getHasEncodedExtensions()
    {
        return RtpHeader.hasExtensions(buffer, offset);
    }

    private void setHasEncodedExtensions(boolean value)
    {
        RtpHeader.setHasExtensions(buffer, offset, value);
    }

    public int getCsrcCount()
    {
        return RtpHeader.getCsrcCount(buffer, offset);
    }

    public boolean isMarked()
    {
        return RtpHeader.getMarker(buffer, offset);
    }

    public void setMarked(boolean value)
    {
        RtpHeader.setMarker(buffer, offset, value);
    }

    /* The four values below (payloadType, sequenceNumber, timestamp, and ssrc)
     * are very frequently accessed in our pipeline; store their values in
     * cached fields, rather than re-reading them from the buffer every time.
     */

    private int _payloadType;

    public int getPayloadType()
    {
        return _payloadType;
    }

    public void setPayloadType(int newValue)
    {
        if (newValue != _payloadType)
        {
            RtpHeader.setPayloadType(this.buffer, this.offset, newValue);
            _payloadType = newValue;
        }
    }

    private int _sequenceNumber;

    public int getSequenceNumber()
    {
        return _sequenceNumber;
    }

    public void setSequenceNumber(int newValue)
    {
        if (newValue != _sequenceNumber)
        {
            RtpHeader.setSequenceNumber(this.buffer, this.offset, newValue);
            _sequenceNumber = newValue;
        }
    }

    private long _timestamp;

    public long getTimestamp()
    {
        return _timestamp;
    }

    public void setTimestamp(long newValue)
    {
        if (newValue != _timestamp)
        {
            RtpHeader.setTimestamp(this.buffer, this.offset, newValue);
            _timestamp = newValue;
        }
    }

    private long _ssrc;

    public long getSsrc()
    {
        return _ssrc;
    }

    public void setSsrc(long newValue)
    {
        if (newValue != _ssrc)
        {
            RtpHeader.setSsrc(this.buffer, this.offset, newValue);
            _ssrc = newValue;
        }
    }

    public List<Long> getCsrcs()
    {
        return RtpHeader.getCsrcs(buffer, offset);
    }

    public int getExtensionsProfileType()
    {
        return RtpHeader.getExtensionsProfileType(buffer, offset);
    }

    /**
     * The length of the entire RTP header, including any extensions, in bytes
     */
    private int headerLength;

    public int getHeaderLength()
    {
        return headerLength;
    }

    protected void setHeaderLength(int value)
    {
        headerLength = value;
    }

    public int getPayloadLength()
    {
        return length - headerLength;
    }

    public int getPayloadOffset()
    {
        return offset + headerLength;
    }

    public int getPaddingSize()
    {
        if (!getHasPadding())
        {
            return 0;
        }
        // The last octet of the padding contains a count of how many
        // padding octets should be ignored, including itself.
        // It's an 8-bit unsigned number.
        return FieldParsers.getByteAsInt(buffer, offset + length - 1);
    }

    public void setPaddingSize(int value)
    {
        if (value > 0)
        {
            setHasPadding(true);
            buffer[offset + length - 1] = (byte) value;
        }
        else
        {
            setHasPadding(false);
        }
    }

    /**
     * The parser to use for header extensions, depending on the type of header extensions in this packet.
     */
    private HeaderExtensionParser getHeaderExtensionParser()
    {
        return HeaderExtensionHelpers.getHeaderExtensionParser(getExtensionsProfileType());
    }

    private final EncodedHeaderExtensions _encodedHeaderExtensions = new EncodedHeaderExtensions();

    private EncodedHeaderExtensions getEncodedHeaderExtensions()
    {
        _encodedHeaderExtensions.reset();
        return _encodedHeaderExtensions;
    }

    /**
     * For {@link RtpPacket} the payload is everything after the RTP Header.
     */
    @Override
    public String getPayloadVerification()
    {
        int payloadOffset = getPayloadOffset();
        int payloadLength = getPayloadLength();
        return "type=RtpPacket len=" + payloadLength +
            " hashCode=" + ByteArrayExtensions.hashCodeOfSegment(buffer, payloadOffset, payloadOffset + payloadLength);
    }

    private List<PendingHeaderExtension> pendingHeaderExtensions;

    private HeaderExtension getEncodedHeaderExtension(int extensionId)
    {
        if (!getHasEncodedExtensions())
        {
            return null;
        }

        Iterator<HeaderExtension> it = getEncodedHeaderExtensions();
        while (it.hasNext())
        {
            HeaderExtension ext = it.next();
            if (ext.getId() == extensionId)
            {
                return ext;
            }
        }
        return null;
    }

    public boolean getHasExtensions()
    {
        if (pendingHeaderExtensions != null)
        {
            return !pendingHeaderExtensions.isEmpty();
        }
        return getHasEncodedExtensions();
    }

    public void setHasExtensions(boolean value)
    {
        List<PendingHeaderExtension> p = pendingHeaderExtensions;
        if (p != null)
        {
            if (value && p.isEmpty())
            {
                throw new IllegalStateException(
                    "Cannot set hasExtensions to true with empty pending extensions"
                );
            }
            if (!value)
            {
                p.clear();
            }
        }
        else
        {
            setHasEncodedExtensions(value);
        }
    }

    public HeaderExtension getHeaderExtension(int extensionId)
    {
        Iterator<? extends HeaderExtension> activeHeaderExtensions = pendingHeaderExtensions != null
            ? pendingHeaderExtensions.iterator()
            : getEncodedHeaderExtensions();

        while (activeHeaderExtensions.hasNext())
        {
            HeaderExtension ext = activeHeaderExtensions.next();
            if (ext.getId() == extensionId)
            {
                return ext;
            }
        }
        return null;
    }

    private void createPendingHeaderExtensions(RemoveIfPredicate removeIf)
    {
        if (pendingHeaderExtensions != null)
        {
            return;
        }
        if (getHasEncodedExtensions() && getHeaderExtensionParser() == null)
        {
            throw new IllegalStateException(
                "Cannot modify header extensions for header extension type " +
                    Integer.toHexString(getExtensionsProfileType())
            );
        }
        List<PendingHeaderExtension> l = new ArrayList<>();
        Iterator<HeaderExtension> it = getEncodedHeaderExtensions();
        while (it.hasNext())
        {
            HeaderExtension ext = it.next();
            if (removeIf == null || !removeIf.test(ext))
            {
                l.add(new PendingHeaderExtension(ext));
            }
        }
        pendingHeaderExtensions = l;
    }

    @FunctionalInterface
    private interface RemoveIfPredicate
    {
        boolean test(HeaderExtension ext);
    }

    /**
     * Removes the header extension (or all header extensions) with the given ID.
     */
    public void removeHeaderExtension(int id)
    {
        if (pendingHeaderExtensions != null)
        {
            pendingHeaderExtensions.removeIf(h -> h.getId() == id);
        }
        else
        {
            createPendingHeaderExtensions(h -> h.getId() == id);
        }
    }

    /**
     * Removes all header extensions except those with ID values in {@code retain}
     */
    public void removeHeaderExtensionsExcept(Set<Integer> retain)
    {
        if (pendingHeaderExtensions != null)
        {
            pendingHeaderExtensions.removeIf(h -> !retain.contains(h.getId()));
        }
        else
        {
            createPendingHeaderExtensions(h -> !retain.contains(h.getId()));
        }
    }

    /**
     * Adds an RTP header extension with ID {@code id} and data length {@code extDataLength} to this
     * packet. The contents of the extension are not set to anything, and the
     * caller of this method is responsible for filling them in via the
     * {@link HeaderExtension} reference returned.
     *
     * This method MUST NOT be called while iterating over the extensions using
     * {@link #getHeaderExtension(int)}, or while manipulating the state of this
     * packet.
     *
     */
    public HeaderExtension addHeaderExtension(int id, int extDataLength)
    {
        if (!(id >= 1 && id <= 255))
        {
            throw new IllegalArgumentException("Invalid header extension ID " + id);
        }
        if (!(extDataLength >= 0 && extDataLength <= 255))
        {
            throw new IllegalArgumentException("Invalid header extension length " + extDataLength);
        }

        PendingHeaderExtension newHeader = new PendingHeaderExtension(id, extDataLength);

        if (pendingHeaderExtensions == null)
        {
            createPendingHeaderExtensions(null);
        }

        pendingHeaderExtensions.add(newHeader);

        return newHeader;
    }

    private HeaderExtensionParser pickParserForEncodedHeaders(
        List<PendingHeaderExtension> pendingHeaderExtensions
    )
    {
        for (PendingHeaderExtension it : pendingHeaderExtensions)
        {
            if (it.getId() >= 15 || it.getDataLengthBytes() == 0 || it.getDataLengthBytes() > 16)
            {
                return TwoByteHeaderExtensionParser.INSTANCE;
            }
        }
        return OneByteHeaderExtensionParser.INSTANCE;
    }

    public void encodeHeaderExtensions()
    {
        List<PendingHeaderExtension> pendingHeaderExtensions = this.pendingHeaderExtensions;
        if (pendingHeaderExtensions == null)
        {
            return;
        }

        HeaderExtensionParser newParser = pickParserForEncodedHeaders(pendingHeaderExtensions);

        // The byte[] of an RtpPacket has the following structure:
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // | A: unused | B: hdr + ext | C: payload | D: unused |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // And the regions have the following sizes:
        // A: this.offset
        // B: this.getHeaderLength()
        // C: this.getPayloadLength()
        // D: this.buffer.length - this.length - this.offset

        // If the newly-encoded header extensions block results in a header
        // longer than the current one,
        // we will try to extend the packet so that it uses A and/or D if
        // possible, in order to avoid allocating new memory.

        // We get this early, before we modify the buffer.
        int currHeaderLength = getHeaderLength();
        int currPayloadLength = getPayloadLength();
        int baseHeaderLength = RtpHeader.FIXED_HEADER_SIZE_BYTES + getCsrcCount() * 4;

        int newExtHeaderLength;
        if (pendingHeaderExtensions.isEmpty())
        {
            newExtHeaderLength = 0;
        }
        else
        {
            int rawHeaderLength = RtpHeader.EXT_HEADER_SIZE_BYTES;
            for (PendingHeaderExtension h : pendingHeaderExtensions)
            {
                rawHeaderLength += newParser.getExtHeaderSizeBytes() + h.getDataLengthBytes();
            }
            newExtHeaderLength = rawHeaderLength + RtpUtils.getNumPaddingBytes(rawHeaderLength);
        }

        int newHeaderLength = baseHeaderLength + newExtHeaderLength;
        int newPacketLength = newHeaderLength + currPayloadLength;

        int newPayloadOffset;
        byte[] newBuffer;
        if (buffer.length >= (newPacketLength + BYTES_TO_LEAVE_AT_END_OF_PACKET))
        {
            // We don't need a new buffer
            if ((offset + currHeaderLength) >= (newPacketLength - currPayloadLength))
            {
                // Region A (see above) is enough to accommodate the new
                // packet, keep the payload where it is.
                newPayloadOffset = getPayloadOffset();
            }
            else
            {
                // We have to use region D, so move the payload all the way to the right
                newPayloadOffset = buffer.length - currPayloadLength - BYTES_TO_LEAVE_AT_END_OF_PACKET;
                System.arraycopy(buffer, getPayloadOffset(), buffer, newPayloadOffset, currPayloadLength);
            }
            newBuffer = buffer;
        }
        else
        {
            // We need a new buffer. We will place the payload almost to the end
            // (leaving room for an SRTP tag)
            byte[] b = BufferPool.getArray(newPacketLength + BYTES_TO_LEAVE_AT_END_OF_PACKET);
            newPayloadOffset = b.length - currPayloadLength - BYTES_TO_LEAVE_AT_END_OF_PACKET;
            System.arraycopy(buffer, getPayloadOffset(), b, newPayloadOffset, currPayloadLength);
            newBuffer = b;
        }
        int newOffset = newPayloadOffset - newHeaderLength;

        if (buffer != newBuffer || offset != newOffset)
        {
            // Copy the base header into place.
            System.arraycopy(buffer, offset, newBuffer, newOffset, baseHeaderLength);
        }

        if (!pendingHeaderExtensions.isEmpty())
        {
            int off = newOffset + baseHeaderLength;
            // Write the header extension
            ByteArrayExtensions.putShort(newBuffer, off, (short) newParser.getHeaderExtensionLabel());
            ByteArrayExtensions.putShort(
                newBuffer, off + 2, (short) ((newExtHeaderLength - RtpHeader.EXT_HEADER_SIZE_BYTES) / 4)
            );
            off += 4;
            // Write pending header extension elements
            for (PendingHeaderExtension h : pendingHeaderExtensions)
            {
                int len = h.writeToBuffer(newBuffer, off, newParser);
                off += len;
            }
            // Write padding
            while (off < newOffset + newHeaderLength)
            {
                newBuffer[off] = 0;
                off++;
            }
        }

        byte[] oldBuffer = buffer;
        buffer = newBuffer;
        // Reference comparison to see if we got a new buffer.  If so, return the old one to the pool
        if (oldBuffer != newBuffer)
        {
            BufferPool.returnArray(oldBuffer);
        }
        offset = newOffset;
        length = newPacketLength;
        setHeaderLength(newHeaderLength);

        // ... and set the extension bit.
        setHasEncodedExtensions(!pendingHeaderExtensions.isEmpty());

        // Clear pending extensions.
        this.pendingHeaderExtensions = null;
    }

    @Override
    public RtpPacket clone()
    {
        RtpPacket clone = new RtpPacket(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length
        );
        postClone(clone);
        return clone;
    }

    /** Extra operations that need to be done after {@link #clone()}.  All subclasses overriding {@link #clone()}
     * must call this method on the newly-created clone. */
    protected void postClone(RtpPacket clone)
    {
        if (pendingHeaderExtensions != null)
        {
            clone.pendingHeaderExtensions = new ArrayList<>(pendingHeaderExtensions);
        }
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(this.getClass().getSimpleName()).append(": ");
        sb.append("PT=").append(getPayloadType());
        sb.append(", Ssrc=").append(getSsrc());
        sb.append(", SeqNum=").append(getSequenceNumber());
        sb.append(", M=").append(isMarked());
        sb.append(", X=").append(getHasEncodedExtensions());
        sb.append(", Ts=").append(getTimestamp());
        return sb.toString();
    }

    /**
     * Represents an RTP header extension.
     */
    public interface HeaderExtension
    {
        byte[] getBuffer();

        int getDataOffset();

        int getId();

        void setId(int id);

        int getDataLengthBytes();

        int getTotalLengthBytes();

        default HeaderExtension cloneExtension()
        {
            return new StandaloneHeaderExtension(this);
        }
    }

    @SuppressFBWarnings("CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE")
    public static class StandaloneHeaderExtension implements HeaderExtension
    {
        private final byte[] buffer;
        private int id;

        public StandaloneHeaderExtension(HeaderExtension ext)
        {
            this.buffer = new byte[ext.getDataLengthBytes()];
            System.arraycopy(ext.getBuffer(), ext.getDataOffset(), this.buffer, 0, ext.getDataLengthBytes());
            this.id = ext.getId();
        }

        @Override
        public byte[] getBuffer()
        {
            return buffer;
        }

        @Override
        public int getDataOffset()
        {
            return 0;
        }

        @Override
        public int getId()
        {
            return id;
        }

        @Override
        public void setId(int id)
        {
            this.id = id;
        }

        @Override
        public int getDataLengthBytes()
        {
            return buffer.length;
        }

        @Override
        public int getTotalLengthBytes()
        {
            return buffer.length;
        }
    }

    @SuppressFBWarnings("CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE")
    public class EncodedHeaderExtension implements HeaderExtension
    {
        private int currExtOffset = 0;
        private int currExtLength = 0;

        @Override
        public int getDataLengthBytes()
        {
            return getHeaderExtensionParser().getDataLengthBytes(getBuffer(), currExtOffset);
        }

        @Override
        public byte[] getBuffer()
        {
            return RtpPacket.this.buffer;
        }

        @Override
        public int getId()
        {
            if (currExtLength <= 0)
            {
                return -1;
            }
            return getHeaderExtensionParser().getId(getBuffer(), currExtOffset);
        }

        @Override
        public void setId(int newId)
        {
            HeaderExtensionParser parser = getHeaderExtensionParser();
            if (currExtLength < parser.getMinimumExtSizeBytes())
            {
                throw new IllegalStateException("Can't set ID: Header extension too short");
            }
            parser.writeIdAndLength(newId, getDataLengthBytes(), getBuffer(), currExtOffset);
        }

        @Override
        public int getDataOffset()
        {
            return getHeaderExtensionParser().getExtHeaderSizeBytes() + currExtOffset;
        }

        @Override
        public int getTotalLengthBytes()
        {
            return getHeaderExtensionParser().getExtHeaderSizeBytes() + getDataLengthBytes();
        }

        public void setOffsetLength(int nextHeaderExtOffset, int nextHeaderExtLength)
        {
            currExtOffset = nextHeaderExtOffset;
            currExtLength = nextHeaderExtLength;
        }
    }

    @SuppressFBWarnings(
        value = { "EI_EXPOSE_REP", "CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE" },
        justification = "We intentionally expose the internal buffer."
    )
    public class PendingHeaderExtension implements HeaderExtension
    {
        private int id;
        private final int dataLengthBytes;
        private final byte[] buffer;

        public PendingHeaderExtension(int id, int dataLengthBytes)
        {
            this.id = id;
            this.dataLengthBytes = dataLengthBytes;
            this.buffer = new byte[dataLengthBytes];
        }

        public PendingHeaderExtension(HeaderExtension other)
        {
            this(other.getId(), other.getDataLengthBytes());
            System.arraycopy(other.getBuffer(), other.getDataOffset(), buffer, 0, dataLengthBytes);
        }

        @Override
        public byte[] getBuffer()
        {
            return buffer;
        }

        @Override
        public int getDataOffset()
        {
            return 0;
        }

        @Override
        public int getId()
        {
            return id;
        }

        @Override
        public void setId(int id)
        {
            this.id = id;
        }

        @Override
        public int getDataLengthBytes()
        {
            return dataLengthBytes;
        }

        @Override
        public int getTotalLengthBytes()
        {
            return dataLengthBytes + getHeaderExtensionParser().getExtHeaderSizeBytes();
        }

        public int writeToBuffer(byte[] buffer, int offset, HeaderExtensionParser parser)
        {
            parser.writeIdAndLength(id, dataLengthBytes, buffer, offset);
            System.arraycopy(
                this.buffer,
                getDataOffset(),
                buffer,
                offset + parser.getExtHeaderSizeBytes(),
                dataLengthBytes
            );
            return parser.getExtHeaderSizeBytes() + dataLengthBytes;
        }
    }

    public class EncodedHeaderExtensions implements Iterator<HeaderExtension>
    {
        /**
         * The offset of the next extension
         */
        private int nextOffset = 0;

        /**
         * The remaining length of the extensions headers.
         */
        private int remainingLength = 0;

        private final EncodedHeaderExtension currHeaderExtension = new EncodedHeaderExtension();

        @Override
        public boolean hasNext()
        {
            if (getHeaderExtensionParser() == null)
            {
                return false;
            }
            // Consume any padding
            while (remainingLength > 0 && RtpUtils.isPadding(buffer[nextOffset]))
            {
                nextOffset++;
                remainingLength--;
            }
            if (remainingLength <= 0 || nextOffset < 0)
            {
                return false;
            }
            return getNextExtLength() > 0;
        }

        @Override
        public HeaderExtension next()
        {
            int nextExtLen = getNextExtLength();
            if (nextExtLen <= 0)
            {
                throw new RuntimeException("Invalid extension length.  Did hasNext() return true?");
            }
            currHeaderExtension.setOffsetLength(nextOffset, nextExtLen);
            nextOffset += nextExtLen;
            remainingLength -= nextExtLen;

            return currHeaderExtension;
        }

        /**
         * Return the entire length (including the header), in bytes, of the 'next' RTP extension
         * according to {@code nextOffset}.  If {@code remainingLength} is less than the minimum size for
         * an extension, or the parsed length is larger than {@code remainingLength}, return -1
         */
        private int getNextExtLength()
        {
            HeaderExtensionParser parser = getHeaderExtensionParser();
            if (parser == null)
            {
                return -1;
            }
            if (remainingLength < parser.getMinimumExtSizeBytes())
            {
                return -1;
            }
            int extLen = parser.getEntireLengthBytes(buffer, nextOffset);
            return extLen > remainingLength ? -1 : extLen;
        }

        /**
         * Resets this iterator back to the beginning of the extensions
         */
        void reset()
        {
            int extLength;
            // This treats unknown header extension types as no extensions, which is what we want.
            if (getHeaderExtensionParser() != null)
            {
                int extensionBlockLength = HeaderExtensionHelpers.getExtensionsTotalLength(
                    buffer,
                    offset + RtpHeader.FIXED_HEADER_SIZE_BYTES + getCsrcCount() * 4
                );

                extLength = extensionBlockLength - HeaderExtensionHelpers.TOP_LEVEL_EXT_HEADER_SIZE_BYTES;
            }
            else
            {
                extLength = 0;
            }

            if (extLength <= 0)
            {
                // No extensions
                nextOffset = -1;
                remainingLength = -1;
            }
            else
            {
                nextOffset = offset +
                    RtpHeader.FIXED_HEADER_SIZE_BYTES +
                    getCsrcCount() * 4 +
                    RtpHeader.EXT_HEADER_SIZE_BYTES;

                remainingLength = extLength;
            }
        }
    }
}
