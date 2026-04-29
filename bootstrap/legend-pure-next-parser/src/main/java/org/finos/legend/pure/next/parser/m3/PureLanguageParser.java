/*
 * Copyright 2024 Goldman Sachs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.finos.legend.pure.next.parser.m3;

import meta.pure.protocol.grammar.PackageableElement;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.pure.next.parser.ParserExtension;

import java.util.List;

/**
 * Parser extension for the default {@code ###Pure} section.
 *
 * <p>Delegates to the {@link M3ProtocolBuilder} for parsing
 * core Pure grammar (classes, enumerations, functions, etc.).</p>
 */
public final class PureLanguageParser implements ParserExtension
{
    @Override
    public String sectionName()
    {
        return "Pure";
    }

    @Override
    public List<PackageableElement> parseSection(String content, String sourceId, int lineOffset)
    {
        if (content.isEmpty())
        {
            return Lists.mutable.empty();
        }
        return new M3ProtocolBuilder().parseElements(content, lineOffset);
    }
}
