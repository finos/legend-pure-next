// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.m3.pureLanguage.metadata;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Aggregates multiple {@link PureLanguageMetadata} instances into a
 * single {@link PureLanguageMetadataAccess} view.
 *
 * <p>Function lookup results are merged across all delegates. Within
 * each arity group the entries are sorted most-specific-first using
 * {@link FunctionIndexEntry#mostSpecificFirst(MetadataAccess)}.</p>
 */
public class CompositePureLanguageMetadata implements PureLanguageMetadataAccess
{
    private final MutableList<PureLanguageMetadata> delegates;
    private final MetadataAccess model;

    public CompositePureLanguageMetadata(MutableList<PureLanguageMetadata> delegates, MetadataAccess model)
    {
        this.delegates = delegates;
        this.model = model;
    }

    @Override
    public MutableMap<Integer, MutableList<FunctionIndexEntry>> findFunctionHeadersByName(String shortName)
    {
        MutableMap<Integer, MutableList<FunctionIndexEntry>> merged = Maps.mutable.empty();
        for (PureLanguageMetadata delegate : delegates)
        {
            MutableMap<Integer, MutableList<FunctionIndexEntry>> partial = delegate.findFunctionHeadersByName(shortName);
            if (partial != null)
            {
                partial.forEachKeyValue((arity, entries) ->
                        merged.getIfAbsentPut(arity, Lists.mutable::empty).addAll(entries));
            }
        }
        if (merged.isEmpty())
        {
            return null;
        }
        merged.forEachValue(entries -> entries.sortThis(FunctionIndexEntry.mostSpecificFirst(model)));
        return merged;
    }

    @Override
    public MutableList<FunctionIndexEntry> findFunctionHeadersByNameAndArity(String shortName, int paramCount)
    {
        MutableList<FunctionIndexEntry> result = Lists.mutable.empty();
        for (PureLanguageMetadata delegate : delegates)
        {
            result.addAll(delegate.findFunctionHeadersByNameAndArity(shortName, paramCount));
        }
        result.sortThis(FunctionIndexEntry.mostSpecificFirst(model));
        return result;
    }

    @Override
    public MutableList<FunctionIndexEntry> getAllFunctionHeaders()
    {
        MutableList<FunctionIndexEntry> result = Lists.mutable.empty();
        for (PureLanguageMetadata delegate : delegates)
        {
            result.addAll(delegate.getAllFunctionHeaders());
        }
        return result;
    }
}
