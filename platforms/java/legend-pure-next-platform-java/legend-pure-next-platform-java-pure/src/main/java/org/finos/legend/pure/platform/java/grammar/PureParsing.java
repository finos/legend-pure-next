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
// limitations under the License.

package org.finos.legend.pure.platform.java.grammar;

import org.finos.legend.pure.platform.java.execution.Execute;
import org.finos.legend.pure.platform.java.runtime.PureRuntime;
import org.finos.legend.pure.platform.java.grammar.Antlr;
import org.finos.legend.pure.platform.java.compiler.module.pdbModule.Metadata;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;

/**
 * `parse(sourceId, content)` — Pure source to a PureFile.
 *
 * <p>Almost none of it is here: the Top grammar splits the source into sections
 * (the ANTLR extension), and the translated Pure `parseDocument` hands each
 * section to the parser registered for it. This only assembles those three
 * pieces, the way the other hosts do.</p>
 */
public final class PureParsing
{
    private static final String PARSE_DOCUMENT =
            "meta::pure::parser::mappings::interpreter::parseDocument_AntlrContext_1__String_1__Boolean_1__Pair_MANY__PureFile_1_";
    private static volatile MutableList<Object> sectionParsers;

    private PureParsing()
    {
    }

    public static Object parse(String sourceId, String content)
    {
        // Source that names no section is Pure: the header the grammar needs is
        // added here, and parseDocument is told so it can keep line numbers.
        boolean syntheticHeader = !content.startsWith("###");
        String effective = syntheticHeader ? "###Pure\n" + content : content;
        Object document = Antlr.parseAntlr(effective, "TopParser", sourceId, 0L);
        return call(PARSE_DOCUMENT, document, sourceId, syntheticHeader, sectionParsers());
    }

    /**
     * The section parsers of every installed language, in registration order. Each is a Pair of a
     * section name and the function that parses that section; built once.
     *
     * <p>The element paths come from the languages' manifests — this used to name Pure's own parser
     * and the compiler's test sections as literals, which made the host the place those two facts
     * lived.</p>
     */
    private static synchronized MutableList<Object> sectionParsers()
    {
        if (sectionParsers == null)
        {
            MutableList<Object> parsers = Lists.mutable.empty();
            for (Language language : PureRuntime.languages())
            {
                String path = language.sectionParsers();
                if (path == null)
                {
                    continue;   // a language may add a grammar and no section of its own
                }
                // GUARDED ON THE ELEMENT BEING LOADED, so an extension whose module is absent
                // contributes nothing rather than throwing — that is what lets a module arrive late.
                // A bundled language has no module to check against and must not be guarded into
                // silence: `###Pure` missing is a broken host, not an absent feature.
                if (!language.bundled() && Metadata.lenientElement(path) == null)
                {
                    continue;
                }
                add(parsers, call(path));
            }
            sectionParsers = parsers;
        }
        return sectionParsers;
    }

    /** Drop the memo after {@code PureRuntime.installLanguages}. */
    public static synchronized void reloadSectionParsers()
    {
        sectionParsers = null;
    }

    private static void add(MutableList<Object> parsers, Object value)
    {
        // A Pure function returning `Pair[*]` hands back a java.util.List from translated code, which is
        // why this takes the broad type rather than an Eclipse Collections one.
        if (value instanceof java.util.List)
        {
            parsers.addAll((java.util.List<?>) value);
        }
        else if (value != null)
        {
            parsers.add(value);
        }
    }

    private static Object call(String path, Object... args)
    {
        try
        {
            return Execute.call(path, args);
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException("parse: cannot call " + path + ": " + e.getMessage(), e);
        }
    }
}
