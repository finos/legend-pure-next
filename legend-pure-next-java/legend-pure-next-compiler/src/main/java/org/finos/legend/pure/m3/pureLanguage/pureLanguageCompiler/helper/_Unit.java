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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.SourceInformation;
import org.eclipse.collections.api.list.ListIterable;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.jspecify.annotations.Nullable;

/**
 * Helper methods for {@link meta.pure.metamodel.type.Unit}.
 */
public final class _Unit
{
    private _Unit()
    {
        // static utility
    }

    /**
     * Find a Unit by a compound name of the form {@code MeasurePath~UnitName},
     * trying fully-qualified and import-prefixed Measure paths.
     *
     * @param name              the compound name (e.g., {@code "RomanLength~Pes"})
     * @param imports           import package paths from the enclosing section
     * @param model             the compiled PureModel used for element lookup
     * @param context           compilation context for error reporting
     * @param sourceInformation source location for error messages
     * @return the resolved Unit, or {@code null} if not found
     */
    public static meta.pure.metamodel.type.@Nullable Unit findUnit(String name, ListIterable<String> imports, MetadataAccess model,
                                                                    CompilationContext context, SourceInformation sourceInformation)
    {
        int tildeIdx = name.indexOf('~');
        if (tildeIdx < 0)
        {
            return null;
        }
        String measureRef = name.substring(0, tildeIdx);
        String unitName = name.substring(tildeIdx + 1);
        PackageableElement measureElement = _PackageableElement.findElementOrReportError(measureRef, imports, model, context, sourceInformation);
        if (measureElement instanceof meta.pure.metamodel.type.Measure measure)
        {
            meta.pure.metamodel.type.Unit unit = findUnitInMeasure(measure, unitName);
            if (unit == null)
            {
                context.addError(new CompilationError("The unit '" + unitName + "' can't be found in measure '" + measureRef + "'", sourceInformation));
            }
            return unit;
        }
        if (measureElement != null)
        {
            context.addError(new CompilationError("The element '" + measureRef + "' is not a Measure", sourceInformation));
        }
        else if (context.currentErrorCount() == 0)
        {
            context.addError(new CompilationError("The measure '" + measureRef + "' can't be found", sourceInformation));
        }
        return null;
    }

    /**
     * Find a unit by name within a Measure's canonical and non-canonical units.
     */
    public static meta.pure.metamodel.type.@Nullable Unit findUnitInMeasure(meta.pure.metamodel.type.Measure measure, String unitName)
    {
        if (measure._canonicalUnit() != null && unitName.equals(measure._canonicalUnit()._name()))
        {
            return measure._canonicalUnit();
        }
        if (measure._nonCanonicalUnits() != null)
        {
            return measure._nonCanonicalUnits().detect(u -> unitName.equals(u._name()));
        }
        return null;
    }
}
