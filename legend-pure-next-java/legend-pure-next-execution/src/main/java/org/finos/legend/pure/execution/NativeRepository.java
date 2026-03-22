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

package org.finos.legend.pure.execution;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.execution.natives.boolean_.BooleanNatives;
import org.finos.legend.pure.execution.natives.collection.CollectionNatives;
import org.finos.legend.pure.execution.natives.io.IONatives;
import org.finos.legend.pure.execution.natives.lang.AssertNatives;
import org.finos.legend.pure.execution.natives.lang.LangNatives;
import org.finos.legend.pure.execution.natives.math.MathNatives;
import org.finos.legend.pure.execution.natives.meta.ElementPathNatives;
import org.finos.legend.pure.execution.natives.meta.MetaNatives;
import org.finos.legend.pure.execution.natives.string.StringNatives;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Registry of Java implementations for Pure native functions.
 *
 * <p>Each native function is registered by its full mangled signature
 * (e.g. {@code "plus_String_1__String_1__String_1_"}) so that each
 * overload has a distinct, type-specific implementation.</p>
 */
public class NativeRepository
{
    /**
     * A native function implementation.
     * Each implementation receives the call-site {@link FunctionExpression} ({@code fe})
     * and returns a raw value (or an explicit {@link ValueSpecification}).
     * The {@link #execute} method wraps raw values using {@code fe._genericType()} and
     * {@code fe._multiplicity()} — the compiler-declared Pure return type, not Java inference.
     * Natives that need to control the exact return type can return a {@code ValueSpecification}
     * directly; {@link _E_ValueSpecification#wrap} will pass it through unchanged.
     */
    @FunctionalInterface
    public interface NativeImpl
    {
        Object apply(List<ValueSpecification> args, ValueSpecificationEvaluator eval, FunctionExpression fe);
    }

    /**
     * A lazy native function implementation that receives the unevaluated
     * {@link FunctionExpression} and controls parameter evaluation itself.
     * Used for short-circuit operators (and, or) and other special forms.
     */
    @FunctionalInterface
    public interface LazyNativeImpl
    {
        Object apply(FunctionExpression fe, ValueSpecificationEvaluator eval);
    }

    /**
     * Exception type for Pure assertion failures.
     * Distinct from RuntimeException so that assertError can let it propagate.
     */
    public static class PureAssertionError extends RuntimeException
    {
        public PureAssertionError(String message)
        {
            super(message);
        }
    }

    private final Map<String, NativeImpl> natives = new HashMap<>();
    private final Map<String, LazyNativeImpl> lazyNatives = new HashMap<>();
    private final MetadataAccess resolver;

    public NativeRepository(MetadataAccess resolver)
    {
        this.resolver = resolver;
        registerDefaults();
    }

    /**
     * Check if the given signature is registered as a lazy native.
     */
    public boolean isLazy(String signature)
    {
        return lazyNatives.containsKey(signature);
    }

    /**
     * Execute a lazy native function — parameters are NOT pre-evaluated.
     */
    public ValueSpecification executeLazy(String signature, ValueSpecificationEvaluator evaluator, FunctionExpression fe)
    {
        LazyNativeImpl impl = lazyNatives.get(signature);
        if (impl == null)
        {
            throw new RuntimeException("No lazy native implementation for: " + signature);
        }
        try
        {
            return _E_ValueSpecification.wrap(impl.apply(fe, evaluator), fe._genericType(), fe._multiplicity());
        }
        catch (PureAssertionError e)
        {
            if (!e.getMessage().contains("\nPure stack trace:"))
            {
                throw new PureAssertionError(e.getMessage() + evaluator.getCallStackTrace());
            }
            throw e;
        }
    }

    /**
     * Execute a native function identified by its full mangled signature.
     * Wraps the result into a {@link ValueSpecification} using the compiler-declared
     * return type ({@code fe._genericType()}) and multiplicity ({@code fe._multiplicity()}).
     * If the native already returns a {@link ValueSpecification}, it is passed through.
     */
    public ValueSpecification execute(String signature, List<ValueSpecification> args,
                                      ValueSpecificationEvaluator evaluator, FunctionExpression fe)
    {
        NativeImpl impl = natives.get(signature);
        if (impl == null)
        {
            throw new RuntimeException("No native implementation for: " + signature);
        }
        try
        {
            return _E_ValueSpecification.wrap(impl.apply(args, evaluator, fe), fe._genericType(), fe._multiplicity());
        }
        catch (PureAssertionError e)
        {
            // Append Pure call stack to assertion errors (only if not already present)
            if (!e.getMessage().contains("\nPure stack trace:"))
            {
                throw new PureAssertionError(e.getMessage() + evaluator.getCallStackTrace());
            }
            throw e;
        }
    }

