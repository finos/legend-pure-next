package org.finos.legend.pure.m3.pureLanguage.metadata;

import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;

public interface PureLanguageMetadataAccess
{
    /**
     * Find all function overloads for the given short name,
     * grouped by parameter count.
     *
     * @param shortName the short (base) function name (e.g. "plus")
     * @return the map (paramCount → functions), or {@code null} if none
     */
    MutableMap<Integer, MutableList<FunctionIndexEntry>> findFunctionHeadersByName(String shortName);

    /**
     * Find functions by short name and parameter count
     * (most specific first).
     *
     * @param shortName  the short (base) function name (e.g. "plus")
     * @param paramCount the number of parameters
     * @return list of matching functions, or an empty list if none found
     */
    MutableList<FunctionIndexEntry> findFunctionHeadersByNameAndArity(String shortName, int paramCount);

    /**
     * Return all function headers as a flat list.
     */
    MutableList<FunctionIndexEntry> getAllFunctionHeaders();
}
