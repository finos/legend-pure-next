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

package org.finos.legend.pure.next.parser.topLevel;

import meta.pure.protocol.PureFile;
import meta.pure.protocol.Section;
import meta.pure.protocol.grammar.PackageableElement;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.next.parser.m3.M3ProtocolSerializer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Top-level Pure code serializer that handles section dispatch.
 *
 * <p>Serializes a list of {@link Section} instances back to Pure source
 * text with {@code ###SectionName} headers, imports, and dispatched
 * element content.</p>
 *
 * <p>Usage:
 * <pre>
 * TopLevelProtocolSerializer serializer = new TopLevelProtocolSerializer();
 * String pureCode = serializer.serialize(sections);
 * </pre>
 * </p>
 */
public class TopLevelProtocolSerializer
{
    private final Map<String, Function<MutableList<PackageableElement>, String>>
            serializers = new LinkedHashMap<>();
    private final M3ProtocolSerializer.ParenthesisMode parenMode;

    /**
     * Create a new serializer with MINIMAL parenthesization.
     */
    public TopLevelProtocolSerializer()
    {
        this(M3ProtocolSerializer.ParenthesisMode.MINIMAL);
    }

    /**
     * Create a new serializer with the given parenthesization mode.
     */
    public TopLevelProtocolSerializer(final M3ProtocolSerializer.ParenthesisMode mode)
    {
        this.parenMode = mode;
        register("Pure",
                elements -> serializePureElements(elements, parenMode));
    }

    /**
     * Register a serializer for a section name.
     *
     * @param sectionName the section name (e.g. "Pure", "Relational")
     * @param serializer  function that takes elements and returns Pure text
     * @return this serializer for chaining
     */
    public TopLevelProtocolSerializer register(
            final String sectionName,
            final Function<MutableList<PackageableElement>, String> serializer)
    {
        serializers.put(sectionName, serializer);
        return this;
    }

    /**
     * Serialize a PureFile to Pure source text.
     *
     * @param pureFile the pure file to serialize
     * @return Pure source text with section headers
     */
    public String serialize(final PureFile pureFile)
    {
        MutableList<Section> sections = pureFile._sections();
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < sections.size(); i++)
        {
            Section section = sections.get(i);
            String parserName = section._parserName();

            if (i > 0)
            {
                sb.append("\n\n");
            }

            sb.append("###").append(parserName).append("\n");

            // Imports
            for (String imp : section._imports())
            {
                sb.append("import ").append(imp).append(";\n");
            }

            // Elements
            Function<MutableList<PackageableElement>, String> sectionSerializer =
                    serializers.get(parserName);
            if (sectionSerializer == null)
            {
                throw new RuntimeException(
                        "No serializer registered for section: ###"
                                + parserName);
            }

            String content = sectionSerializer.apply(section._elements());
            if (content != null && !content.isEmpty())
            {
                sb.append(content);
            }
        }

        return sb.toString();
    }

    private static String serializePureElements(
            final MutableList<PackageableElement> elements,
            final M3ProtocolSerializer.ParenthesisMode mode)
    {
        M3ProtocolSerializer pureSerializer = new M3ProtocolSerializer(mode);
        return pureSerializer.serializeElements(elements);
    }
}
