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
package org.finos.legend.pure.truffle.extensions.relation;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.finos.legend.pure.extensions.relation.table.RelationTable;
import org.finos.legend.pure.truffle.compiler.helper._Any;

/** A relation value on this platform, as the table the natives run on. */
final class Relations
{
    private static final String TDS = "platform::extensions::pure::tds::metamodel::TDS";

    private Relations()
    {
    }

    /**
     * The table {@code relation} is: a TDS literal's, read from its text. Anything else is not a relation this
     * platform knows how to run.
     */
    @TruffleBoundary
    static RelationTable table(Object relation)
    {
        if (relation instanceof RelationTable table)
        {
            return table;
        }
        if (_Any.pureTypeIs(relation, TDS) && _Any.read(relation, "csv") instanceof String csv)
        {
            return RelationTable.fromTdsCsv(csv);
        }
        throw new IllegalArgumentException("not a relation the relation natives can run: "
                + (relation == null ? "nothing" : _Any.pureTypeOf(relation)));
    }
}
