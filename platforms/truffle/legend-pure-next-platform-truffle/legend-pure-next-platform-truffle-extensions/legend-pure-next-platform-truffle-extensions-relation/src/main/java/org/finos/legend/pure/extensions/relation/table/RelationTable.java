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
package org.finos.legend.pure.extensions.relation.table;

import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;

/**
 * A relation's rows, held by column: what the relation natives run on.
 *
 * <p>Host-agnostic — no engine type appears here — so that every JVM platform runs the same table. A cell
 * holds its value, or null for none.</p>
 */
public final class RelationTable
{
    private final ImmutableList<String> columnNames;
    private final ImmutableList<ImmutableList<String>> columns;
    private final int rowCount;

    private RelationTable(ImmutableList<String> columnNames, ImmutableList<ImmutableList<String>> columns, int rowCount)
    {
        this.columnNames = columnNames;
        this.columns = columns;
        this.rowCount = rowCount;
    }

    /**
     * The table a TDS literal holds: its column names, then its rows, as the TDS extension writes them (see
     * {@link TdsCsv}).
     */
    public static RelationTable fromTdsCsv(String csv)
    {
        ListIterable<ListIterable<String>> lines = TdsCsv.parse(csv);
        if (lines.isEmpty())
        {
            throw new IllegalArgumentException("a TDS without a header: '" + csv + "'");
        }
        ImmutableList<String> names = lines.getFirst().collect(name -> name == null ? "" : name).toImmutable();
        ListIterable<ListIterable<String>> rows = lines.drop(1);
        int wrong = rows.detectIndex(row -> row.size() != names.size());
        if (wrong >= 0)
        {
            throw new IllegalArgumentException("a TDS row has " + rows.get(wrong).size() + " cells for "
                    + names.size() + " columns (" + names.makeString(", ") + "): row " + (wrong + 1));
        }
        ImmutableList<ImmutableList<String>> columns = names.collectWithIndex((name, i) -> rows.collect(row -> row.get(i)).toImmutable());
        return new RelationTable(names, columns, rows.size());
    }

    public int rowCount()
    {
        return rowCount;
    }

    public ImmutableList<String> columnNames()
    {
        return columnNames;
    }
}
