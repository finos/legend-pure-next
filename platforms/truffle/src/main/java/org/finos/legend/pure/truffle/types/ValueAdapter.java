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

package org.finos.legend.pure.truffle.types;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.truffle.runtime.EvaluatorHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * Single wrap/unwrap boundary between specialized Truffle nodes (raw values:
 * {@code long}, {@code double}, {@code String}, {@link PureNull}, generated
 * class instances, {@code List<Object>} for typed sequences) and the legacy
 * bridged {@link org.finos.legend.pure.execution.NativeRepository} (boxed
 * {@link ValueSpecification} tree).
 *
 * <p>The invariant for the full-Truffle rewrite: every wrap/unwrap happens
 * here and nowhere else. Specialized math / collection / string / meta nodes
 * operate on raw values; only at the edge of a bridged-native call does a
 * node reach for {@link #toVS} or {@link #toRaw}.</p>
 *
 * <p>Delegates the heavy lifting to
 * {@link _E_ValueSpecification#wrap} and {@link _E_ValueSpecification#unwrap}
 * so the reference semantics stay in one place (the parity oracle). This
 * class exists so we can change that one place later (e.g. specialize the
 * adapter once {@code PureSequence} lands in Phase 4).</p>
 */
public final class ValueAdapter
{
    private ValueAdapter()
    {
    }

    /**
     * Unwrap a {@link ValueSpecification} to its raw Java value.
     *
     * <p>An {@link AtomicValue} returns its payload directly (possibly
     * {@code null}, which callers translate to {@link PureNull#INSTANCE}).
     * A {@link Collection} returns a {@code List<Object>} whose elements
     * are each unwrapped one level. Anything else passes through.</p>
     *
     * <p>This call is always safe on the slow path (bridged boundary). It
     * is {@link TruffleBoundary} because the underlying unwrap walks a
     * list and allocates — not something partial evaluation should
     * attempt to inline.</p>
     */
    @TruffleBoundary
    public static Object toRaw(Object boxed)
    {
        Object unwrapped = _E_ValueSpecification.unwrap(boxed);
        return unwrapped == null ? PureNull.INSTANCE : unwrapped;
    }

    /**
     * Wrap a raw Java value back into a {@link ValueSpecification} with the
     * supplied generic type and multiplicity.
     *
     * <p>Used when a specialized node's result has to flow into a bridged
     * native (arg position) or back into the compile-time IR. {@link
     * PureNull#INSTANCE} becomes an empty {@link Collection}.</p>
     */
    @TruffleBoundary
    public static ValueSpecification toVS(Object raw, GenericType genericType, Multiplicity multiplicity)
    {
        if (raw == PureNull.INSTANCE)
        {
            raw = null;
        }
        return _E_ValueSpecification.wrap(raw, genericType, multiplicity, resolver());
    }

    /**
     * Bulk variant of {@link #toRaw} for argument lists handed to specialized
     * nodes from a still-bridged caller. Returns a freshly allocated list
     * (specialized nodes are free to mutate).
     */
    @TruffleBoundary
    public static List<Object> toRawList(List<ValueSpecification> boxedArgs)
    {
        List<Object> out = new ArrayList<>(boxedArgs.size());
        for (ValueSpecification vs : boxedArgs)
        {
            out.add(toRaw(vs));
        }
        return out;
    }

    /**
     * Ensure {@code v} is a {@link ValueSpecification}. If it already is,
     * return as-is. If it's a raw Java value ({@code Long}, {@code Double},
     * {@code Boolean}, {@code String}, etc.), wrap it into an
     * {@link meta.pure.metamodel.valuespecification.AtomicValue} with the
     * correct genericType inferred from the Java type. This is the single
     * boundary-crossing point for raw-to-VS conversion — called only at
     * the edge of bridged natives, user function calls, and lambda returns,
     * never between adjacent specialized nodes.
     */
    @TruffleBoundary
    public static ValueSpecification ensureVS(Object v)
    {
        if (v instanceof ValueSpecification vs)
        {
            return vs;
        }
        if (v == PureNull.INSTANCE || v == null)
        {
            return _E_ValueSpecification.wrap(null, null, null, resolver());
        }
        MetadataAccess r = resolver();
        if (v instanceof PureSequence seq)
        {
            // Unwrap each element to raw before passing to wrap() — the
            // sequence may contain AtomicValue wrappers from bridge nodes,
            // and wrap() doesn't check for already-wrapped values.
            Object[] boxed = seq.toBoxedArray();
            java.util.List<Object> rawList = new java.util.ArrayList<>(boxed.length);
            for (Object item : boxed)
            {
                rawList.add(item instanceof ValueSpecification vs2 ? _E_ValueSpecification.unwrap(vs2) : item);
            }
            return _E_ValueSpecification.wrap(rawList, null, null, r);
        }
        if (v instanceof java.util.List<?>)
        {
            return _E_ValueSpecification.wrap(v, null, null, r);
        }
        GenericType gt = inferGenericType(v, r);
        Multiplicity mul = (Multiplicity) r.getElement("meta::pure::metamodel::multiplicity::PureOne");
        return _E_ValueSpecification.wrap(v, gt, mul, r);
    }

    private static GenericType inferGenericType(Object v, MetadataAccess r)
    {
        String typePath = switch (v)
        {
            case Long l -> "meta::pure::metamodel::type::primitives::Integer";
            case Double d -> "meta::pure::metamodel::type::primitives::Float";
            case Boolean b -> "meta::pure::metamodel::type::primitives::Boolean";
            case String s -> "meta::pure::metamodel::type::primitives::String";
            default -> null;
        };
        if (typePath == null)
        {
            return org.finos.legend.pure.execution.PureTypeResolver.getClassifierGenericType(v, r);
        }
        meta.pure.metamodel.type.Type type = (meta.pure.metamodel.type.Type) r.getElement(typePath);
        if (type == null)
        {
            return null;
        }
        return new meta.pure.metamodel.type.generics.InferredGenericTypeImpl(r)._type(type);
    }

    /**
     * Invoke a bridged (eager) native, converting raw Truffle args to VS
     * and the VS result back to raw. Truffle AST nodes call this instead
     * of touching NativeRepository or ValueSpecification directly.
     */
    @TruffleBoundary
    public static Object invokeNative(String signature, Object[] rawArgs,
                                      Object genericType, Object multiplicity)
    {
        java.util.List<ValueSpecification> vsArgs = new java.util.ArrayList<>(rawArgs.length);
        for (Object raw : rawArgs)
        {
            vsArgs.add(ensureVS(raw));
        }
        org.finos.legend.pure.execution.ValueSpecificationEvaluator eval = EvaluatorHolder.current();
        ValueSpecification result = eval.natives().execute(
                signature, vsArgs, eval,
                (GenericType) genericType,
                (Multiplicity) multiplicity);
        return toRaw(result);
    }

    /**
     * Invoke a user-defined FunctionDefinition with raw args. Wraps each
     * arg to VS, calls evaluateFunctionDefinition, unwraps the result.
     */
    @TruffleBoundary
    public static Object callUserFunction(Object fd, Object[] rawArgs)
    {
        java.util.List<ValueSpecification> vsArgs = new java.util.ArrayList<>(rawArgs.length);
        for (Object raw : rawArgs)
        {
            vsArgs.add(ensureVS(raw));
        }
        org.finos.legend.pure.execution.ValueSpecificationEvaluator eval = EvaluatorHolder.current();
        ValueSpecification result = eval.evaluateFunctionDefinition(
                (meta.pure.metamodel.function.FunctionDefinition) fd, vsArgs);
        return toRaw(result);
    }

    /**
     * Unwrap a boolean result from a VS (for filter/exists/forAll lambdas).
     */
    @TruffleBoundary
    public static boolean isTrue(Object v)
    {
        if (v instanceof Boolean b)
        {
            return b;
        }
        if (v instanceof ValueSpecification vs)
        {
            return Boolean.TRUE.equals(_E_ValueSpecification.unwrap(vs));
        }
        return false;
    }

    private static MetadataAccess resolver()
    {
        return EvaluatorHolder.current().natives().resolver();
    }
}
