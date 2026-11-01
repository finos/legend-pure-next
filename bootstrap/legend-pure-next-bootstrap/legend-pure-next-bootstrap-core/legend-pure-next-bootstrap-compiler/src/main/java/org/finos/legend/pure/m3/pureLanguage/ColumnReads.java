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

package org.finos.legend.pure.m3.pureLanguage;

import platform.pure.metamodel.relation.Column;
import platform.pure.metamodel.relation.RelationType;
import platform.pure.metamodel.type.generics.GenericTypeValue;
import platform.pure.metamodel.valuespecification.FunctionExpression;
import platform.pure.metamodel.valuespecification.ValueSpecification;

/**
 * A column read ({@code $row.col}) as a PDB holds it: its column is written inline (a relation type's column
 * has no address), and read back as the column of that name in the receiver's relation type — the object the
 * compiler resolved the read to.
 */
public final class ColumnReads
{
    private ColumnReads()
    {
    }

    public static Column receiversColumn(FunctionExpression read, Column written)
    {
        ValueSpecification receiver = read._parametersValues() == null || read._parametersValues().isEmpty() ? null : read._parametersValues().getFirst();
        Column column = receiver != null && receiver._genericType() instanceof GenericTypeValue gtv && gtv._type() instanceof RelationType rt
                ? (Column) rt._columns().detect(c -> c._name() != null && c._name().equals(written._name()))
                : null;
        if (column == null)
        {
            throw new IllegalStateException("PDB reader: the column read '" + written._name() + "' has no column of that name in its receiver's relation type");
        }
        return column;
    }
}
