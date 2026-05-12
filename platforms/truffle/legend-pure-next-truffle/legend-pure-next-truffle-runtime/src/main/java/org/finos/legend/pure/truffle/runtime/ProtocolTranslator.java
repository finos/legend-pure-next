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

package org.finos.legend.pure.truffle.runtime;

import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.truffle.types.ObjectSequence;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Translates bootstrap protocol objects (meta.pure.protocol.*)
 * to truffle-namespaced protocol objects (org.finos.legend.pure.truffle.pdb.meta.pure.protocol.*).
 *
 * Both share the same _xxx() getter/setter convention since they're generated
 * from the same Pure model.
 */
public final class ProtocolTranslator
{

    private static final int SLOT_CLASSIFIER_GENERIC_TYPE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("classifierGenericType");
    private static final String BOOTSTRAP_PREFIX = "meta.pure.";
    private static final String TRUFFLE_PREFIX = "org.finos.legend.pure.truffle.pdb.";
    private final TruffleMetadataAccess resolver;

    private static final java.util.Set<String> JAVA_KEYWORDS = java.util.Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while");

    private final Map<Object, Object> seen = new IdentityHashMap<>();

    /**
     * Per-class cache of {@code Class.getMethods()} results. {@code getMethods()}
     * defensively copies the entire methods array on every call (JFR-visible
     * at ~17 samples on the metamodel_factories.pure compile). The result is
     * deterministic per Class, so caching makes the second-and-onwards lookups
     * a single map read.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Method[]> METHODS_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Method[] cachedMethods(Class<?> clazz)
    {
        Method[] cached = METHODS_CACHE.get(clazz);
        if (cached != null)
        {
            return cached;
        }
        Method[] computed = clazz.getMethods();
        METHODS_CACHE.put(clazz, computed);
        return computed;
    }

    public ProtocolTranslator(TruffleMetadataAccess resolver)
    {
        this.resolver = resolver;
    }

    public Object translate(Object source)
    {
        if (source == null)
        {
            return null; // null stays null — setter handles it
        }

        // Primitives — pass through
        if (source instanceof String || source instanceof Long || source instanceof Double
                || source instanceof Boolean || source instanceof Integer
                || source instanceof java.math.BigDecimal || source instanceof Number)
        {
            return source;
        }

        // Date types — pass through
        if (source instanceof java.time.temporal.Temporal)
        {
            return source;
        }

        // Cycle detection
        Object cached = seen.get(source);
        if (cached != null)
        {
            return cached;
        }

        // MutableList — translate elements into PureSequence
        if (source instanceof MutableList<?> list)
        {
            Object[] arr = new Object[list.size()];
            for (int i = 0; i < list.size(); i++)
            {
                arr[i] = translate(list.get(i));
            }
            return new ObjectSequence(arr);
        }

        // PureSequence — translate elements
        if (source instanceof PureSequence seq)
        {
            Object[] arr = seq.toBoxedArray();
            Object[] translated = new Object[arr.length];
            for (int i = 0; i < arr.length; i++)
            {
                translated[i] = translate(arr[i]);
            }
            return new ObjectSequence(translated);
        }

        // Bootstrap protocol object — translate to truffle
        String className = source.getClass().getName();
        if (className.startsWith(BOOTSTRAP_PREFIX))
        {
            return translateProtocolObject(source, className);
        }

        // Already a truffle type or unknown — pass through
        return source;
    }

    private Object translateProtocolObject(Object source, String className)
    {
        // Derive the truffle Pure-form path for the source:
        //   meta.pure.protocol.grammar.relationship.AssociationImpl
        //   → meta::pure::protocol::grammar::relationship::Association
        String purePath = derivePurePath(className);
        // Existence check: is the Pure type known to the resolver? This
        // doesn't depend on a generated XImpl — user/test/compiler/protocol
        // classes that have no codegen still resolve via their Class
        // definition stored in the PDB. The previous `Class.forName` check
        // returned false for any Pure type without an XImpl and silently
        // passed the M3 source through unchanged, breaking the compile
        // (which then operated on M3 protocol objects rather than truffle
        // PDOs and produced empty CompilationResults).
        if (resolver == null || resolver.getElement(purePath) == null)
        {
            return source;
        }

        // Create the target as a PDO directly (no typed XImpl construction,
        // no reflective setter dispatch). PureObj.write does the same
        // PropertyCoercion the typed setter did for [*] / scalar wrap, but
        // through the PDO Shape's sharedData. After this migration the
        // generated XImpl's writeProperty switch is no longer reachable
        // through ProtocolTranslator.
        Object target = TruffleInstanceFactory.createInstance(purePath, resolver);
        seen.put(source, target);

        for (Method getter : cachedMethods(source.getClass()))
        {
            String name = getter.getName();
            if (!name.startsWith("_") || name.equals("_copy") || getter.getParameterCount() != 0
                    || getter.getReturnType() == void.class)
            {
                continue;
            }
            try
            {
                Object value = getter.invoke(source);
                if (value == null)
                {
                    continue; // null optional property — skip
                }
                Object translated = translate(value);
                if (translated == null)
                {
                    throw new RuntimeException("translate() returned null for non-null property '" + name + "' on " + className
                            + " (value type: " + value.getClass().getName() + ")");
                }
                org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(target, name.substring(1), translated);
            }
            catch (Exception e)
            {
                throw new RuntimeException("Failed to translate property '" + name + "' on " + source.getClass().getName(), e);
            }
        }

        // Set classifierGenericType so match/instanceOf works
        setClassifierGenericType(target, className);
        return target;
    }

    private static String derivePurePath(String bootstrapClassName)
    {
        String p = bootstrapClassName;
        if (p.endsWith("Impl"))
        {
            p = p.substring(0, p.length() - 4);
        }
        return String.join("::", p.split("\\."));
    }

    private void setClassifierGenericType(Object target, String bootstrapClassName)
    {
        if (resolver == null || target == null)
        {
            return;
        }
        // Already has CGT (e.g. copied from source) — don't overwrite
        if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(target, SLOT_CLASSIFIER_GENERIC_TYPE) != null)
        {
            return;
        }
        String purePath = derivePurePath(bootstrapClassName);

        Object classType = resolver.getElement(purePath);
        if (classType != null)
        {
            org.finos.legend.pure.truffle.runtime.dynobj.PureObj.write(target, "classifierGenericType",
                    org.finos.legend.pure.truffle.runtime.helper._GenericType.buildUserDefinedGenericType(classType, resolver));
        }
        else
        {
            throw new RuntimeException("[ProtocolTranslator] Cannot resolve classifierGenericType for "
                    + bootstrapClassName + " (purePath=" + purePath + ")");
        }
    }

    private static String escapeJavaKeywords(String dottedPath)
    {
        String[] parts = dottedPath.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++)
        {
            if (i > 0)
            {
                sb.append('.');
            }
            sb.append(JAVA_KEYWORDS.contains(parts[i]) ? parts[i] + "_" : parts[i]);
        }
        return sb.toString();
    }
}
