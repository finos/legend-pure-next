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

package org.finos.legend.pure.m3.module.sourceModule.topLevel;

import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;
import platform.pure.metamodel.valuespecification.ValueSpecification;
import platform.pure.protocol.grammar.SourceInformation;
import platform.pure.protocol.grammar.SourceInformationImpl;
import platform.pure.protocol.grammar.valuespecification.Island;

/**
 * Compiles expression islands (`#…#`) — the Java counterpart of
 * {@code platform::pure::compiler::extension::IslandHandler}. Every island's text (what lies between the
 * two `#`s) is offered to every handler of every extension; exactly one must claim it.
 */
public interface IslandHandler
{
    /** For diagnostics: errors name the handlers that were asked. */
    String name();

    /** Whether this handler compiles the island whose text (between the `#`s) is given. */
    boolean claims(String text);

    /** The island, compiled to ordinary value specifications in the enclosing expression's context. */
    ValueSpecification compile(Island island, MutableList<String> imports, MetadataAccess model, CompilationContext context);

    /**
     * Where a range of the island's TEXT is in the file — as {@code islandSourceInformation} in Pure. A
     * handler counts in its own text, 1-based: line 1 is the text's first line, and its column 1 is the
     * character right after the opening {@code #}. Report an error there with
     * {@code ValueSpecificationCompiler.islandErrorAt}.
     */
    static SourceInformation sourceInformation(Island island, long line, long column, long endLine, long endColumn)
    {
        SourceInformation at = island._p_sourceInformation();
        return at == null ? null : new SourceInformationImpl()
                ._sourceId(at._sourceId())
                ._startLine(at._startLine() + line - 1)
                ._startColumn(column(at, line, column))
                ._endLine(at._startLine() + endLine - 1)
                ._endColumn(column(at, endLine, endColumn));
    }

    private static long column(SourceInformation at, long line, long column)
    {
        return line == 1 ? at._startColumn() + column : column;
    }
}
