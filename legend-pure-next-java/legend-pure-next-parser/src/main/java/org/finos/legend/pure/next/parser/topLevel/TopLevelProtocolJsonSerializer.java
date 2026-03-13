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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Top-level JSON serializer that serializes sections.
 *
 * <p>Serializes a list of {@link Section} instances to JSON.
 * Each section is serialized with its parserName, imports, and elements.</p>
 *
 * <p>Usage:
 * <pre>
 * TopLevelProtocolJsonSerializer serializer =
 *         new TopLevelProtocolJsonSerializer();
 * String json = serializer.serialize(sections);
 * </pre>
 * </p>
 */
public class TopLevelProtocolJsonSerializer
{
    private final ObjectMapper mapper;

    /**
     * Create a new JSON serializer.
     */
    public TopLevelProtocolJsonSerializer()
    {
        this.mapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(tools.jackson.databind.MapperFeature
                        .SORT_PROPERTIES_ALPHABETICALLY)
                .changeDefaultPropertyInclusion(v -> v.withValueInclusion(
                        com.fasterxml.jackson.annotation.JsonInclude
                                .Include.NON_EMPTY))
                .addModule(new tools.jackson.datatype.eclipsecollections
                        .EclipseCollectionsModule())
                .build();
    }

    /**
     * Serialize a PureFile to JSON.
     *
     * @param pureFile the pure file to serialize
     * @return JSON string
     * @throws JacksonException if serialization fails
     */
    public String serialize(final PureFile pureFile)
            throws JacksonException
    {
        return mapper.writeValueAsString(pureFile);
    }

    /**
     * Get the ObjectMapper.
     *
     * @return object mapper
     */
    public ObjectMapper getMapper()
    {
        return mapper;
    }
}
