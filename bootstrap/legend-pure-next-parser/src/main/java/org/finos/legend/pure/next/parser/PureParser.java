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

import meta.pure.protocol.PureFile;
import org.finos.legend.pure.next.parser.topLevel.TopLevelProtocolBuilder;

import java.util.List;

/**
 * Parses Pure source text into protocol model {@link PureFile} objects.
 *
 * <p>Built via {@link PureParserBuilder} to configure parser extensions
 * for custom section types.</p>
 *
 * <p>Usage:
 * <pre>
 * PureParser parser = PureParser.builder()
 *         .withExtensions(List.of(myExtension))
 *         .build();
 * PureFile file = parser.parse("test.pure", content);
 * </pre>
 * </p>
 */
public final class PureParser
{
    private final List<ParserExtension> extensions;

    private PureParser(List<ParserExtension> extensions)
    {
        this.extensions = List.copyOf(extensions);
    }

    /**
     * Parse source text into a PureFile.
     *
     * @param sourceId identifier for the source (e.g. file path)
     * @param content  the full source text
     * @return PureFile with parsed sections and sourceId
     */
    public PureFile parse(String sourceId, String content)
    {
        return TopLevelProtocolBuilder.parse(content, sourceId, extensions);
    }

    /**
     * Create a new builder.
     */
    public static PureParserBuilder builder()
    {
        return new PureParserBuilder();
    }

    /**
     * Builder for configuring and creating a {@link PureParser}.
     */
    public static final class PureParserBuilder
    {
        private List<? extends ParserExtension> extensions = List.of();

        private PureParserBuilder()
        {
        }

        /**
         * Set the parser extensions for custom section types.
         *
         * @param extensions list of parser extensions
         * @return this builder
         */
        public PureParserBuilder withExtensions(List<? extends ParserExtension> extensions)
        {
            this.extensions = extensions;
            return this;
        }

        /**
         * Build the PureParser.
         *
         * @return configured PureParser instance
         */
        public PureParser build()
        {
            return new PureParser(List.copyOf(extensions));
        }
    }
}
