// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.truffle.ast.natives.meta;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.PureContext;
import org.finos.legend.pure.truffle.PureLanguage;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.RawClosure;
import org.finos.legend.pure.truffle.ast.natives.collection.CollectionHelper;
import org.finos.legend.pure.truffle.ast.natives.collection.MapImpl;
import org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;
import org.finos.legend.pure.truffle.types.ObjectSequence;

/**
 * {@code openVariableValues(LambdaFunction<Any>[1]) : Map<String, List<Any>>[1]}.
 *
 * <p>Reads {@link RawClosure#capturedNames} / {@link RawClosure#capturedValues}
 * off the runtime closure and builds a Pure {@code Map<String, List<Any>>}
 * with one entry per captured variable. Each value is wrapped in a Pure
 * {@code List} (PDO with {@code values: T[*]}); scalars become 1-element
 * lists, sequences become same-element lists, empties become 0-element lists.</p>
 *
 * <p>Returns an empty Map if the runtime value isn't a {@code RawClosure}
 * (e.g. the argument is a {@code UserDefinedFunction} reference rather than
 * a lambda literal) — matches the spec which restricts the public signature
 * to {@code LambdaFunction} but doesn't fail loudly on misuse.</p>
 */
@NodeInfo(shortName = "openVariableValues")
public final class OpenVariableValuesNode extends PureNode
{
    @Child
    private PureNode lambdaArg;

    public OpenVariableValuesNode(PureNode lambdaArg)
    {
        this.lambdaArg = lambdaArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object lambda = lambdaArg.executeGeneric(frame);
        return buildMap(lambda, getResolver());
    }

    @CompilerDirectives.TruffleBoundary
    private static Object buildMap(Object lambdaRaw, TruffleMetadataAccess resolver)
    {
        PureContext ctx = PureLanguage.get(null);
        Object mapCgt  = ctx.cgtForType("meta::pure::functions::collection::Map");
        Object listCgt = ctx.cgtForType("meta::pure::functions::collection::List");
        if (mapCgt == null || listCgt == null)
        {
            throw new RuntimeException("[OpenVariableValuesNode] Cannot resolve Map/List type from PDB");
        }

        MapImpl map = new MapImpl();
        PureObj.write(map, "classifierGenericType", mapCgt);

        if (!(lambdaRaw instanceof RawClosure rc))
        {
            // Non-lambda Function — no captures to expose. Returning empty
            // matches the legend-pure reference behavior on UserDefinedFunction
            // arguments (testOpenVariableValuesForFunction expects empty keys).
            return map;
        }

        String[] names  = rc.capturedNames();
        Object[] values = rc.capturedValues();
        if (names == null || values == null)
        {
            return map;
        }

        for (int i = 0; i < names.length; i++)
        {
            Object listInstance = TruffleInstanceFactory.createInstance(
                    "meta::pure::functions::collection::List", resolver);
            PureObj.write(listInstance, "classifierGenericType", listCgt);
            PureObj.write(listInstance, "values", toObjectSequence(values[i]));
            map.put(names[i], listInstance);
        }
        return map;
    }

    // Normalize a captured raw value to an ObjectSequence holding its
    // elements. Sequences flatten; scalars become 1-element; null/empty
    // become 0-element. Uses CollectionHelper so the same multiplicity
    // semantics as the rest of the runtime apply.
    @CompilerDirectives.TruffleBoundary
    private static ObjectSequence toObjectSequence(Object raw)
    {
        int sz = CollectionHelper.size(raw);
        Object[] arr = new Object[sz];
        for (int i = 0; i < sz; i++)
        {
            arr[i] = CollectionHelper.at(raw, i);
        }
        return new ObjectSequence(arr);
    }
}
