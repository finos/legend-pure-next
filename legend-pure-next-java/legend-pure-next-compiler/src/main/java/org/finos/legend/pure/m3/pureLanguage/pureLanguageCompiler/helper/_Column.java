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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.relation.Column;
import meta.pure.metamodel.relation.ColumnImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl;
import meta.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Helper methods for {@link Column}.
 */
public final class _Column
{
    private _Column()
    {
        // static utility
    }

    /**
     * Build a new Column with the given name, owner, type, multiplicity, and wildcard flag.
     * If multiplicity is null, defaults to PureOne.
     * Sets classifierGenericType to {@code Column<owner, genericType | multiplicity>}.
     *
     * @param name         the column name
     * @param owner        the GenericType of the owning RelationType (U in Column&lt;U,V|m&gt;)
     * @param genericType  the column's value type (V)
     * @param multiplicity the column's value multiplicity (m), or null for PureOne
     * @param nameWildCard true if this column name is a wildcard pattern
     * @param model        the compiled PureModel
     */
    public static Column build(String name, GenericType owner, GenericType genericType, Multiplicity multiplicity, boolean nameWildCard, MetadataAccess model)
    {
        Multiplicity mul = multiplicity != null ? multiplicity : (Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::PureOne");
        return new ColumnImpl()
                ._name(name)
                ._nameWildCard(nameWildCard)
                ._genericType(genericType)
                ._multiplicity(mul)
                ._classifierGenericType(
                        new ConcreteGenericTypeImpl()
                                ._rawType((Type) model.getElement("meta::pure::metamodel::relation::Column"))
                                ._typeArguments(Lists.mutable.with(owner, genericType))
                                ._multiplicityArguments(Lists.mutable.with(mul)));
    }
}
