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

package org.finos.legend.pure.next.parser;

import meta.pure.protocol.grammar.PackageableElement;

import java.util.List;

/**
 * Extension point for parsing custom {@code ###Section} types.
 *
 * <p>Implementations provide a section name and the logic to parse
 * raw section content into grammar elements.</p>
 */
public interface ParserExtension
{
    /**
     * @return the section name without the {@code ###} prefix (e.g. "CompiledGraph")
     */
    String sectionName();

    /**
     * Parse the content of a custom section into grammar elements.
     *
     * @param content    the section content (text between section headers)
     * @param sourceId   identifier for the source file
     * @param lineOffset 0-based line offset for correct file-level line numbers
     * @return parsed grammar elements
     */
    List<PackageableElement> parseSection(String content, String sourceId, int lineOffset);
}
