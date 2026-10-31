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

package org.finos.legend.pure.truffle.compiler.module.pdbModule;

import org.finos.legend.pure.truffle.compiler.helper._Any;
import org.finos.legend.pure.truffle.execution.types.PureSequence;

/**
 * A column read ({@code $row.col}) as a PDB holds it: its column is written inline (a relation type's column
 * has no address), and read back as the column of that name in the receiver's relation type — the object the
 * compiler resolved the read to. Mirrors the bootstrap's {@code ColumnReads}.
 */
final class ColumnReads
{
    private static final String COLUMN = "platform::pure::metamodel::relation::Column";

    private ColumnReads()
    {
    }

    static Object receiversColumn(Object read, Object func)
    {
        if (func == null || !_Any.pureTypeIs(func, COLUMN))
        {
            return func;
        }
        Object name = _Any.read(func, "name");
        Object receiver = first(_Any.read(read, "parametersValues"));
        Object genericType = receiver == null ? null : _Any.read(receiver, "genericType");
        Object type = genericType == null ? null : _Any.read(genericType, "type");
        Object columns = type == null ? null : _Any.read(type, "columns");
        if (columns instanceof PureSequence seq)
        {
            for (int i = 0; i < seq.size(); i++)
            {
                Object column = seq.getBoxed(i);
                if (name != null && name.equals(_Any.read(column, "name")))
                {
                    return column;
                }
            }
        }
        throw new IllegalStateException("PDB reader: the column read '" + name + "' has no column of that name in its receiver's relation type");
    }

    private static Object first(Object values)
    {
        return values instanceof PureSequence seq ? (seq.size() == 0 ? null : seq.getBoxed(0)) : values;
    }
}
