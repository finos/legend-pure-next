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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural;

import platform.pure.metamodel.SourceInformationImpl;
import platform.pure.metamodel.type.Type;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;

/**
 * Compiles/converts between grammar-level and metamodel-level SourceInformation.
 */
public final class SourceInformationCompiler
{
    private SourceInformationCompiler()
    {
    }

    /**
     * Convert grammar-level SourceInformation to metamodel-level SourceInformation.
     * This overload does NOT set classifierGenericType — use only for error-context
     * SourceInformation that will not be serialized to FlatBuffer.
     */
    public static SourceInformationImpl compile(platform.pure.protocol.grammar.SourceInformation src)
    {
        return compile(src, (String) null);
    }

    /**
     * Convert grammar-level SourceInformation to metamodel-level SourceInformation
     * with classifierGenericType properly set.
     */
    public static SourceInformationImpl compile(platform.pure.protocol.grammar.SourceInformation src, MetadataAccess model)
    {
        return compile(src, null, model);
    }

    /**
     * Convert grammar-level SourceInformation to metamodel-level SourceInformation,
     * using the fallback sourceId if the grammar-level one is absent.
     * This overload does NOT set classifierGenericType.
     */
    public static SourceInformationImpl compile(platform.pure.protocol.grammar.SourceInformation src, String fallbackSourceId)
    {
        if (src == null)
        {
            return null;
        }
        String sourceId = src._sourceId() != null ? src._sourceId() : fallbackSourceId;
        return new SourceInformationImpl()
                ._sourceId(sourceId)
                ._startLine(src._startLine())
                ._startColumn(src._startColumn())
                ._endLine(src._endLine())
                ._endColumn(src._endColumn());
    }

    /**
     * Convert grammar-level SourceInformation to metamodel-level SourceInformation,
     * using the fallback sourceId if the grammar-level one is absent.
     * Sets classifierGenericType for proper serialization.
     */
    public static SourceInformationImpl compile(platform.pure.protocol.grammar.SourceInformation src, String fallbackSourceId, MetadataAccess model)
    {
        if (src == null)
        {
            return null;
        }
        return  new SourceInformationImpl(model)
                        ._sourceId(src._sourceId() != null ? src._sourceId() : fallbackSourceId)
                        ._startLine(src._startLine())
                        ._startColumn(src._startColumn())
                        ._endLine(src._endLine())
                        ._endColumn(src._endColumn());
    }
}
