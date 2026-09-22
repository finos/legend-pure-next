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

package org.finos.legend.pure.platform.java.pdb;

import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.FbsNode;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.ReadAncestorRef;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.ReadPointerRef;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.ZipEntryInfo;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.unwrapValueDef_FbsNode_1__Any_1_;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.elementNode_Integer_MANY__String_1__FbsNode_1_;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.readField_FbsSchema_1__FbsNode_1__String_1__Any_MANY_;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.readZipEntries_Integer_MANY__ZipEntryInfo_MANY_;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.rootNode_Integer_MANY__String_1__FbsNode_1_;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.reader.zipEntryData_Integer_MANY__ZipEntryInfo_1__Integer_MANY_;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.schema.FbsField;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.schema.FbsSchema;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.schema.parseFbs_String_1__FbsSchema_1_;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.schema.FbsTable;
import org.finos.legend.pure.m3.meta.pure.compiler.pdb.schema.table_FbsSchema_1__String_1__FbsTable_$0_1$_;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Lazy access to the elements of PDB archives, through the self-hosted Pure PDB
 * reader translated to Java. JDK only.
 *
 * <p>Opening an archive only scans its zip directory into a path index; an
 * element is decoded when first requested, and its properties when first read
 * (see {@link LazyObject}). Decoding semantics follow the other platforms:</p>
 * <ul>
 *   <li>a Pure property is stored under its snake_case FlatBuffer field, with
 *   the {@code type_}/{@code namespace_} and {@code p_tags}/{@code p_stereotypes}
 *   exceptions;</li>
 *   <li>an Integer/Float/Boolean/String/Decimal {@code ValueDef} table wraps a scalar;</li>
 *   <li>a PointerRef names another element (kind 0: {@code [path]}, where a path
 *   {@code Owner.member} is a nested element such as an enum value) or a member
 *   of one (kind 1 property, 2 qualified property, 3 stereotype, 4 tag:
 *   {@code [ownerPath, name]});</li>
 *   <li>an AncestorRef of depth n is the object n levels up from the one whose
 *   property is being read.</li>
 * </ul>
 * <p>Archives are searched in the order they were opened. Every node decodes to
 * one Java object, so identity holds across reads.</p>
 */
public final class PdbRuntime
{
    /** Tables wrapping an AtomicValue's scalar payload; other *ValueDef tables (MultiplicityValueDef) are classes. */
    private static final java.util.Set<String> SCALAR_WRAPPERS = java.util.Set.of("IntegerValueDef", "FloatValueDef", "BooleanValueDef", "StringValueDef", "DecimalValueDef");

    private final FbsSchema schema;
    private final Function<String, LazyObject> types;
    private final List<Archive> archives = new ArrayList<>();
    private final Map<String, Object> elements = new HashMap<>();
    private final Map<Object, Map<Long, LazyObject>> nodes = new IdentityHashMap<>();
    private final Map<String, Boolean> typeStoredAs = new HashMap<>();
    private java.util.Set<String> moduleNames;
    private List<Object> allTypes;
    private Map<String, List<String>> functionIndex;

    private PdbRuntime(FbsSchema schema, Function<String, LazyObject> types)
    {
        this.schema = schema;
        this.types = types;
    }

    /** A runtime over the given PDBs, decoding with m3.fbs and the generated implementations ({@code types}). */
    public static PdbRuntime open(Path fbsSchema, Function<String, LazyObject> types, Path... pdbs) throws IOException
    {
        PdbRuntime runtime = new PdbRuntime(parseFbs_String_1__FbsSchema_1_.execute(Files.readString(fbsSchema)), types);
        for (Path pdb : pdbs)
        {
            runtime.archives.add(new Archive(new ByteList(Files.readAllBytes(pdb))));
        }
        return runtime;
    }

    /**
     * The names of the modules this runtime holds — a PDB's manifest names it,
     * and `compileSource` checks its dependencies against these.
     */
    public java.util.Set<String> moduleNames()
    {
        if (moduleNames == null)
        {
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            for (Archive archive : archives)
            {
                String manifest = sectionText(archive, "manifest");
                String name = manifest == null ? null : jsonString(manifest, "name");
                if (name != null)
                {
                    names.add(name);
                }
            }
            moduleNames = names;
        }
        return moduleNames;
    }

