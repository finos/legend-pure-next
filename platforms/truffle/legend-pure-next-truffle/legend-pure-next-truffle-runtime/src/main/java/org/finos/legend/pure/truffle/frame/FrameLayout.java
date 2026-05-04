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

package org.finos.legend.pure.truffle.frame;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;

import java.util.Map;

/**
 * Static mapping of variable names to Truffle {@link FrameDescriptor} slots
 * for one {@link org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.function.FunctionDefinition}.
 *
 * <p>Built once per FD (by {@link FrameDescriptorBuilder}) and shared across
 * all invocations; the FrameDescriptor itself must be immutable after the
 * first frame allocation (Truffle contract).</p>
 *
 * <p>Phase 3 uses Object-typed slots everywhere — typed slots
 * ({@code Long}/{@code Double}/{@code Boolean}) come with Phase 3 follow-up
 * once we have a type-inference pass.</p>
 *
 * @param descriptor the FrameDescriptor shared by every invocation of the FD
 * @param slots      name → slot-index mapping (parameters + {@code letFunction} targets)
 * @param paramSlots ordered array of param slot indices, aligned with the
 *                   FD's parameter list. Callers bind {@code args[i]} into
 *                   slot {@code paramSlots[i]}.
 */
public record FrameLayout(
        FrameDescriptor descriptor,
        Map<String, Integer> slots,
        int[] paramSlots)
{
    /**
     * Look up the slot for {@code name}, or {@code null} if the name is not
     * in this layout (e.g. a captured variable from an outer scope).
     *
     * <p>{@code @TruffleBoundary} so PE doesn't try to inline {@code
     * HashMap.get} (whose {@code TreeNode.find} recursion blows past Graal's
     * inlining-depth budget — observed 991-deep chains causing 195+ {@code
     * PureFunctionRootNode} compilations to bail with "Too deep inlining"
     * before this annotation). Build-time callers don't care about
     * boundaries; the only hot caller is {@link
     * org.finos.legend.pure.truffle.PureContext#bindQpTypeVariablesStatic}
     * which runs once per QP call and is not in an inner loop.</p>
     */
    @TruffleBoundary
    public Integer slotFor(String name)
    {
        return slots.get(name);
    }

    public int size()
    {
        return slots.size();
    }
}
