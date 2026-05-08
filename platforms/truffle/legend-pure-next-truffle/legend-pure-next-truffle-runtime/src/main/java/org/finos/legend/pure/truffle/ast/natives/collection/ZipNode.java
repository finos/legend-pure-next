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

package org.finos.legend.pure.truffle.ast.natives.collection;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.pdb.meta.pure.functions.collection.PairImpl;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

/**
 * {@code zip(T[*], U[*]) : Pair<T,U>[*]} -- zips two collections into pairs.
 */
@NodeInfo(shortName = "zip")
public final class ZipNode extends PureNode
{
    @Child
    private PureNode leftArg;

    @Child
    private PureNode rightArg;

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue cachedPairCgt;

    public ZipNode(PureNode leftArg, PureNode rightArg)
    {
        this.leftArg = leftArg;
        this.rightArg = rightArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object left = leftArg.executeGeneric(frame);
        Object right = rightArg.executeGeneric(frame);
        // Empty short-circuit BEFORE the Pair CGT lookup — many call sites
        // (e.g. resolveForTarget_GenericTypeValue's multiplicityArguments
        // zip) hit this with one or both sides empty, and cgtForType is a
        // @TruffleBoundary that's pure overhead in that case.
        int leftSz = CollectionHelper.size(left);
        int rightSz = CollectionHelper.size(right);
        int sz = Math.min(leftSz, rightSz);
        if (sz == 0)
        {
            return PureSequence.EMPTY;
        }
        return doZip(left, right, sz, lookupPairCgt());
    }

    private org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue lookupPairCgt()
    {
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue cgt = cachedPairCgt;
        if (cgt == null)
        {
            // First-call population — invalidate so PE re-specialises with
            // the @CompilationFinal field treated as a constant on subsequent
            // executions. Without this, the JIT would treat cachedPairCgt as
            // null (its initial value at compile time) and never benefit from
            // the cache on JIT-compiled code paths.
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
            cgt = populatePairCgt();
        }
        return cgt;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue populatePairCgt()
    {
        org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue cgt =
                getContext().cgtForType("meta::pure::functions::collection::Pair");
        if (cgt == null)
        {
            throw new RuntimeException("[ZipNode] Cannot resolve Pair type from PDB");
        }
        cachedPairCgt = cgt;
        return cgt;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object doZip(Object left, Object right, int sz, org.finos.legend.pure.truffle.pdb.meta.pure.metamodel.type.generics.GenericTypeValue pairCGT)
    {
        Object[] pairs = new Object[sz];
        for (int i = 0; i < sz; i++)
        {
            PairImpl pair = new PairImpl();
            pair._first(CollectionHelper.at(left, i));
            pair._second(CollectionHelper.at(right, i));
            pair._classifierGenericType(pairCGT);
            pairs[i] = pair;
        }
        return new ObjectSequence(pairs);
    }
}
