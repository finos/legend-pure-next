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

package org.finos.legend.pure.specification.generation.fbs;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.map.MutableMap;

/**
 * Parsed view of an {@code .fbs} schema, exposing the subset that downstream
 * generators need to stay in sync with the wire format: union member ordering
 * and the union (if any) backing each table field.
 *
 * <p>Built from the same {@code m3.fbs} consumed by {@code flatc} — making the
 * schema the single source of truth for byte indices shared between writer
 * codegen, wrapper (reader) codegen, and the FlatBuffer reader.</p>
 */
public final class FbsSchema
{
    private final MutableMap<String, FbsUnion> unions;
    private final MutableMap<String, FbsTable> tables;

    FbsSchema(MutableMap<String, FbsUnion> unions, MutableMap<String, FbsTable> tables)
    {
        this.unions = unions;
        this.tables = tables;
    }

    public FbsUnion union(String name)
    {
        FbsUnion u = unions.get(name);
        if (u == null)
        {
            throw new IllegalArgumentException("Unknown union: " + name + " — schema has " + unions.keysView().toSortedList());
        }
        return u;
    }

    public boolean hasUnion(String name)
    {
        return unions.containsKey(name);
    }

    public FbsTable table(String name)
    {
        FbsTable t = tables.get(name);
        if (t == null)
        {
            throw new IllegalArgumentException("Unknown table: " + name);
        }
        return t;
    }

    public boolean hasTable(String name)
    {
        return tables.containsKey(name);
    }

    /**
     * Look up the union backing {@code field} on {@code table}, if any.
     * Returns null when the field is not union-typed (e.g. a primitive or a
     * concrete table reference).
     */
    public FbsUnion unionForField(String tableName, String fieldName)
    {
        FbsTable t = tables.get(tableName);
        if (t == null) { return null; }
        String unionName = t.fieldUnionType(fieldName);
        return unionName == null ? null : unions.get(unionName);
    }

    public ImmutableList<String> unionNames()
    {
        return unions.keysView().toSortedList().toImmutable();
    }

    /**
     * A union: ordered members, with byte index = position in declaration + 1
     * (matching FlatBuffers' 1-based union discriminator; 0 is reserved for
     * "absent"/NONE).
     */
    public static final class FbsUnion
    {
        private final String name;
        private final ImmutableList<String> members;

        FbsUnion(String name, MutableList<String> members)
        {
            this.name = name;
            this.members = members.toImmutable();
        }

        public String name() { return name; }

        public ImmutableList<String> members() { return members; }

        public boolean hasMember(String memberName)
        {
            return members.contains(memberName);
        }

        /**
         * Byte discriminator for {@code memberName} (1-based). Throws when the
         * member is not part of this union — that's the structural check that
         * keeps writer and reader in sync.
         */
        public int byteFor(String memberName)
        {
            int idx = members.indexOf(memberName);
            if (idx < 0)
            {
                throw new IllegalArgumentException(
                        "Union '" + name + "' has no member '" + memberName + "'. Members: " + members);
            }
            return idx + 1;
        }
    }

    /**
     * A table: ordered fields, each with a type reference. Only union-typed
     * fields are tracked here — that's all the downstream codegen needs.
     */
    public static final class FbsTable
    {
        private final String name;
        private final MutableMap<String, String> fieldUnion;

        FbsTable(String name, MutableMap<String, String> fieldUnion)
        {
            this.name = name;
            this.fieldUnion = fieldUnion;
        }

        public String name() { return name; }

        public String fieldUnionType(String fieldName)
        {
            return fieldUnion.get(fieldName);
        }
    }

    static FbsSchema empty()
    {
        return new FbsSchema(Maps.mutable.empty(), Maps.mutable.empty());
    }

    static MutableList<String> mutableList()
    {
        return Lists.mutable.empty();
    }
}
