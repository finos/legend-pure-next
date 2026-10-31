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

package org.finos.legend.pure.platform.java.runtime;

/**
 * Relation values in translated code. A row ({@code $row.col}'s receiver) is a {@link Row}: the relation
 * runtime's tables hand out rows implementing it.
 */
public final class PureRelations
{
    /** A row of a relation: its value in a column, by the column's name. */
    public interface Row
    {
        Object get(String column);
    }

    private PureRelations()
    {
    }

    public static Object columnValue(Object row, String column)
    {
        if (row instanceof Row r)
        {
            return r.get(column);
        }
        throw new IllegalArgumentException("Column read '" + column + "' on a value that is not a relation row: "
                + (row == null ? "null" : row.getClass().getName()));
    }
}
