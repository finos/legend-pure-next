package org.finos.legend.pure.m3.pureLanguage.metadata;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.finos.legend.pure.m3.module.MetadataAccessExtension;

public class PureLanguageMetadata implements MetadataAccessExtension, PureLanguageMetadataAccess
{
    private MutableMap<String, MutableMap<Integer, MutableList<FunctionIndexEntry>>> functionIndex = Maps.mutable.empty();

    public PureLanguageMetadata()
    {
    }

    public PureLanguageMetadata(MutableMap<String, MutableMap<Integer, MutableList<FunctionIndexEntry>>> functionIndex)
    {
        this.functionIndex = functionIndex;
    }

    public void setFunctionIndex(MutableMap<String, MutableMap<Integer, MutableList<FunctionIndexEntry>>> functionIndex)
    {
        this.functionIndex = functionIndex;
    }

    private MutableMap<String, MutableMap<Integer, MutableList<FunctionIndexEntry>>> getFunctionIndex()
    {
        return functionIndex;
    }

    public MutableMap<Integer, MutableList<FunctionIndexEntry>> findFunctionHeadersByName(String shortName)
    {
        MutableMap<String, MutableMap<Integer, MutableList<FunctionIndexEntry>>> index = getFunctionIndex();
        return index.get(shortName);
    }

    public MutableList<FunctionIndexEntry> findFunctionHeadersByNameAndArity(String shortName, int paramCount)
    {
        MutableMap<Integer, MutableList<FunctionIndexEntry>> byParamCount = findFunctionHeadersByName(shortName);
        if (byParamCount == null)
        {
            return Lists.mutable.empty();
        }
        MutableList<FunctionIndexEntry> result = byParamCount.get(paramCount);
        return result != null ? result : Lists.mutable.empty();
    }

    public MutableList<FunctionIndexEntry> getAllFunctionHeaders()
    {
        MutableList<FunctionIndexEntry> result = Lists.mutable.empty();
        getFunctionIndex().forEachValue(byParamCount ->
                byParamCount.forEachValue(result::addAllIterable));
        return result;
    }
}