    /**
     * `findAllTypes()`: every element stored as a type — Class, Enumeration,
     * PrimitiveType and the rest. Read off each archive's index of what it
     * holds, so nothing is decoded but the types themselves.
     */
    public List<Object> allTypes()
    {
        if (allTypes == null)
        {
            List<Object> types1 = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Archive archive : archives)
            {
                for (Map.Entry<String, String> entry : archive.elementTypes.entrySet())
                {
                    if (isTypeStoredAs(entry.getValue()) && seen.add(entry.getKey()))
                    {
                        Object element = element(entry.getKey());
                        if (element != null)
                        {
                            types1.add(element);
                        }
                    }
                }
            }
            allTypes = types1;
        }
        return allTypes;
    }

    /**
     * `findFunctionsByNameAndArity(name, arity)`: from each PDB's functionIndex,
     * the section written for exactly this lookup (the alternative is decoding
     * every function in every archive).
     */
    public List<Object> functionsByNameAndArity(String name, long arity)
    {
        List<Object> found = new ArrayList<>();
        for (String path : functionIndex().getOrDefault(name + "/" + arity, Collections.emptyList()))
        {
            Object element = element(path);
            if (element != null)
            {
                found.add(element);
            }
        }
        return found;
    }

    /** Every indexed function, keyed by "name/arity". */
    private Map<String, List<String>> functionIndex()
    {
        if (functionIndex == null)
        {
            Map<String, List<String>> index = new HashMap<>();
            for (Archive archive : archives)
            {
                FbsNode section = sectionNode(archive, "functionIndex", "FunctionIndex");
                for (Object entry : section == null ? Collections.emptyList() : field(section, "entries"))
                {
                    indexFunction(index, (FbsNode) entry);
                }
            }
            functionIndex = index;
        }
        return functionIndex;
    }

    private void indexFunction(Map<String, List<String>> index, FbsNode entry)
    {
        List<Object> paths = field(entry, "full_path");
        List<Object> names = field(entry, "function_name");
        List<Object> functionTypes = field(entry, "function_type");
        if (paths.isEmpty() || names.isEmpty())
        {
            return;
        }
        long arity = functionTypes.isEmpty() ? 0 : field((FbsNode) functionTypes.get(0), "parameters").size();
        index.computeIfAbsent(names.get(0) + "/" + arity, k -> new ArrayList<>()).add((String) paths.get(0));
    }

    /** Is an element stored as `storedAs` a Type? Asked once per stored type name. */
    private boolean isTypeStoredAs(String storedAs)
    {
        return typeStoredAs.computeIfAbsent(storedAs, k ->
        {
            // The factory is keyed by the FlatBuffers table, which is the stored
            // type name plus "Def" (as `elementNode` spells it when decoding).
            LazyObject prototype = types.apply(k + "Def");
            Object type = prototype == null ? null : element(prototype._purePath());
            Object typeClass = element("meta::pure::metamodel::type::Type");
            return type != null && typeClass != null && Metadata.isOrSpecializes(type, typeClass);
        });
    }

    private List<Object> field(FbsNode node, String name)
    {
        return readField_FbsSchema_1__FbsNode_1__String_1__Any_MANY_.execute(schema, node, name);
    }

    /** A section of the archive, read as the FlatBuffers table `table`. */
    private FbsNode sectionNode(Archive archive, String section, String table)
    {
        ZipEntryInfo entry = archive.sections.get(section);
        return entry == null
                ? null
                : rootNode_Integer_MANY__String_1__FbsNode_1_.execute(zipEntryData_Integer_MANY__ZipEntryInfo_1__Integer_MANY_.execute(archive.bytes, entry), table);
    }

    private String sectionText(Archive archive, String section)
    {
        ZipEntryInfo entry = archive.sections.get(section);
        if (entry == null)
        {
            return null;
        }
        List<Long> data = zipEntryData_Integer_MANY__ZipEntryInfo_1__Integer_MANY_.execute(archive.bytes, entry);
        byte[] text = new byte[data.size()];
        for (int i = 0; i < data.size(); i++)
        {
            text[i] = (byte) (long) data.get(i);
        }
        return new String(text, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The string value of `key` in a flat JSON object — the manifest is one. */
    private static String jsonString(String json, String key)
    {
        int at = json.indexOf('"' + key + '"');
        int start = at < 0 ? -1 : json.indexOf('"', json.indexOf(':', at) + 1);
        return start < 0 ? null : json.substring(start + 1, json.indexOf('"', start + 1));
    }

    /** True while an element is being decoded (decoding builds objects itself). */
    static final ThreadLocal<Boolean> DECODING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** The element at {@code path} (or a nested element {@code Owner.member}), or null. */
    public Object element(String path)
    {
        boolean outermost = !DECODING.get();
        DECODING.set(Boolean.TRUE);
        try
        {
            return elementInternal(path);
        }
        finally
        {
            if (outermost)
            {
                DECODING.set(Boolean.FALSE);
            }
        }
    }

    private Object elementInternal(String path)
    {
        if (elements.containsKey(path))
        {
            return elements.get(path);
        }
        Object element = null;
        for (Archive archive : archives)
        {
            element = decode(archive, path);
            if (element != null)
            {
                // Its own path, so a Package can merge its children across archives.
                ((LazyObject) element).elementPath = path;
                break;
            }
        }
        if (element == null && path.lastIndexOf('.') > 0)
        {
            int dot = path.lastIndexOf('.');
            element = member(element(path.substring(0, dot)), path.substring(dot + 1), "qualifiedProperties", "properties", "propertiesFromAssociations", "values");
        }
        elements.put(path, element);
        return element;
    }

    /** The element at {@code path} as this archive stores it, or null. */
    private Object decode(Archive archive, String path)
    {
        ZipEntryInfo entry = archive.entries.get(path);
        if (entry == null)
        {
            return null;
        }
        String typeName = ((String) entry.name()).substring(((String) entry.name()).lastIndexOf('.') + 1);
        // Compact: an element's decoded bytes are held for as long as its
        // node is, and a List of boxed Long costs eight times a byte[].
        ByteList data = ByteList.compact(zipEntryData_Integer_MANY__ZipEntryInfo_1__Integer_MANY_.execute(archive.bytes, entry));
        return wrap(elementNode_Integer_MANY__String_1__FbsNode_1_.execute(data, typeName), null);
    }

    /**
     * A package's children as every archive sees them: each PDB stores its own
     * Package element for a shared path (core.pdb lists the library's children,
     * core-tests.pdb the test packages), so first-archive-wins would hide most
     * of a package.
     */
    private List<Object> mergedChildren(LazyObject owner)
    {
        List<Object> merged = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Archive archive : archives)
        {
            Object element = archive.entries.containsKey(owner.elementPath) ? decode(archive, owner.elementPath) : null;
            LazyObject pkg = element == null ? null : (LazyObject) element;
            for (Object child : pkg == null ? Collections.emptyList() : read(pkg, "children"))
            {
                List<Object> childNames = ((LazyObject) child).__values("name");
                if (childNames.isEmpty() || names.add((String) childNames.get(0)))
                {
                    merged.add(child);
                }
            }
        }
        return Collections.unmodifiableList(merged);
    }

    List<Object> read(LazyObject owner, String property)
    {
        if ("children".equals(property) && owner.elementPath != null)
        {
            return mergedChildren(owner);
        }
        String field = fieldName(owner.node, property);
        if (field == null)
        {
            return Collections.emptyList();
        }
        List<Object> decoded = new ArrayList<>();
        for (Object value : readField_FbsSchema_1__FbsNode_1__String_1__Any_MANY_.execute(schema, owner.node, field))
        {
            Object converted = convert(owner, value);
            if (converted != null)
            {
                decoded.add(converted);
            }
        }
        return Collections.unmodifiableList(decoded);
    }

    private Object convert(LazyObject owner, Object value)
    {
        if (value instanceof FbsNode node)
        {
            if (!SCALAR_WRAPPERS.contains((String) node.type()))
            {
                return wrap(node, owner);
            }
            Object unwrapped = unwrapValueDef_FbsNode_1__Any_1_.execute(node);
            // A Decimal is stored as its text; the value is a BigDecimal, as
            // everywhere else on the platform (Pure's Decimal is exact).
            return "DecimalValueDef".equals(node.type()) && unwrapped instanceof String
                    ? new java.math.BigDecimal((String) unwrapped)
                    : unwrapped;
        }
        if (value instanceof ReadPointerRef pointer)
        {
            return resolve(pointer);
        }
        if (value instanceof ReadAncestorRef ancestor)
        {
            LazyObject target = owner;
            for (long i = 0; i < (Long) ancestor.depth() && target != null; i++)
            {
                target = target.parent;
            }
            return target;
        }
        return value;
    }

    private LazyObject wrap(FbsNode node, LazyObject parent)
    {
        Map<Long, LazyObject> byPosition = nodes.computeIfAbsent(node.bytes(), k -> new HashMap<>());
        LazyObject object = byPosition.get((Long) node.pos());
        if (object == null)
        {
            object = types.apply((String) node.type());
            if (object == null)
            {
                object = new UntypedObject((String) node.type());
            }
            object.runtime = this;
            object.node = node;
            object.parent = parent;
            byPosition.put((Long) node.pos(), object);
        }
        return object;
    }

    private Object resolve(ReadPointerRef pointer)
    {
        List<?> segs = (List<?>) pointer.segs();
        if (segs.isEmpty())
        {
            return null;
        }
        long kind = (Long) pointer.kind();
        if (kind == 0)
        {
            return element(String.join("::", segs.stream().map(String::valueOf).toList()));
        }
        Object owner = element((String) segs.get(0));
        String name = (String) segs.get(1);
        return switch ((int) kind)
        {
            case 1 -> member(owner, name, "properties", "propertiesFromAssociations", "values");
            case 2 -> member(owner, name, "qualifiedProperties", "qualifiedPropertiesFromAssociations");
            case 3 -> valued(owner, name, "p_stereotypes");
            case 4 -> valued(owner, name, "p_tags");
            default -> null;
        };
    }

    /** The first value of `lists` on `owner` whose name is `name`. */
    private static Object member(Object owner, String name, String... lists)
    {
        if (!(owner instanceof LazyObject lazy))
        {
            return null;
        }
        for (String list : lists)
        {
            for (Object candidate : lazy.__values(list))
            {
                if (candidate instanceof LazyObject c && name.equals(c.one("name")))
                {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Object valued(Object owner, String value, String list)
    {
        if (owner instanceof LazyObject lazy)
        {
            for (Object candidate : lazy.__values(list))
            {
                if (candidate instanceof LazyObject c && value.equals(c.one("value")))
                {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** The FlatBuffer field holding `property` on the node's table, or null when the table has none. */
    private String fieldName(FbsNode node, String property)
    {
        FbsTable table = table_FbsSchema_1__String_1__FbsTable_$0_1$_.execute(schema, (String) node.type());
        if (table == null)
        {
            return null;
        }
        String snake = property.replaceAll("([A-Z])", "_$1").toLowerCase();
        for (String candidate : List.of(property, snake, snake + "_", property + "_"))
        {
            for (Object field : (List<?>) table.fields())
            {
                if (candidate.equals(((FbsField) field).name()))
                {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static final class Archive
    {
        final ByteList bytes;
        final Map<String, ZipEntryInfo> entries = new HashMap<>();
        /** The type each element is stored as ('Class', 'NativeFunction', ...). */
        final Map<String, String> elementTypes = new HashMap<>();
        /** The archive's own sections: manifest, functionIndex, elementIndex. */
        final Map<String, ZipEntryInfo> sections = new HashMap<>();

        Archive(ByteList bytes)
        {
            this.bytes = bytes;
            for (ZipEntryInfo entry : readZipEntries_Integer_MANY__ZipEntryInfo_MANY_.execute(bytes))
            {
                String name = (String) entry.name();
                if (!name.startsWith("elements/"))
                {
                    sections.put(name, entry);
                    continue;
                }
                String path = name.substring("elements/".length(), name.lastIndexOf('.')).replace("/", "::");
                entries.put(path.isEmpty() ? "::" : path, entry);
                elementTypes.put(path.isEmpty() ? "::" : path, name.substring(name.lastIndexOf('.') + 1));
            }
        }
    }

    /** A node whose table has no generated implementation. */
    private static final class UntypedObject extends LazyObject
    {
        private final String table;

        UntypedObject(String table)
        {
            this.table = table;
        }

        @Override
        public String _purePath()
        {
            return table.endsWith("Def") ? table.substring(0, table.length() - 3) : table;
        }
    }
}
