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

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import meta.pure.metamodel.type.Type;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Type;
import org.finos.legend.pure.truffle.ast.PureNode;
import org.finos.legend.pure.truffle.ast.natives.lang.MatchNode;
import org.finos.legend.pure.truffle.runtime.StandaloneEvaluatorHolder;

/**
 * {@code instanceOf(Any[1], Type[1]) : Boolean[1]}.
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

    private static boolean doInstanceOf(Object rawVal, Object typeResult)
    {
        MetadataAccess resolver = StandaloneEvaluatorHolder.current().resolver();

        // Unwrap AtomicValue if present (e.g., dates kept as AV)
        Object valResult = rawVal;
        if (valResult instanceof meta.pure.metamodel.valuespecification.AtomicValue av)
        {
            valResult = av._value();
        }
        if (valResult == null || valResult instanceof org.finos.legend.pure.truffle.types.PureSequence _ps && _ps.isEmpty())
        {
            return false;
        }

        // Resolve the target type
        Type targetType = null;
        if (typeResult instanceof Type t)
        {
            targetType = t;
        }
        else if (typeResult instanceof meta.pure.metamodel.valuespecification.AtomicValue av && av._value() instanceof Type t)
        {
            targetType = t;
        }
        if (targetType == null)
        {
            return valResult != null;
        }

        // Get the value's runtime type. If original was AtomicValue, use
        // its genericType (carries the actual Pure type like Date/StrictDate).
        Type valueType = getRawValueType(rawVal, valResult, resolver);
        if (valueType == null)
        {
            return false;
        }
        return _Type.subtypeOf(valueType, targetType, resolver);
    }

    private static Type getRawValueType(Object original, Object unwrapped, MetadataAccess resolver)
    {
        // If original was an AtomicValue with genericType, use that
        // (handles dates stored as Strings with Date genericType)
        if (original instanceof meta.pure.metamodel.valuespecification.AtomicValue av
                && av._genericType() != null)
        {
            Type t = _GenericType.type(av._genericType());
            if (t != null)
            {
                return t;
            }
        }
        Object value = unwrapped;
        if (value instanceof meta.pure.metamodel.type.Any any && any._classifierGenericType() != null)
        {
            return _GenericType.type(any._classifierGenericType());
        }
        if (value instanceof Long)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Integer");
        }
        if (value instanceof Double)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Float");
        }
        if (value instanceof Boolean)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Boolean");
        }
        if (value instanceof String)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::String");
        }
        if (value instanceof java.math.BigDecimal)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::primitives::Decimal");
        }
        if (value instanceof org.finos.legend.pure.truffle.types.PureSequence)
        {
            return (Type) resolver.getElement("meta::pure::metamodel::type::Any");
        }
        return (Type) resolver.getElement("meta::pure::metamodel::type::Any");
    }
}
