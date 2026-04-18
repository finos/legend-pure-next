// Copyright 2024 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.pure.truffle.ast.natives.date;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.ast.PureNode;

import java.time.LocalDateTime;
import java.util.function.ToIntFunction;

/**
 * Extracts a single component (year, month, day, hour, minute, second, millis)
 * from a Pure date string. Shared implementation for all date component natives.
 */
@NodeInfo(shortName = "dateComp")
public final class DateComponentNode extends PureNode
{
    private final String signature;
    private final ToIntFunction<LocalDateTime> extractor;

    @Child
    private PureNode dateArg;

    public DateComponentNode(PureNode dateArg, String signature, ToIntFunction<LocalDateTime> extractor)
    {
        this.dateArg = dateArg;
        this.signature = signature;
        this.extractor = extractor;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object v = dateArg.executeGeneric(frame);
        return extract(v);
    }

    @TruffleBoundary
    private long extract(Object v)
    {
        String dateStr = DateHelper.asDateString(v, signature);
        LocalDateTime ldt = DateHelper.parseDate(dateStr);
        return (long) extractor.applyAsInt(ldt);
    }
}
