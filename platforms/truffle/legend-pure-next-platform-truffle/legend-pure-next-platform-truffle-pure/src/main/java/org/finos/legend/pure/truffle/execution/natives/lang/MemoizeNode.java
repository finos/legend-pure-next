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

package org.finos.legend.pure.truffle.execution.natives.lang;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.compiler.module.MetadataAccess;
import org.finos.legend.pure.truffle.compiler.helper._Any;
import org.finos.legend.pure.truffle.compiler.helper._GenericType;
import org.finos.legend.pure.truffle.compiler.helper._PackageableElement;
import org.finos.legend.pure.truffle.execution.PureLanguage;
import org.finos.legend.pure.truffle.execution.ast.PureNode;
import org.finos.legend.pure.truffle.execution.ast.call.RawLambdaCallNode;
import org.finos.legend.pure.truffle.execution.types.PureSequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * memoize(f, args...): {@code f->eval(args...)}, computed once per execution for a given function
 * (by its address) and arguments (objects by identity, primitives by value, collections element by
 * element). See lang/eval/memoize.pure.
 */
@NodeInfo(shortName = "memoize")
public final class MemoizeNode extends PureNode
{
    @Children
    private PureNode[] children;
    @Child
    private RawLambdaCallNode callNode = new RawLambdaCallNode();

    public MemoizeNode(PureNode[] children)
    {
        this.children = children;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return memoize(evaluateChildren(frame));
    }

    @ExplodeLoop
    private Object[] evaluateChildren(VirtualFrame frame)
    {
        Object[] values = new Object[children.length];
        for (int i = 0; i < children.length; i++)
        {
            values[i] = children[i].executeGeneric(frame);
        }
        return values;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object memoize(Object[] values)
    {
        Object fn = values[0];
        Object[] args = Arrays.copyOfRange(values, 1, values.length);
        String address = address(fn, getResolver());
        List<Object> key = new ArrayList<>(args.length + 1);
        key.add(address);
        for (Object arg : args)
        {
            key.add(argument(arg));
        }
        return PureLanguage.get(this).memoized(key, address, () -> callNode.callWithArgs(fn, args));
    }

    /**
     * A function's address, as {@code address} gives it: a packageable function's path, a property's
     * owner and name, a qualified property's owner, name and parameters.
     */
    private static String address(Object fn, MetadataAccess resolver)
    {
        if (_Any.isType(fn, "platform::pure::metamodel::function::property::QualifiedProperty", resolver))
        {
            Object params = _Any.read(fn, "parameters");
            List<String> printed = new ArrayList<>();
            if (params instanceof PureSequence seq)
            {
                Object[] ps = seq.toBoxedArray();
                for (int i = 1; i < ps.length; i++)
                {
                    printed.add(_GenericType.print(_Any.read(ps[i], "genericType"), resolver) + multiplicity(_Any.read(ps[i], "multiplicity")));
                }
            }
            return ownerPath(fn, resolver) + "." + _Any.read(fn, "name") + "(" + String.join(",", printed) + ")";
        }
        if (_Any.isType(fn, "platform::pure::metamodel::function::property::AbstractProperty", resolver))
        {
            return ownerPath(fn, resolver) + "." + _Any.read(fn, "name");
        }
        return _PackageableElement.path(fn, resolver);
    }

    private static String ownerPath(Object property, MetadataAccess resolver)
    {
        return _PackageableElement.path(_Any.read(property, "owner"), resolver);
    }

    private static String multiplicity(Object m)
    {
        Object name = _Any.read(m, "name");
        if (name instanceof String parameter)
        {
            return "[" + parameter + "]";
        }
        Object lower = _Any.read(_Any.read(m, "lowerBound"), "value");
        Object upperBound = _Any.read(m, "upperBound");
        Object upper = upperBound == null ? null : _Any.read(upperBound, "value");
        return "[" + lower + (Objects.equals(lower, upper) ? "" : ".." + (upper == null ? "*" : upper)) + "]";
    }

    /**
     * An argument as part of a key: a collection by its elements, an object by identity, anything else
     * (a primitive) by value.
     */
    private static Object argument(Object value)
    {
        if (value instanceof PureSequence seq)
        {
            List<Object> elements = new ArrayList<>();
            for (Object e : seq.toBoxedArray())
            {
                elements.add(element(e));
            }
            return elements;
        }
        return List.of(element(value));
    }

    private static Object element(Object value)
    {
        return value == null || value.getClass().getName().startsWith("java.") || value instanceof org.finos.legend.pure.truffle.execution.types.PureDate
                ? new Primitive(value)
                : new Identity(value);
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
