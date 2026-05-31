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

import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;
import org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject;
import org.finos.legend.pure.truffle.runtime.dynobj.PureObj;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tree-pretty-printer for {@link Node} trees, tuned for the Truffle gallery.
 *
 * <p>Strips Truffle infrastructure noise that {@link
 * com.oracle.truffle.api.nodes.NodeUtil#printTreeToString} emits — frame
 * descriptors, locks, polyglot refs, identity hashes, cached-fields that
 * are null at the pre-execution snapshot the gallery captures. What
 * survives: short class names, labeled {@code @Child} edges (the actual
 * AST shape), key data fields (literal values, specialization signatures,
 * bound slot indices), and source line+column.</p>
 *
 * <p>Reflection-based (Truffle's {@code NodeUtil.getNodeFields} isn't part
 * of the public API). Walks declared fields up to {@link Node}; classifies
 * each as a child (via {@link Node.Child}/{@link Node.Children} annotation)
 * or as data, applying name- and type-based noise filters. Reflection
 * failures (e.g. JPMS-blocked fields on Truffle internals) are swallowed
 * silently — those fields would have been noise anyway.</p>
 */
final class TruffleAstPrinter
{
    private TruffleAstPrinter() {}

    // Field names that are always Truffle bookkeeping — never carry
    // gallery-relevant signal regardless of which Node subclass declares them.
    private static final Set<String> ALWAYS_SKIP_FIELDS = Set.of(
            "polyglotRef", "frameDescriptor", "lock", "instrumentationBits",
            "callTarget", "parent", "pureSourceSection", "sourceCharIndex",
            "sourceLength",
            // Pre-execution caches that are populated lazily by the
            // interpreter; null until the lambda is first called. The gallery
            // dumps at AST-build time, so these are always null and pure noise.
            "cachedContext", "cachedTarget", "cachedLambdaIdentity",
            "directCallNode", "indirectCallNode",
            // Duplicate of `value` on every {Long,Double,Boolean}ConstantNode
            // (one is the primitive, the other the box of the same number).
            "primitive");

    static String print(Node root)
    {
        StringBuilder sb = new StringBuilder();
        dump(sb, root, 0, "");
        return sb.toString();
    }

    private static void dump(StringBuilder sb, Node node, int depth, String label)
    {
        for (int i = 0; i < depth; i++) sb.append("  ");
        if (!label.isEmpty()) sb.append(label).append(" = ");
        sb.append(shortName(node));

        List<Field> childFields = new ArrayList<>();
        Map<String, Object> childrenArrayFields = new LinkedHashMap<>();
        StringBuilder dataAcc = new StringBuilder();

        // Walk declared fields from concrete class up to (but not including)
        // Node — Node's own fields are infrastructure that we always skip.
        for (Class<?> c = node.getClass(); c != null && c != Node.class && c != Object.class; c = c.getSuperclass())
        {
            for (Field f : c.getDeclaredFields())
            {
                int mods = f.getModifiers();
                if (Modifier.isStatic(mods)) continue;
                String name = f.getName();
                if (ALWAYS_SKIP_FIELDS.contains(name)) continue;

                Object value;
                try
                {
                    f.setAccessible(true);
                    value = f.get(node);
                }
                catch (RuntimeException | IllegalAccessException e)
                {
                    continue;
                }
                if (value == null) continue;

                if (f.isAnnotationPresent(Node.Child.class))
                {
                    childFields.add(f);
                }
                else if (f.isAnnotationPresent(Node.Children.class))
                {
                    childrenArrayFields.put(name, value);
                }
                else
                {
                    appendDataField(dataAcc, name, value);
                }
            }
        }

        if (dataAcc.length() > 0)
        {
            sb.append(' ').append(dataAcc);
        }

        SourceSection ss = node.getSourceSection();
        if (ss != null && ss.isAvailable() && ss.getStartLine() > 0)
        {
            sb.append(" @").append(ss.getSource().getName())
                    .append(':').append(ss.getStartLine())
                    .append(':').append(ss.getStartColumn());
        }
        sb.append('\n');

        for (Field f : childFields)
        {
            try
            {
                Node child = (Node) f.get(node);
                if (child != null) dump(sb, child, depth + 1, f.getName());
            }
            catch (IllegalAccessException ignored) {}
        }
        for (Map.Entry<String, Object> entry : childrenArrayFields.entrySet())
        {
            Object arrObj = entry.getValue();
            if (arrObj instanceof Node[] arr)
            {
                for (int i = 0; i < arr.length; i++)
                {
                    if (arr[i] != null) dump(sb, arr[i], depth + 1, entry.getKey() + "[" + i + "]");
                }
            }
        }
    }

    // Most Truffle nodes have a simple class name that already conveys what
    // they do (e.g. PlusFloatNode, MatchNode, RawLambdaCallNode). Drop the
    // "Node" suffix to tighten the column.
    private static String shortName(Node node)
    {
        String simple = node.getClass().getSimpleName();
        if (simple.endsWith("Node") && simple.length() > "Node".length())
        {
            return simple.substring(0, simple.length() - "Node".length());
        }
        return simple;
    }

    private static void appendDataField(StringBuilder sb, String name, Object value)
    {
        String rendered = renderValue(value);
        if (rendered == null) return;
        // Drop empty-array fields (e.g. paramSlots=[] on a 0-arity lambda) —
        // their absence is the signal; the bracket pair is noise.
        if ("[]".equals(rendered)) return;
        // Drop boundSlot/boundIndex sentinel values: -1 means "not bound at
        // AST-build time" and every gallery dump is at AST-build time.
        if (("boundSlot".equals(name) || "boundIndex".equals(name) || "slot".equals(name))
                && "-1".equals(rendered))
        {
            return;
        }
        if (sb.length() > 0) sb.append(' ');
        sb.append(name).append('=').append(rendered);
    }

    private static String renderValue(Object v)
    {
        if (v == null) return "null";
        if (v instanceof String s) return "\"" + s + "\"";
        if (v instanceof Boolean || v instanceof Number || v instanceof Enum<?>) return v.toString();
        if (v instanceof PureDynamicObject pdo) return renderPdo(pdo);
        if (v instanceof PureSequence seq) return renderSequence(seq);
        if (v instanceof String[] arr) return arrayJoin(arr);
        if (v instanceof int[] arr)
        {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++)
            {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            return sb.append(']').toString();
        }
        if (v instanceof long[] arr)
        {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++)
            {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            return sb.append(']').toString();
        }
        if (v instanceof Object[] arr)
        {
            // Generic Object[] — print classes if entries aren't scalars.
            if (arr.length == 0) return "[]";
            if (arr.length > 8) return "[" + arr.length + " items]";
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++)
            {
                if (i > 0) sb.append(',');
                Object e = arr[i];
                String r = renderValue(e);
                sb.append(r == null ? scalarOrType(e) : r);
            }
            return sb.append(']').toString();
        }
        // PureSourceSection-typed fields handled at the node level.
        // Skip Truffle-internal types — they would render as @hash garbage.
        String pkg = v.getClass().getPackageName();
        if (pkg.startsWith("com.oracle.truffle") || pkg.startsWith("java.util.concurrent"))
        {
            return null;
        }
        // Truffle frame-layout helpers carry zero printable info — the
        // class name alone is uninformative noise, and the inner state
        // (descriptor, slot-index map) is already represented by the
        // RawLambdaRoot's other fields (paramSlots, openVarSlots).
        if ("FrameLayout".equals(v.getClass().getSimpleName())) return null;
        // For runtime closure values (rare) and other host objects, fall back
        // to the class name — never the identity-hash toString.
        return scalarOrType(v);
    }

    private static String scalarOrType(Object v)
    {
        if (v == null) return "null";
        return v.getClass().getSimpleName();
    }

    // Render a Pure metamodel PDO with a human-readable label rather than the
    // useless "PureDynamicObject" class name. Tries, in order:
    //   1. `name` slot — populated on Class, Type, Property, Function, etc.
    //   2. `value` slot — populated on EnumValue and similar singletons.
    //   3. `genericType.rawType.name` — for cast targets, which are typically
    //      `UserDefinedGenericTypeAndMultiplicityHolder` wrappers around a
    //      `GenericType{rawType: <Type>}`. Surfacing the inner type name
    //      turns `value=UserDefinedGenericTypeAndMultiplicityHolder{}` into
    //      `value=Integer` for `->cast(@Integer)`.
    //   4. `rawType.name` — for bare GenericType nodes that aren't holders.
    // For Pure-data PDOs (e.g. a user-defined `Person` instance) all reads
    // fail; we fall back to the classifier type's name so the dump shows
    // e.g. `Person{}` instead of opaque bytes.
    private static String renderPdo(PureDynamicObject pdo)
    {
        String classifier = classifierName(pdo);

        // Holder INSTANCES don't have a real `name` slot — the holder Class
        // is named "UserDefinedGenericTypeAndMultiplicityHolder" (per m3.ttl),
        // and PureObj.read happens to echo that string back on instances that
        // never set their own name. Skip straight to the wrapped type so a
        // `->cast(@String)` target shows as "String" rather than the holder's
        // class name. Two middle-slot names because grammar-built
        // `UserDefinedGenericType` uses `type` while compiled metamodel
        // `GenericType` uses `rawType`.
        boolean isHolder = classifier != null
                && classifier.endsWith("GenericTypeAndMultiplicityHolder");
        if (isHolder)
        {
            String wrapped = readChainedString(pdo, "genericType", "rawType", "name");
            if (wrapped != null) return wrapped;
            wrapped = readChainedString(pdo, "genericType", "type", "name");
            if (wrapped != null) return wrapped;
            return classifier + "{?}";
        }

        // `name` works for Class, Type, Property, Function, Enumeration —
        // the common metamodel PDOs that AtomicValue holds.
        String name = readString(pdo, "name");
        if (name != null) return name;
        // EnumValue + similar singletons.
        String value = readString(pdo, "value");
        if (value != null) return value;
        return classifier != null ? classifier + "{}" : "PureDynamicObject";
    }

    // Read pdo.slot1.slot2...sloN, collapsing 1-element PureSequences at each
    // hop, returning the final value as a non-empty String, or null if any
    // hop yields null/empty.
    private static String readChainedString(Object root, String... slots)
    {
        Object cur = root;
        for (int i = 0; i < slots.length - 1; i++)
        {
            try { cur = PureObj.read(cur, slots[i]); } catch (RuntimeException e) { return null; }
            if (cur instanceof PureSequence seq && seq.size() == 1) cur = seq.getBoxed(0);
            if (cur == null) return null;
        }
        return readString(cur, slots[slots.length - 1]);
    }

    private static String renderSequence(PureSequence seq)
    {
        int n = seq.size();
        if (n == 0) return "[]";
        if (n > 5) return "[" + n + " items]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++)
        {
            if (i > 0) sb.append(',');
            String r = renderValue(seq.getBoxed(i));
            sb.append(r == null ? scalarOrType(seq.getBoxed(i)) : r);
        }
        return sb.append(']').toString();
    }

    private static String readString(Object pdo, String slot)
    {
        try
        {
            Object v = PureObj.read(pdo, slot);
            if (v instanceof String s && !s.isEmpty()) return s;
            // Sometimes the slot holds a 1-element PureSequence of String.
            if (v instanceof PureSequence seq && seq.size() == 1)
            {
                Object first = seq.getBoxed(0);
                if (first instanceof String s && !s.isEmpty()) return s;
            }
        }
        catch (RuntimeException ignored) {}
        return null;
    }

    private static String classifierName(PureDynamicObject pdo)
    {
        try
        {
            Object cgt = PureObj.read(pdo, "classifierGenericType");
            if (cgt instanceof PureSequence seq && seq.size() == 1) cgt = seq.getBoxed(0);
            if (cgt == null) return null;
            Object type = PureObj.read(cgt, "rawType");
            if (type instanceof PureSequence seq && seq.size() == 1) type = seq.getBoxed(0);
            if (type == null) type = PureObj.read(cgt, "type");
            if (type instanceof PureSequence seq && seq.size() == 1) type = seq.getBoxed(0);
            if (type == null) return null;
            return readString(type, "name");
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
    }

    private static String arrayJoin(String[] arr)
    {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++)
        {
            if (i > 0) sb.append(',');
            sb.append(arr[i] == null ? "null" : arr[i]);
        }
        return sb.append(']').toString();
    }
}
