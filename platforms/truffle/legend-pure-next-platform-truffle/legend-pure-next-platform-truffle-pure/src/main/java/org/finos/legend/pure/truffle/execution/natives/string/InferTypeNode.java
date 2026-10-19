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

package org.finos.legend.pure.truffle.execution.natives.string;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import org.finos.legend.pure.truffle.execution.ast.PureNode;
import org.finos.legend.pure.truffle.execution.natives.collection.CollectionHelper;

import java.util.regex.Pattern;

/**
 * {@code inferType(String[*]) : Type[1]} — the type a CSV column's cells have, by Legend Engine's rules
 * (deephaven-csv's default parsers; see inferType.pure). An empty or {@code null} cell tells nothing; another
 * is Boolean, Integer (within 64 bits), Float, Date (a date-time with a time and a zone), else String; the
 * column is its cells' kind, Float for Integer and Float, String for any other mix or when no cell tells.
 */
@NodeInfo(shortName = "inferType")
public final class InferTypeNode extends PureNode
{
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");
    private static final Pattern FLOAT = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})");

    @Child
    private PureNode arg;

    public InferTypeNode(PureNode arg)
    {
        this.arg = arg;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame)
    {
        return getContext().resolver().getElement(path(arg.executeGeneric(frame)));
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String path(Object cells)
    {
        String kind = null;
        int size = CollectionHelper.size(cells);
        for (int i = 0; i < size; i++)
        {
            Object raw = CollectionHelper.at(cells, i);
            String cell = raw == null ? "" : String.valueOf(raw).trim();
            if (cell.isEmpty() || "null".equals(cell))
            {
                continue;
            }
            String cellKind = kind(cell);
            kind = kind == null || kind.equals(cellKind) ? cellKind
                    : (isNumber(kind) && isNumber(cellKind)) ? "Float"
                    : "String";
        }
        return "platform::pure::metamodel::type::primitives::" + (kind == null ? "String" : kind);
    }

    private static boolean isNumber(String kind)
    {
        return "Integer".equals(kind) || "Float".equals(kind);
    }

    private static String kind(String cell)
    {
        if ("true".equalsIgnoreCase(cell) || "false".equalsIgnoreCase(cell))
        {
            return "Boolean";
        }
        if (INTEGER.matcher(cell).matches())
        {
            try
            {
                Long.parseLong(cell.startsWith("+") ? cell.substring(1) : cell);
                return "Integer";
            }
            catch (NumberFormatException e)
            {
                return "Float";
            }
        }
        if (FLOAT.matcher(cell).matches())
        {
            return "Float";
        }
        return DATE.matcher(cell).matches() ? "Date" : "String";
    }
}
