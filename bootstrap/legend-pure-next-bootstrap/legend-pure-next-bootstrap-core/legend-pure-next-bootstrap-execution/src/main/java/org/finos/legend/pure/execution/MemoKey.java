// ©2026 JP Morgan Chase & Co. All rights reserved.
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

package org.finos.legend.pure.execution;

import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Multiplicity;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;
import platform.pure.metamodel.PackageableElement;
import platform.pure.metamodel.function.property.AbstractProperty;
import platform.pure.metamodel.function.property.QualifiedProperty;
import platform.pure.metamodel.valuespecification.VariableExpression;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The key of a memoized call: the function's address and its arguments. An object argument is the
 * same argument only when it is the same object; a primitive one (string, number, boolean, date,
 * binary) when it is equal; a collection when its elements are, in order.
 */
record MemoKey(String function, List<Object> arguments)
{
    /**
     * A function's address, as {@code address} gives it: a packageable function's path, a property's
     * owner and name, a qualified property's owner, name and parameters. A property the compiler
     * resolved for a call site has the address of the property it was resolved from.
     */
    static String address(Object function)
    {
        return switch (function)
        {
            case QualifiedProperty qp -> ownerPath(qp) + "." + qp._name() + "(" + qp._parameters().subList(1, qp._parameters().size()).stream().map(MemoKey::parameter).collect(Collectors.joining(",")) + ")";
            case AbstractProperty p -> ownerPath(p) + "." + p._name();
            case PackageableElement pe -> _PackageableElement.path(pe);
            default -> throw new RuntimeException("memoize: " + function + " has no address");
        };
    }

    private static String ownerPath(AbstractProperty property)
    {
        return _PackageableElement.path((PackageableElement) property._owner());
    }

    private static String parameter(VariableExpression v)
    {
        return _GenericType.print(v._genericType(), true) + _Multiplicity.print(v._multiplicity());
    }

    /**
     * An argument as part of a key: its value, its elements for a collection, wrapped so equality
     * follows the rules above.
     */
    static Object argument(Object argument)
    {
        Object value = _E_ValueSpecification.unwrap(argument);
        return value instanceof List<?> values
                ? values.stream().map(MemoKey::element).toList()
                : List.of(element(value));
    }

    private static Object element(Object value)
    {
        return isPrimitive(value) ? new Primitive(value) : new Identity(value);
    }

    private static boolean isPrimitive(Object value)
    {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof byte[]
                || value instanceof java.time.temporal.Temporal || value.getClass().getName().startsWith("java.");
    }

    private record Primitive(Object value)
    {
        @Override
        public boolean equals(Object o)
        {
            return o instanceof Primitive p && (value instanceof byte[] b ? p.value instanceof byte[] pb && Arrays.equals(b, pb) : Objects.equals(value, p.value));
        }

        @Override
        public int hashCode()
        {
            return value instanceof byte[] b ? Arrays.hashCode(b) : Objects.hashCode(value);
        }
    }

    private record Identity(Object value)
    {
        @Override
        public boolean equals(Object o)
        {
            return o instanceof Identity i && i.value == value;
        }

        @Override
        public int hashCode()
        {
            return System.identityHashCode(value);
        }
    }
}
