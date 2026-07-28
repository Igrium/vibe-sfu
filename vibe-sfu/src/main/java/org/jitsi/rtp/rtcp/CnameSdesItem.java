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

package org.jitsi.rtp.rtcp;

import java.nio.charset.StandardCharsets;

/**
 * https://tools.ietf.org/html/rfc3550#section-6.5.1
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    CNAME=1    |     length    | user and domain name        ...
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public class CnameSdesItem extends SdesItem
{
    private final byte[] dataField;
    private final int sizeBytes;
    private String cname;

    public CnameSdesItem(byte[] buf, int offset, int length)
    {
        super(SdesItemType.CNAME);
        this.dataField = copyData(buf, offset, length);
        this.sizeBytes = SDES_ITEM_HEADER_SIZE + dataField.length;
    }

    @Override
    public int getSizeBytes()
    {
        return sizeBytes;
    }

    public String getCname()
    {
        if (cname == null)
        {
            cname = new String(dataField, StandardCharsets.US_ASCII);
        }
        return cname;
    }

    @Override
    public String toString()
    {
        return "CNAME: " + getCname();
    }
}
