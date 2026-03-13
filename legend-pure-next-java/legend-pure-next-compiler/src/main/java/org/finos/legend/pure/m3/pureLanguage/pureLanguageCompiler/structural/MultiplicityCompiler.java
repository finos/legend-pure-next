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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural;

import meta.pure.metamodel.multiplicity.ConcreteMultiplicityImpl;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.multiplicity.MultiplicityValueImpl;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * Compiles a grammar-level {@link meta.pure.protocol.grammar.multiplicity.Multiplicity}
 * into a metamodel-level {@link Multiplicity}.
 * <p>
 * Well-known multiplicities ({@code [1]}, {@code [0..1]}, {@code [*]}, {@code [1..*]}) are resolved
 * to their canonical {@link meta.pure.metamodel.multiplicity.PackageableMultiplicity}
 * instances bootstrapped in the {@link PureModel}.
 * Non-standard multiplicities are represented as {@link ConcreteMultiplicityImpl}.
 */
public final class MultiplicityCompiler
{
    private static final String MULTIPLICITY_PACKAGE = "meta::pure::metamodel::multiplicity::";

    private MultiplicityCompiler()
    {
    }

    /**
     * Compile a grammar Multiplicity into a metamodel Multiplicity.
     * <p>
     * If the multiplicity matches a well-known pattern, the canonical
     * {@code PackageableMultiplicity} is looked up from the model.
     * Otherwise a fresh {@code ConcreteMultiplicity} is created.
     *
     * @param grammarMultiplicity the grammar-level multiplicity to compile
     * @param model               the PureModel containing bootstrapped multiplicities
     * @return the resolved metamodel Multiplicity
     */
    public static Multiplicity compile(meta.pure.protocol.grammar.multiplicity.Multiplicity grammarMultiplicity, MetadataAccess model)
    {
        // Try to resolve to a well-known PackageableMultiplicity
        if (grammarMultiplicity._multiplicityParameter() == null)
        {
            String canonicalName = resolveCanonicalName(grammarMultiplicity);
            if (canonicalName != null)
            {
                Multiplicity found = (Multiplicity) model.getElement(MULTIPLICITY_PACKAGE + canonicalName);
                if (found != null)
                {
                    return found;
                }
            }
        }

        // Fall back to an anonymous ConcreteMultiplicity
        ConcreteMultiplicityImpl result = new ConcreteMultiplicityImpl()
                ._multiplicityParameter(grammarMultiplicity._multiplicityParameter());
        if (grammarMultiplicity._lowerBound() != null)
        {
            result._lowerBound(new MultiplicityValueImpl()._value(grammarMultiplicity._lowerBound()._value()));
        }
        if (grammarMultiplicity._upperBound() != null)
        {
            result._upperBound(new MultiplicityValueImpl()._value(grammarMultiplicity._upperBound()._value()));
        }
        return result;
    }

    private static String resolveCanonicalName(meta.pure.protocol.grammar.multiplicity.Multiplicity m)
    {
        long lower = m._lowerBound() != null ? m._lowerBound()._value() : -1;
        long upper = m._upperBound() != null ? m._upperBound()._value() : -1;
        boolean hasUpper = m._upperBound() != null;

        if (lower == 1 && hasUpper && upper == 1)
        {
            return "PureOne";
        }
        if (lower == 0 && hasUpper && upper == 1)
        {
            return "ZeroOne";
        }
        if (lower == 0 && !hasUpper)
        {
            return "ZeroMany";
        }
        if (lower == 1 && !hasUpper)
        {
            return "OneMany";
        }
        return null;
    }

    public static meta.pure.metamodel.type.generics.MultiplicityParameter compileMultiplicityParameter(meta.pure.protocol.grammar.type.generics.MultiplicityParameter grammarMultiplicityParameter)
    {
        if (grammarMultiplicityParameter == null)
        {
            return null;
        }
        return new meta.pure.metamodel.type.generics.MultiplicityParameterImpl()
                ._name(grammarMultiplicityParameter._name());
    }
}
