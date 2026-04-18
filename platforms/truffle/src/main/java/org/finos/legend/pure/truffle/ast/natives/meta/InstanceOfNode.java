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

package org.finos.legend.pure.truffle.ast.natives.meta;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;
import org.finos.legend.pure.truffle.types.ValueAdapter;

/**
 * {@code instanceOf(Any[1], Type[1]) : Boolean[1]}.
 *
 * <p>Checks whether the runtime type of the first argument is a subtype of
 * the second argument. Returns a raw boolean.</p>
 */
@NodeInfo(shortName = "instanceOf")
public final class InstanceOfNode extends PureNode
{
    @Child
    private PureNode value;

    @Child
    private PureNode typeArg;

    public InstanceOfNode(PureNode value, PureNode typeArg)
    {
        this.value = value;
        this.typeArg = typeArg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        Object valResult = value.executeGeneric(frame);
        Object typeResult = typeArg.executeGeneric(frame);
        return doInstanceOf(valResult, typeResult);
    }

    @TruffleBoundary
    private static boolean doInstanceOf(Object valResult, Object typeResult)
    {
        ValueSpecification valVS = ValueAdapter.ensureVS(valResult);
        ValueSpecification typeVS = ValueAdapter.ensureVS(typeResult);
        MetadataAccess resolver = EvaluatorHolder.current().natives().resolver();

        Object unwrapped = _E_ValueSpecification.unwrap(valVS);
        if (unwrapped == null)
        {
            return false;
        }

        // Resolve the target type
        Type targetType = null;
        Object targetValue = _E_ValueSpecification.unwrap(typeVS);
        if (targetValue instanceof Type t)
        {
            targetType = t;
        }
        else if (typeVS._genericType() != null)
        {
            targetType = _GenericType.type(typeVS._genericType());
        }
        if (targetType == null)
        {
            return unwrapped != null;
        }

        // Get the value's runtime type
        Type valueType = _E_ValueSpecification.getValueOriginalType(valVS);
        if (valueType == null)
        {
            return false;
        }
        return _Type.subtypeOf(valueType, targetType, resolver);
    }
}
