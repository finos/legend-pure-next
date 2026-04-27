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
import org.finos.legend.pure.execution.natives.meta.CompilerNatives;
import org.finos.legend.pure.next.parser.ParserExtension;
import org.finos.legend.pure.execution.natives.io.IONatives;
import org.finos.legend.pure.execution.natives.lang.AssertNatives;
import org.finos.legend.pure.execution.natives.lang.LangNatives;
import org.finos.legend.pure.execution.natives.math.MathNatives;
import org.finos.legend.pure.execution.natives.meta.ElementPathNatives;
import org.finos.legend.pure.execution.natives.meta.MetaNatives;
import org.finos.legend.pure.execution.natives.string.StringNatives;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;

import java.util.ArrayList;
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
     * Each implementation receives the resolved return {@code genericType} and {@code multiplicity}
     * from the caller. In the normal call path these come from the call-site
     * {@link FunctionExpression}; when invoked as a first-class value they come from
     * the {@link meta.pure.metamodel.function.NativeFunction}'s declared return type.
     * The {@link #execute} method wraps raw values using these.
     * Natives that return a {@code ValueSpecification} directly will be passed through unchanged.
     */
    @FunctionalInterface
    public interface NativeImpl
    {
        ValueSpecification apply(List<ValueSpecification> args, ValueSpecificationEvaluator eval,
                                 meta.pure.metamodel.type.generics.GenericType genericType,
                                 meta.pure.metamodel.multiplicity.Multiplicity multiplicity);
    }

    /**
     * A lazy native function implementation that receives the unevaluated
     * {@link FunctionExpression} and controls parameter evaluation itself.
     * Used for short-circuit operators (and, or) and other special forms.
     */
    @FunctionalInterface
    public interface LazyNativeImpl
    {
        ValueSpecification apply(FunctionExpression fe, ValueSpecificationEvaluator eval);
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
        this(resolver, null, List.of());
    }

    private NativeRepository(MetadataAccess resolver, Iterable<? extends NativeExtension> extensions, List<? extends ParserExtension> parserExtensions)
    {
        this.resolver = resolver;
        registerDefaults(parserExtensions);
        if (extensions != null)
        {
            extensions.forEach(ext -> ext.register(natives, lazyNatives, resolver));
        }
    }

    public static class Builder
    {
        private MetadataAccess resolver;
        private final List<NativeExtension> nativeExtensions = new ArrayList<>();
        private final List<ParserExtension> parserExtensions = new ArrayList<>();

        public Builder withResolver(MetadataAccess resolver)
        {
            this.resolver = resolver;
            return this;
        }

        public Builder withNativeExtensions(Iterable<? extends NativeExtension> extensions)
        {
            if (extensions != null)
            {
                extensions.forEach(this.nativeExtensions::add);
            }
            return this;
        }

        public Builder withParserExtensions(Iterable<? extends ParserExtension> extensions)
        {
            if (extensions != null)
            {
                extensions.forEach(this.parserExtensions::add);
            }
            return this;
        }

        public NativeRepository build()
        {
            return new NativeRepository(resolver, nativeExtensions, parserExtensions);
        }
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public MetadataAccess resolver()
    {
        return resolver;
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
            return impl.apply(fe, evaluator);
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
     * Wraps the result into a {@link ValueSpecification} using the provided
     * {@code genericType} and {@code multiplicity}.
     */
    public ValueSpecification execute(String signature, List<ValueSpecification> args,
                                      ValueSpecificationEvaluator evaluator,
                                      meta.pure.metamodel.type.generics.GenericType genericType,
                                      meta.pure.metamodel.multiplicity.Multiplicity multiplicity)
    {
        NativeImpl impl = natives.get(signature);
        if (impl == null)
        {
            throw new RuntimeException("No native implementation for: " + signature);
        }
        try
        {
            return impl.apply(args, evaluator, genericType, multiplicity);
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

    private void registerDefaults(List<? extends ParserExtension> parserExtensions)
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
        org.finos.legend.pure.execution.natives.io.FileSystemNatives.register(natives, lazyNatives, resolver);
        org.finos.legend.pure.execution.natives.date.DateNatives.register(natives, lazyNatives, resolver);
        new CompilerNatives(parserExtensions).register(natives, lazyNatives, resolver);
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
        // Unwrap ValueSpecification wrappers before comparison
        if (a instanceof meta.pure.metamodel.valuespecification.ValueSpecification vsA)
        {
            a = _E_ValueSpecification.unwrap(vsA);
        }
        if (b instanceof meta.pure.metamodel.valuespecification.ValueSpecification vsB)
        {
            b = _E_ValueSpecification.unwrap(vsB);
        }

        if (Objects.equals(a, b))
        {
            return true;
        }
        if (a == null && b instanceof List<?> listB && listB.isEmpty())
        {
            return true;
        }
        if (b == null && a instanceof List<?> listA && listA.isEmpty())
        {
            return true;
        }
        if (a == null || b == null)
        {
            return false;
        }

        // Numeric equality: handles -0.0 vs 0.0 and cross-type precision (Long vs Decimal)
        if (a instanceof Number na && b instanceof Number nb)
        {
            java.math.BigDecimal bdA = na instanceof java.math.BigDecimal ? (java.math.BigDecimal) na : new java.math.BigDecimal(na.toString());
            java.math.BigDecimal bdB = nb instanceof java.math.BigDecimal ? (java.math.BigDecimal) nb : new java.math.BigDecimal(nb.toString());
            return bdA.compareTo(bdB) == 0;
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


        // Pure Enum values — compare by name AND owning enumeration
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

        // DynamicInstance — compare by class + equality key properties
        if (a instanceof DynamicInstance diA && b instanceof DynamicInstance diB)
        {
            if (!Objects.equals(diA.getClassPath(), diB.getClassPath()))
            {
                return false;
            }
            // Find equality key properties from the class's stereotypes
            meta.pure.metamodel.type.generics.GenericType cgtA = diA.getClassifierGenericType();
            if (cgtA != null)
            {
                meta.pure.metamodel.type.Type type = _GenericType.type(cgtA);
                if (type instanceof meta.pure.metamodel.SimplePropertyOwner spo)
                {
                    java.util.List<String> keyProps = collectEqualityKeyProperties(spo);
                    if (!keyProps.isEmpty())
                    {
                        // Compare only equality key properties
                        for (String prop : keyProps)
                        {
                            if (!pureEquals(diA.get(prop), diB.get(prop)))
                            {
                                return false;
                            }
                        }
                        return true;
                    }
                }
            }
            return false;
        }

        // PureMap — delegate to PureMap.equals which uses structural equality
        if (a instanceof PureMap && b instanceof PureMap)
        {
            return a.equals(b);
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
     * Collect property names with the {@code <<equality.Key>>} stereotype
     * from the given type and its supertypes.
     * <p>
     * If a subclass redefines a property that a superclass marked with
     * {@code <<equality.Key>>}, the subclass's definition takes precedence.
     * This means a property overridden WITHOUT the stereotype will NOT
     * be treated as an equality key.
     */
    private static java.util.List<String> collectEqualityKeyProperties(meta.pure.metamodel.SimplePropertyOwner owner)
    {
        java.util.List<String> keys = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        collectEqualityKeyPropertiesRecursive(owner, keys, seen);
        return keys;
    }

    private static void collectEqualityKeyPropertiesRecursive(meta.pure.metamodel.SimplePropertyOwner owner, java.util.List<String> keys, java.util.Set<String> seen)
    {
        if (owner._properties() != null)
        {
            for (meta.pure.metamodel.function.property.Property p : owner._properties())
            {
                String propName = p._name();
                if (seen.contains(propName))
                {
                    // Already defined by a subclass — subclass definition takes precedence
                    continue;
                }
                seen.add(propName);
                if (p._stereotypes() != null)
                {
                    for (meta.pure.metamodel.extension.Stereotype st : p._stereotypes())
                    {
                        if ("Key".equals(st._value())
                                && st._profile() instanceof PackageableElement pe
                                && "meta::pure::profiles::equality".equals(_PackageableElement.path(pe)))
                        {
                            keys.add(propName);
                            break;
                        }
                    }
                }
            }
        }
        // Traverse supertypes
        if (owner instanceof meta.pure.metamodel.type.Type type && type._generalizations() != null)
        {
            for (meta.pure.metamodel.relationship.Generalization gen : type._generalizations())
            {
                if (gen._general() != null && _GenericType.type(gen._general()) instanceof meta.pure.metamodel.SimplePropertyOwner superOwner)
                {
                    collectEqualityKeyPropertiesRecursive(superOwner, keys, seen);
                }
            }
        }
    }

    /**
     * Format a Pure value for human-readable display in assertion messages.
     */
    public static String pureToString(Object obj)
    {
        // Unwrap ValueSpecification wrappers before rendering — symmetric with pureEquals
        if (obj instanceof meta.pure.metamodel.valuespecification.ValueSpecification vs)
        {
            obj = _E_ValueSpecification.unwrap(vs);
        }
        if (obj == null)
        {
            return "null";
        }
        if (obj instanceof PureMap pm)
        {
            return pm.toString();
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
            // Render as classPath{prop=value,...}
            StringBuilder sb = new StringBuilder(di.getClassPath());
            sb.append('{');
            di.getValues().forEach((k, vs) ->
                    sb.append(k).append('=').append(pureToString(_E_ValueSpecification.unwrap(vs))).append(','));
            if (sb.charAt(sb.length() - 1) == ',')
            {
                sb.setCharAt(sb.length() - 1, '}');
            }
            else
            {
                sb.append('}');
            }
            return sb.toString();
        }
        if (obj instanceof List<?> list)
        {
            return "[" + list.stream().map(NativeRepository::pureToString).reduce((a, b) -> a + ", " + b).orElse("") + "]";
        }
        return String.valueOf(obj);
    }
}
