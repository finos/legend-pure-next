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

package org.finos.legend.pure.platform.java.parser;

import org.finos.legend.pure.platform.java.Execute;
import org.finos.legend.pure.platform.java.antlr.Antlr;
import org.finos.legend.pure.platform.java.pdb.Metadata;

import java.util.ArrayList;
import java.util.List;

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
    private static final String PURE_SECTION_PARSER =
            "meta::pure::parser::mappings::interpreter::pureSectionParser__Pair_1_";
    private static final String TEST_SECTION_PARSERS =
            "meta::pure::compiler::test::testSectionParsers__Pair_MANY_";

    private static volatile List<Object> sectionParsers;

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
     * The section parsers: Pure's own, plus the compiler's test sections when
     * that module is loaded. Built once — each is a Pair of a section name and
     * the function that parses that section.
     */
    private static synchronized List<Object> sectionParsers()
    {
        if (sectionParsers == null)
        {
            List<Object> parsers = new ArrayList<>();
            add(parsers, call(PURE_SECTION_PARSER));
            if (Metadata.lenientElement(TEST_SECTION_PARSERS) != null)
            {
                add(parsers, call(TEST_SECTION_PARSERS));
            }
            sectionParsers = parsers;
        }
        return sectionParsers;
    }

    private static void add(List<Object> parsers, Object value)
    {
        if (value instanceof List)
        {
            parsers.addAll((List<?>) value);
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
