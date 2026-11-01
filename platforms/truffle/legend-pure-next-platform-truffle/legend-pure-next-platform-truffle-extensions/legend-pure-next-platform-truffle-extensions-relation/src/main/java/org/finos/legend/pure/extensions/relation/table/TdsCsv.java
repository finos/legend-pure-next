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

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.impl.utility.ArrayIterate;

import java.util.regex.Pattern;

/**
 * The text of a TDS literal, as the TDS extension keeps it ({@code csv}): a line of column names, then one
 * line per row. A cell is separated from the next by {@code ,}; one that holds a {@code ,} is quoted with
 * {@code "} ({@code ""} inside for a {@code "}); spaces around a cell are not part of it; an empty cell or
 * {@code null} holds no value (null here).
 */
final class TdsCsv
{
    // a `,` outside quotes: followed by an even number of `"` up to the end of the line
    private static final Pattern SEPARATOR = Pattern.compile(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
    private static final Pattern LINE = Pattern.compile("\r?\n");

    private TdsCsv()
    {
    }

    static ListIterable<ListIterable<String>> parse(String csv)
    {
        return ArrayIterate.reject(LINE.split(csv), line -> line.isBlank())
                .collect(line -> (ListIterable<String>) Lists.mutable.with(SEPARATOR.split(line, -1)).collect(TdsCsv::cell));
    }

    private static String cell(String text)
    {
        String trimmed = text.strip();
        if (trimmed.isEmpty() || "null".equals(trimmed))
        {
            return null;
        }
        return trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"")
                : trimmed;
    }
}