    private void registerDefaults()
    {
        StringNatives.register(natives, lazyNatives, resolver);
        MathNatives.register(natives, lazyNatives, resolver);
        BooleanNatives.register(natives, lazyNatives, resolver);
        LangNatives.register(natives, lazyNatives, resolver);
        AssertNatives.register(natives, lazyNatives, resolver);
        CollectionNatives.register(natives, lazyNatives, resolver);
        MetaNatives.register(natives, lazyNatives, resolver);
        ElementPathNatives.register(natives, lazyNatives, resolver);
        IONatives.register(natives, lazyNatives, resolver);
    }

    // =========================================================================
    // Shared utilities used by handler classes
    // =========================================================================

    /**
     * Pure-semantics equality: PackageableElements are compared by path,
     * DynamicInstances by class+values, Lists element-wise.
     */
    public static boolean pureEquals(Object a, Object b)
    {
        if (Objects.equals(a, b))
        {
            return true;
        }
        if (a == null || b == null)
        {
            return false;
        }

        // Normalize single-element List to its element for comparison
        if (a instanceof List<?> listA && listA.size() == 1 && !(b instanceof List<?>))
        {
            return pureEquals(listA.get(0), b);
        }
        if (b instanceof List<?> listB && listB.size() == 1 && !(a instanceof List<?>))
        {
            return pureEquals(a, listB.get(0));
        }

        // Enum values — compare by name AND owning enumeration
        if (a instanceof meta.pure.metamodel.type.Enum enumA && b instanceof meta.pure.metamodel.type.Enum enumB)
        {
            if (!Objects.equals(enumA._name(), enumB._name()))
            {
                return false;
            }
            meta.pure.metamodel.type.generics.GenericType gtA = enumA._classifierGenericType();
            meta.pure.metamodel.type.generics.GenericType gtB = enumB._classifierGenericType();
            if (gtA != null && gtB != null)
            {
                meta.pure.metamodel.type.Type typeA = _GenericType.type(gtA);
                meta.pure.metamodel.type.Type typeB = _GenericType.type(gtB);
                if (typeA instanceof PackageableElement peA && typeB instanceof PackageableElement peB)
                {
                    return Objects.equals(
                            org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(peA),
                            org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(peB));
                }
            }
            return true;
        }

        // GenericType — compare by their contained type path
        if (a instanceof meta.pure.metamodel.type.generics.GenericType gtA
                && b instanceof meta.pure.metamodel.type.generics.GenericType gtB)
        {
            meta.pure.metamodel.type.Type typeA = _GenericType.type(gtA);
            meta.pure.metamodel.type.Type typeB = _GenericType.type(gtB);
            if (typeA == null && typeB == null)
            {
                return true;
            }
            if (typeA == null || typeB == null)
            {
                return false;
            }
            return pureEquals(typeA, typeB);
        }

        // GenericTypeAndMultiplicityHolder — compare by their held GenericType
        if (a instanceof GenericTypeAndMultiplicityHolder gthA
                && b instanceof GenericTypeAndMultiplicityHolder gthB)
        {
            meta.pure.metamodel.type.generics.GenericType cgA = gthA._genericType();
            meta.pure.metamodel.type.generics.GenericType cgB = gthB._genericType();
            if (cgA == null && cgB == null)
            {
                return true;
            }
            if (cgA == null || cgB == null)
            {
                return false;
            }
            var taA = _GenericType.typeArguments(cgA);
            var taB = _GenericType.typeArguments(cgB);
            if (taA != null && !taA.isEmpty() && taB != null && !taB.isEmpty())
            {
                return pureEquals(taA.getFirst(), taB.getFirst());
            }
            return pureEquals(cgA, cgB);
        }

        // PackageableElement — compare by path
        if (a instanceof PackageableElement peA && b instanceof PackageableElement peB)
        {
            String pathA = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(peA);
            String pathB = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(peB);
            return Objects.equals(pathA, pathB);
        }

        // DynamicInstance — compare by class + values
        if (a instanceof DynamicInstance diA && b instanceof DynamicInstance diB)
        {
            return Objects.equals(diA.getClassPath(), diB.getClassPath())
                    && Objects.equals(diA.getValues(), diB.getValues());
        }

        // List — element-wise
        if (a instanceof List<?> listA && b instanceof List<?> listB)
        {
            if (listA.size() != listB.size())
            {
                return false;
            }
            for (int i = 0; i < listA.size(); i++)
            {
                if (!pureEquals(listA.get(i), listB.get(i)))
                {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Format a Pure value for human-readable display in assertion messages.
     */
    public static String pureToString(Object obj)
    {
        if (obj == null)
        {
            return "null";
        }
        if (obj instanceof PackageableElement pe)
        {
            return org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
        }
        if (obj instanceof meta.pure.metamodel.type.Enum e)
        {
            return e._name();
        }
        if (obj instanceof DynamicInstance di && di.getClassifierGenericType() != null)
        {
            return di.getClassPath() + di.getValues();
        }
        if (obj instanceof List<?> list)
        {
            return "[" + list.stream().map(NativeRepository::pureToString).reduce((a, b) -> a + ", " + b).orElse("") + "]";
        }
        return String.valueOf(obj);
    }
}
