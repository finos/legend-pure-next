// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.execution.natives.meta;

import meta.pure.protocol.PureFile;
import org.finos.legend.pure.execution.DynamicInstance;
import org.finos.legend.pure.execution.NativeExtension;
import org.finos.legend.pure.execution.NativeRepository.LazyNativeImpl;
import org.finos.legend.pure.execution.NativeRepository.NativeImpl;
import org.finos.legend.pure.execution.ProtocolToDynamicInstance;
import org.finos.legend.pure.execution._E_ValueSpecification;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.metadata.CompositePureLanguageMetadata;
import org.finos.legend.pure.m3.pureLanguage.metadata.PureLanguageMetadata;
import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.FunctionIndexEntry;
import org.finos.legend.pure.next.parser.ParserExtension;
import org.finos.legend.pure.next.parser.PureParser;
import org.finos.legend.pure.next.parser.pureLanguage.PureLanguageParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CompilerNatives implements NativeExtension
{
    private final List<ParserExtension> extraExtensions;

    public CompilerNatives()
    {
        this(List.of());
    }

    public CompilerNatives(List<? extends ParserExtension> extraExtensions)
    {
        this.extraExtensions = List.copyOf(extraExtensions);
    }

    @Override
    public void register(Map<String, NativeImpl> natives,
                         Map<String, LazyNativeImpl> lazyNatives,
                         MetadataAccess resolver)
    {
        // meta::pure::functions::meta::parse(sourceId:String[1], content:String[1]):PureFile[1]
        List<ParserExtension> extensions = new ArrayList<>();
        extensions.add(new PureLanguageParser());
        extensions.addAll(this.extraExtensions);
        PureParser parser = PureParser.builder()
                .withExtensions(extensions)
                .build();

        natives.put("parse_String_1__String_1__PureFile_1_", (args, eval, genericType, multiplicity) ->
        {
            String sourceId = (String) _E_ValueSpecification.unwrap(args.get(0));
            String content = (String) _E_ValueSpecification.unwrap(args.get(1));

            PureFile pureFile = parser.parse(sourceId, content);

            ProtocolToDynamicInstance translator = new ProtocolToDynamicInstance(resolver, obj ->
            {
                for (ParserExtension ext : extensions)
                {
                    String resolved = ext.resolveType(obj);
                    if (resolved != null)
                    {
                        return resolved;
                    }
                }
                return ProtocolToDynamicInstance.defaultTypeResolutionStrategy(obj);
            });
            Object resultValue = translator.convert(pureFile);

            return _E_ValueSpecification.wrap(resultValue, genericType, multiplicity, resolver);
        });

        // meta::pure::functions::meta::compileSource(file:PureFile[1], dependencies:String[*]):CompileSourceResult[1]
        // THE dynamic-evaluation primitive: dispatches into the INTERPRETED
        // Pure compiler (compiler.pdb must be loaded) and returns the compiled
        // elements as SELF-CONTAINED VALUES — mutates no global state (the
        // module registry is immutable after boot); compile diagnostics are
        // DATA on the result, not exceptions. `dependencies` names loaded
        // modules; an unknown name is a caller bug and throws (message pinned
        // by the PCT contract tests).
        natives.put("compileSource_PureFile_1__String_MANY__CompileSourceResult_1_", (args, eval, genericType, multiplicity) ->
        {
            for (meta.pure.metamodel.valuespecification.ValueSpecification depVS
                    : _E_ValueSpecification.toCollection(args.get(1), resolver)._values())
            {
                String dep = String.valueOf(_E_ValueSpecification.unwrap(depVS));
                if (!resolver.moduleNames().contains(dep))
                {
                    throw new RuntimeException("compileSource: dependency module '" + dep + "' is not loaded");
                }
            }
            Object compileFn = resolver.getElement("meta::pure::compiler::compile_PureFile_MANY__CompilationResult_1_");
            if (compileFn == null)
            {
                throw new RuntimeException("compileSource: the compiler module is not loaded");
            }

            DynamicInstance out = new DynamicInstance("meta::pure::functions::meta::CompileSourceResult");
            try
            {
                Object resultVS = eval.executeFunction(
                        _E_ValueSpecification.wrap(compileFn, null, null, resolver),
                        List.of(args.get(0)));
                Object result = _E_ValueSpecification.unwrap(
                        (meta.pure.metamodel.valuespecification.ValueSpecification) resultVS);
                DynamicInstance compResult = (DynamicInstance) result;
                Object errorsObj = compResult.get("errors");
                boolean failed = errorsObj instanceof List<?> errs && !errs.isEmpty();
                out.put("elements", failed ? List.of() : compResult.get("elements"));
                out.put("errors", errorsObj == null ? List.of() : errorsObj);
            }
            catch (RuntimeException e)
            {
                out.put("elements", List.of());
                out.put("errors", List.of(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            return _E_ValueSpecification.wrap(out, genericType, multiplicity, resolver);
        });

        // meta::pure::compiler::findFunctionsByNameAndArity(name:String[1], arity:Integer[1]):PackageableFunction<Any>[*]
        natives.put("findFunctionsByNameAndArity_String_1__Integer_1__PackageableFunction_MANY_", (args, eval, genericType, multiplicity) ->
        {
            String name = (String) _E_ValueSpecification.unwrap(args.get(0));
            long arity = (Long) _E_ValueSpecification.unwrap(args.get(1));
            CompositePureLanguageMetadata fnMetadata = new CompositePureLanguageMetadata(
                    resolver.getMetadataAccessExtension(PureLanguageMetadata.class), resolver);
            org.eclipse.collections.api.list.MutableList<FunctionIndexEntry> entries = fnMetadata.findFunctionHeadersByNameAndArity(name, (int) arity);
            List<meta.pure.metamodel.valuespecification.ValueSpecification> wrapped = new ArrayList<>();
            for (FunctionIndexEntry entry : entries)
            {
                wrapped.add(_E_ValueSpecification.wrap(entry, null, null, resolver));
            }
            return org.finos.legend.pure.execution.natives.collection.CollectionNatives.makeCollection(wrapped, resolver);
        });

        // meta::pure::compiler::findAllTypes():Type[*]
        // Enumerates every Type-typed PackageableElement across all loaded
        // modules. Used by buildLinearizationCache to seed the cache without
        // relying on package-tree traversal (which is split per module).
        natives.put("findAllTypes__Type_MANY_", (args, eval, genericType, multiplicity) ->
        {
            List<meta.pure.metamodel.valuespecification.ValueSpecification> wrapped = new ArrayList<>();
            for (String path : resolver.elementPaths())
            {
                meta.pure.metamodel.PackageableElement element = resolver.getElement(path);
                if (element instanceof meta.pure.metamodel.type.Type)
                {
                    wrapped.add(_E_ValueSpecification.wrap(element, null, null, resolver));
                }
            }
            return org.finos.legend.pure.execution.natives.collection.CollectionNatives.makeCollection(wrapped, resolver);
        });

        // meta::pure::compiler::structural::valueSpecificationCompiler::normalizeDateString(dateStr:String[1]):String[1]
        // Normalizes date literals: zero-pads components and converts timezone offsets to UTC.
        natives.put("normalizeDateString_String_1__String_1_", (args, eval, genericType, multiplicity) ->
        {
            String dateStr = (String) _E_ValueSpecification.unwrap(args.get(0));
            String result = org.finos.legend.pure.execution.natives.string.StringNatives.normalizePureDate(dateStr);
            return _E_ValueSpecification.wrap(result != null ? result : dateStr, genericType, multiplicity, resolver);
        });

        // meta::pure::functions::meta::resolveAndReturnGraph(elements:Map<String, PackageableElement>[1]):PackageableElement[*]
        //
        // Deep-copies every input PE and recursively rewrites every
        // TempCompilerPointer (top-level or nested in any slot) to the live
        // element — looked up first in the input map itself (by `_path()`),
        // then via the cross-module resolver. Input map and PE PDOs are left
        // untouched: each copied PE is built via the XImpl-generated `_copy()`
        // (shallow), and rewritten slot values are written onto the copy via
        // fluent setters. Identity-based cycle tracking keeps the metamodel's
        // back-edges (e.g. PE.package → Package.children → same PE) finite.
        natives.put("resolveAndReturnGraph_Map_1__PackageableElement_MANY_", (args, eval, genericType, multiplicity) ->
        {
            org.finos.legend.pure.execution.PureMap pureMap =
                    (org.finos.legend.pure.execution.PureMap) _E_ValueSpecification.unwrap(args.get(0));
            java.util.Map<String, Object> byPath = new java.util.HashMap<>();
            for (java.util.Map.Entry<meta.pure.metamodel.valuespecification.ValueSpecification,
                                     meta.pure.metamodel.valuespecification.ValueSpecification> e :
                    pureMap.getMap().entrySet())
            {
                Object key = _E_ValueSpecification.unwrap(e.getKey());
                Object val = _E_ValueSpecification.unwrap(e.getValue());
                if (key instanceof String && !(val instanceof meta.pure.metamodel.pointer.TempCompilerPointer))
                {
                    byPath.put((String) key, val);
                }
            }
            java.util.IdentityHashMap<Object, Object> copies = new java.util.IdentityHashMap<>();
            org.eclipse.collections.api.list.MutableList<meta.pure.metamodel.valuespecification.ValueSpecification> wrapped =
                    org.eclipse.collections.api.factory.Lists.mutable.empty();
            for (java.util.Map.Entry<meta.pure.metamodel.valuespecification.ValueSpecification,
                                     meta.pure.metamodel.valuespecification.ValueSpecification> e :
                    pureMap.getMap().entrySet())
            {
                meta.pure.metamodel.valuespecification.ValueSpecification value = e.getValue();
                Object unwrapped = _E_ValueSpecification.unwrap(value);
                Object rewritten = deepCopyAndRewritePointers(unwrapped, byPath, resolver, copies);
                if (rewritten != unwrapped)
                {
                    value = _E_ValueSpecification.wrap(rewritten, null,
                            (meta.pure.metamodel.multiplicity.Multiplicity)
                                    resolver.getElement("meta::pure::metamodel::multiplicity::PureOne"),
                            resolver);
                }
                wrapped.add(value);
            }
            return org.finos.legend.pure.execution.natives.collection.CollectionNatives.makeCollection(wrapped, resolver);
        });
    }

    /**
     * Deep-copy {@code value} and recursively rewrite every
     * {@link meta.pure.metamodel.pointer.TempCompilerPointer} encountered to
     * the live element resolved via {@code byPath} (in-map first) or
     * {@code resolver} (cross-module).
     *
     * <p>Pointers are <i>not</i> copied — they are replaced. Non-pointer PEs
     * are shallow-cloned via the generated {@code _copy()} method; each slot
     * is then re-walked, and any rewritten value is set back onto the copy
     * via its fluent setter. Cycles are tracked by reference identity: a
     * second visit returns the in-progress copy, leaving the back-edge intact.
     * Live targets produced by the resolver are shared (not copied) — they
     * are already the canonical instances and don't contain pointers.</p>
     */
    private static Object deepCopyAndRewritePointers(Object value,
                                                     Map<String, Object> byPath,
                                                     MetadataAccess resolver,
                                                     java.util.IdentityHashMap<Object, Object> copies)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof meta.pure.metamodel.pointer.TempCompilerPointer p)
        {
            String path = p._path();
            Object live = byPath.get(path);
            if (live == null)
            {
                live = resolver.getElement(path);
            }
            return (live != null && !(live instanceof meta.pure.metamodel.pointer.TempCompilerPointer))
                    ? live
                    : value;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character)
        {
            return value;
        }
        if (value instanceof java.util.Collection<?> col)
        {
            org.eclipse.collections.api.list.MutableList<Object> out =
                    org.eclipse.collections.api.factory.Lists.mutable.ofInitialCapacity(col.size());
            boolean anyChange = false;
            for (Object elem : col)
            {
                Object rewritten = deepCopyAndRewritePointers(elem, byPath, resolver, copies);
                if (rewritten != elem)
                {
                    anyChange = true;
                }
                out.add(rewritten);
            }
            return anyChange ? out : value;
        }
        Object cached = copies.get(value);
        if (cached != null)
        {
            return cached;
        }
        // Resolver-indexed dep element that is NOT being (re)compiled: return
        // it AS-IS. These are the live canonical instances (Integer, Any, …)
        // referenced by the compiled output; cloning them breaks identity-based
        // operations downstream (`cast(@Integer)` on a dynamically compiled
        // function's result compared a CLONED Integer against the live one —
        // "Integer cannot be cast to Integer"). Mirrors the Truffle rule in
        // PointerGraphImmutableResolver ("dep-PDB elements must not be cloned").
        if (value instanceof meta.pure.metamodel.PackageableElement pe)
        {
            String path = ElementPathNatives.elementToPathString(pe, "::");
            if (path != null && !path.isEmpty() && !byPath.containsKey(path)
                    && resolver.getElement(path) == value)
            {
                return value;
            }
        }
        try
        {
            java.lang.reflect.Method copyMethod = value.getClass().getMethod("_copy");
            Object copy = copyMethod.invoke(value);
            copies.put(value, copy);
            for (java.lang.reflect.Method getter : getPropertyAccessors(value.getClass()))
            {
                Object slotVal = getter.invoke(value);
                if (slotVal == null)
                {
                    continue;
                }
                Object newSlotVal = deepCopyAndRewritePointers(slotVal, byPath, resolver, copies);
                if (newSlotVal != slotVal)
                {
                    java.lang.reflect.Method setter = findSetter(copy.getClass(), getter.getName(), newSlotVal);
                    if (setter != null)
                    {
                        setter.invoke(copy, newSlotVal);
                    }
                }
            }
            return copy;
        }
        catch (NoSuchMethodException ex)
        {
            // Non-XImpl Java object (e.g. PureMap, PureDate). Leave as-is —
            // these don't contain Pure PEs in slots, so there's nothing to
            // rewrite.
            return value;
        }
        catch (Exception ex)
        {
            // Include the cause chain in the MESSAGE — the Pure-level stack
            // formatter only surfaces messages, so a bare wrapped cause is
            // invisible in test output.
            StringBuilder chain = new StringBuilder();
            for (Throwable t = ex; t != null; t = t.getCause())
            {
                chain.append(" <- ").append(t.getClass().getSimpleName())
                        .append(t.getMessage() != null ? ": " + t.getMessage() : "");
            }
            throw new RuntimeException("resolveAndReturnGraph: deep-copy + rewrite failed on "
                    + value.getClass().getName() + chain, ex);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.List<java.lang.reflect.Method>>
            PROPERTY_ACCESSORS_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static java.util.List<java.lang.reflect.Method> getPropertyAccessors(Class<?> cls)
    {
        return PROPERTY_ACCESSORS_CACHE.computeIfAbsent(cls, c ->
        {
            java.util.List<java.lang.reflect.Method> result = new ArrayList<>();
            for (java.lang.reflect.Method m : c.getMethods())
            {
                String name = m.getName();
                // No-arg `_X` getters only — skip `_copy`, `_classifierGenericType`
                // (carries the PE's own type metadata, not user-facing slots) and
                // `_elementOverride` (compile-pure internal anchor).
                if (name.startsWith("_") && m.getParameterCount() == 0
                        && !name.equals("_copy")
                        && !name.equals("_classifierGenericType")
                        && !name.equals("_elementOverride"))
                {
                    result.add(m);
                }
            }
            return List.copyOf(result);
        });
    }

    private static java.lang.reflect.Method findSetter(Class<?> cls, String name, Object arg)
    {
        java.lang.reflect.Method best = null;
        for (java.lang.reflect.Method m : cls.getMethods())
        {
            if (m.getName().equals(name) && m.getParameterCount() == 1)
            {
                Class<?> paramType = m.getParameterTypes()[0];
                if (arg == null || paramType.isInstance(arg))
                {
                    // Prefer the most specific matching overload.
                    if (best == null || best.getParameterTypes()[0].isAssignableFrom(paramType))
                    {
                        best = m;
                    }
                }
            }
        }
        return best;
    }
}
