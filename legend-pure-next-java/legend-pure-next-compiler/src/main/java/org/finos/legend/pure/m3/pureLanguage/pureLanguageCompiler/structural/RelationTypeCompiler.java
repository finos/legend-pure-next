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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural;

import meta.pure.metamodel.relation.RelationType;
import meta.pure.metamodel.relation.RelationTypeImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Column;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;

/**
 * Compiles a grammar-level {@link meta.pure.protocol.grammar.relation.RelationType}
 * into a metamodel-level {@link meta.pure.metamodel.relation.RelationType}.
 */
public final class RelationTypeCompiler
{
    private RelationTypeCompiler()
    {
    }

    /**
     * Compile a grammar-level {@link meta.pure.protocol.grammar.relation.RelationType}
     * into a metamodel-level {@link meta.pure.metamodel.relation.RelationType}.
     */
    public static RelationType compile(
            meta.pure.protocol.grammar.relation.RelationType rt,
            MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        RelationTypeImpl result = new RelationTypeImpl();
        GenericType ownerGT = _GenericType.buildUserDefinedGenericType(result, model);
        return result
                ._columns(rt._columns().collect(col -> _Column.build(
                                                                    col._name(),
                                                                    ownerGT,
                                                                    col._genericType() != null ? GenericTypeCompiler.compile(col._genericType(), imports, model, context) : null,
                                                                    col._multiplicity() != null ? MultiplicityCompiler.compile(col._multiplicity(), model) : null,
                                                                    col._nameWildCard() != null && col._nameWildCard(),
                                                                    model)
                                                                )
                )._classifierGenericType(
                        new UserDefinedGenericTypeImpl(model)
                                ._type((Type) model.getElement("meta::pure::metamodel::relation::RelationType"))
                                ._typeArguments(Lists.mutable.with(ownerGT))
                );
    }
}
