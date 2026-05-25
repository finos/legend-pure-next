// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.truffle.ast.natives.io;

import com.oracle.truffle.api.CompilerDirectives;
import org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject;
import org.finos.legend.pure.truffle.types.PureSequence;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Truffle-side analog of bootstrap's {@code PureValuePrinter}: format a Pure
 * runtime value (primitive, {@link String}, {@link PureSequence}, or
 * {@link PureDynamicObject}) into the same human-visible shape Java-direct's
 * {@code println} produces, so cross-backend tests can assert on identical
 * output strings.
 *
 * <h3>Top-level vs nested</h3>
 * <ul>
 *   <li>{@link #printForOutput(Object)} — invoked by {@code println}/{@code print}.
 *       Strings render without surrounding quotes; sequences are
 *       newline-separated with no opening header.</li>
 *   <li>{@link #print(Object)} — used recursively / as the structural form
 *       (strings quoted, sequences indented).</li>
 * </ul>
 *
 * <h3>Object walk</h3>
 * For a {@link PureDynamicObject} we print:
 * <pre>
 *   ClassName [optional 'name']
 *     prop1: ...
 *     prop2: ...
 * </pre>
 * Slots in {@link #BOILERPLATE_SLOTS} (classifier-generic-type, element
 * override, source information) are skipped — they're metadata the user
 * doesn't want printed every time. Pointer-shaped property values (other
 * PackageableElements) are rendered as their qualified path to break cycles
 * and keep output tractable.
 */
public final class TruffleValuePrinter
{
    private static final String INDENT = "  ";

    /**
     * Hard cap on object-graph recursion depth. Cycle detection via
     * {@code visited} catches back-edges but a long chain of distinct
     * objects (e.g. a deeply nested AST) can still blow the stack — this
     * is the structural safety net. Mirrors the bootstrap printer's cap so
     * cross-backend output stays consistent up to that limit.
     */
    private static final int MAX_DEPTH = 50;

    private static final Set<String> BOILERPLATE_SLOTS = Set.of(
            "classifierGenericType",
            "elementOverride",
            "sourceInformation");

    private TruffleValuePrinter() {}

    @CompilerDirectives.TruffleBoundary
    public static String printForOutput(Object value)
    {
        if (value == null) { return ""; }
        if (value instanceof String s) { return s; }
        if (value instanceof PureSequence seq)
        {
            if (seq.isEmpty()) { return ""; }
            StringBuilder sb = new StringBuilder();
            for (int i = 0, n = seq.size(); i < n; i++)
            {
                if (i > 0) { sb.append('\n'); }
                sb.append(printForOutput(seq.getBoxed(i)));
            }
            return sb.toString();
        }
        return print(value);
    }

    @CompilerDirectives.TruffleBoundary
    public static String print(Object value)
    {
        StringBuilder sb = new StringBuilder();
        append(value, sb, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
        return sb.toString();
    }

    private static void append(Object value, StringBuilder sb, int indent, Set<Object> visited)
    {
        if (indent > MAX_DEPTH)
        {
            sb.append("<max-depth>");
            return;
        }
        if (value == null)
        {
            sb.append("null");
        }
        else if (value instanceof PureSequence seq)
        {
            appendSequence(seq, sb, indent, visited);
        }
        else if (value instanceof PureDynamicObject pdo)
        {
            appendDynamic(pdo, sb, indent, visited);
        }
        else if (value instanceof String s)
        {
            sb.append('\'').append(s).append('\'');
        }
        else
        {
            sb.append(value);
        }
    }

    private static void appendSequence(PureSequence seq, StringBuilder sb, int indent, Set<Object> visited)
    {
        if (seq.isEmpty())
        {
            sb.append("[]");
            return;
        }
        for (int i = 0, n = seq.size(); i < n; i++)
        {
            sb.append('\n').append(INDENT.repeat(indent + 1));
            append(seq.getBoxed(i), sb, indent + 1, visited);
        }
    }

    private static void appendDynamic(PureDynamicObject pdo, StringBuilder sb, int indent, Set<Object> visited)
    {
        if (!visited.add(pdo))
        {
            sb.append("<cycle: ").append(shortName(pdo.classInfo.purePath)).append('>');
            return;
        }
        sb.append(metaclassName(pdo));
        String name = readName(pdo);
        if (name != null)
        {
            sb.append(' ').append(name);
        }
        String[] names = pdo.classInfo.nameBySlot();
        for (int slot = 0; slot < names.length; slot++)
        {
            String propName = names[slot];
            if (propName == null || BOILERPLATE_SLOTS.contains(propName))
            {
                continue;
            }
            Object value = pdo.readSlot(slot);
            if (value == null) { continue; }
            if (value instanceof PureSequence ps && ps.isEmpty()) { continue; }
            sb.append('\n').append(INDENT.repeat(indent + 1)).append(propName).append(": ");
            if (isPackageableElementReference(value))
            {
                appendPointer(value, sb);
            }
            else
            {
                append(value, sb, indent + 1, visited);
            }
        }
    }

    /**
     * Detect cross-references to other PackageableElements (Class, Function,
     * Type, etc.) and render them as their qualified path rather than walking
     * the whole graph — same heuristic Java-direct uses via {@code @Pointer}.
     * Without this, printing {@code A} (a Class) would recurse into A's
     * {@code generalizations}, those into {@code Any}, those into their own
     * generalizations, and so on — the deep print is rarely what the user
     * wanted to see.
     */
    private static boolean isPackageableElementReference(Object value)
    {
        if (value instanceof PureDynamicObject pdo)
        {
            return hasSlot(pdo, "package") && hasSlot(pdo, "name");
        }
        if (value instanceof PureSequence seq && !seq.isEmpty())
        {
            Object head = seq.getBoxed(0);
            return head instanceof PureDynamicObject p
                    && hasSlot(p, "package") && hasSlot(p, "name");
        }
        return false;
    }

    private static boolean hasSlot(PureDynamicObject pdo, String name)
    {
        return pdo.classInfo.slotIndex(name) >= 0;
    }

    private static void appendPointer(Object value, StringBuilder sb)
    {
        if (value instanceof PureSequence seq)
        {
            if (seq.isEmpty()) { sb.append("[]"); return; }
            if (seq.size() == 1)
            {
                sb.append(qualifiedPath(seq.getBoxed(0)));
                return;
            }
            sb.append('[');
            for (int i = 0, n = seq.size(); i < n; i++)
            {
                if (i > 0) { sb.append(", "); }
                sb.append(qualifiedPath(seq.getBoxed(i)));
            }
            sb.append(']');
        }
        else
        {
            sb.append(qualifiedPath(value));
        }
    }

    private static String qualifiedPath(Object value)
    {
        if (!(value instanceof PureDynamicObject pdo)) { return String.valueOf(value); }
        Object pkg = pdo.readProperty("package");
        Object name = pdo.readProperty("name");
        String simpleName = unwrapString(name);
        String pkgPath = walkPackage(pkg);
        if (pkgPath == null || pkgPath.isEmpty() || "::".equals(pkgPath))
        {
            return simpleName == null ? "?" : simpleName;
        }
        return pkgPath + "::" + (simpleName == null ? "?" : simpleName);
    }

    private static String walkPackage(Object pkg)
    {
        if (pkg == null) { return null; }
        if (pkg instanceof PureSequence ps)
        {
            if (ps.size() != 1) { return null; }
            return walkPackage(ps.getBoxed(0));
        }
        if (!(pkg instanceof PureDynamicObject p)) { return null; }
        String name = unwrapString(p.readProperty("name"));
        if (name == null || "::".equals(name))
        {
            return null;
        }
        String parent = walkPackage(p.readProperty("package"));
        return parent == null ? name : parent + "::" + name;
    }

    /**
     * Return the {@code name} value to show after the type header — but only
     * for PackageableElement-shaped PDOs (those with both {@code package} and
     * {@code name} slots). For ordinary user classes that happen to declare a
     * {@code name} property, we leave the header bare so the slot still shows
     * up as a regular property line and isn't duplicated.
     */
    private static String readName(PureDynamicObject pdo)
    {
        if (pdo.classInfo.slotIndex("name") < 0
                || pdo.classInfo.slotIndex("package") < 0)
        {
            return null;
        }
        return unwrapString(pdo.readProperty("name"));
    }

    private static String unwrapString(Object v)
    {
        if (v == null) { return null; }
        if (v instanceof PureSequence seq)
        {
            if (seq.size() != 1) { return null; }
            return unwrapString(seq.getBoxed(0));
        }
        return v.toString();
    }

    private static String metaclassName(PureDynamicObject pdo)
    {
        return shortName(pdo.classInfo.purePath);
    }

    private static String shortName(String purePath)
    {
        if (purePath == null) { return "?"; }
        int idx = purePath.lastIndexOf("::");
        return idx >= 0 ? purePath.substring(idx + 2) : purePath;
    }
}
