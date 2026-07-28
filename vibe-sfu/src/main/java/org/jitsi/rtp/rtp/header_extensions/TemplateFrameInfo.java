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
package org.jitsi.rtp.rtp.header_extensions;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.ArrayList;
import java.util.List;

/* The only thing this changes from its parent class is to make the lists mutable, so the parent equals() is fine. */
@SuppressFBWarnings("EQ_DOESNT_OVERRIDE_EQUALS")
public class TemplateFrameInfo extends FrameInfo
{
    public TemplateFrameInfo(int spatialId, int temporalId)
    {
        this(spatialId, temporalId, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public TemplateFrameInfo(
        int spatialId,
        int temporalId,
        List<DTI> dti,
        List<Integer> fdiff,
        List<Integer> chains
    )
    {
        super(spatialId, temporalId, dti, fdiff, chains);
    }
}
