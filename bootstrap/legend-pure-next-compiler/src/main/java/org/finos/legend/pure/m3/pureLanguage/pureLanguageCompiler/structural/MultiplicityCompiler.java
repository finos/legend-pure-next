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

import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.multiplicity.MultiplicityValueImpl;
import meta.pure.metamodel.multiplicity.UserDefinedAdHocMultiplicityImpl;
import meta.pure.protocol.grammar.multiplicity.Multiplicity_Pointer;
import meta.pure.protocol.grammar.multiplicity.Multiplicity_Protocol;
import org.finos.legend.pure.m3.PureModel;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;

/**
 * Compiles a grammar-level {@link meta.pure.protocol.grammar.multiplicity.Multiplicity}
 * into a metamodel-level {@link Multiplicity}.
 * <p>
 * Well-known multiplicities ({@code [1]}, {@code [0..1]}, {@code [*]}, {@code [1..*]}) are resolved
 * to their canonical {@link meta.pure.metamodel.multiplicity.PackageableMultiplicity}
 * instances bootstrapped in the {@link PureModel}.
 * Non-standard multiplicities are represented as {@link UserDefinedAdHocMultiplicityImpl}.
 */
public final class MultiplicityCompiler
{
    private static final String MULTIPLICITY_PACKAGE = "meta::pure::metamodel::multiplicity::";

    private MultiplicityCompiler()
    {
    }

    /**
     * Compile a grammar Multiplicity into a metamodel Multiplicity (without scope context).
     */
    public static Multiplicity compile(meta.pure.protocol.grammar.multiplicity.Multiplicity grammarMultiplicity, MetadataAccess model)
    {
        return compile(grammarMultiplicity, model, null);
    }

    /**
     * Compile a Multiplicity_Protocol (union of pointer + inline) into a metamodel Multiplicity.
     * Dispatches to the Multiplicity overload for inline ConcreteMultiplicity,
     * and resolves Multiplicity_Pointer paths directly to canonical singletons.
     */
    public static Multiplicity compile(Multiplicity_Protocol grammarMultiplicity, MetadataAccess model)
    {
        return compile(grammarMultiplicity, model, null);
    }

    /**
     * Compile a Multiplicity_Protocol with scope context.
     */
    public static Multiplicity compile(Multiplicity_Protocol grammarMultiplicity, MetadataAccess model, CompilationContext context)
    {
        if (grammarMultiplicity == null)
        {
            return (Multiplicity) model.getElement(MULTIPLICITY_PACKAGE + "PureOne");
        }
        if (grammarMultiplicity instanceof meta.pure.protocol.grammar.multiplicity.UndefinedMultiplicity)
        {
            return new meta.pure.metamodel.multiplicity.UndefinedMultiplicityImpl(model);
        }
        if (grammarMultiplicity instanceof meta.pure.protocol.grammar.multiplicity.Multiplicity m)
        {
            return compile(m, model, context);
        }
        if (grammarMultiplicity instanceof Multiplicity_Pointer ptr)
        {
            return (Multiplicity) model.getElement(ptr._pointerValue());
        }
        return (Multiplicity) model.getElement(MULTIPLICITY_PACKAGE + "PureOne");
    }

    /**
     * Compile a grammar Multiplicity into a metamodel Multiplicity.
     * <p>
     * If the multiplicity is a MultiplicityParameter, resolve it from scope.
     * If it matches a well-known pattern, the canonical
     * {@code PackageableMultiplicity} is looked up from the model.
     * Otherwise a fresh {@code UserDefinedAdHocMultiplicity} is created.
     *
     * @param grammarMultiplicity the grammar-level multiplicity to compile
     * @param model               the PureModel containing bootstrapped multiplicities
     * @param context             the compilation context for scope lookup (may be null)
     * @return the resolved metamodel Multiplicity
     */
    public static Multiplicity compile(meta.pure.protocol.grammar.multiplicity.Multiplicity grammarMultiplicity, MetadataAccess model, CompilationContext context)
    {
        // Handle multiplicity parameter (e.g. [m])
        if (grammarMultiplicity instanceof meta.pure.protocol.grammar.multiplicity.MultiplicityParameter grammarMulParam)
        {
            return resolveOrCreateMultiplicityParameter(grammarMulParam, model, context);
        }

        // Handle concrete multiplicity with bounds
        if (grammarMultiplicity instanceof meta.pure.protocol.grammar.multiplicity.ConcreteMultiplicity grammarConcrete)
        {
            // Try to resolve to a well-known PackageableMultiplicity
            String canonicalName = resolveCanonicalName(grammarConcrete);
            if (canonicalName != null)
            {
                Multiplicity found = (Multiplicity) model.getElement(MULTIPLICITY_PACKAGE + canonicalName);
                if (found != null)
                {
                    return found;
                }
            }

            // Fall back to an anonymous UserDefinedAdHocMultiplicity
            UserDefinedAdHocMultiplicityImpl result = new UserDefinedAdHocMultiplicityImpl(model);
            if (grammarConcrete._lowerBound() != null)
            {
                result._lowerBound(new MultiplicityValueImpl(model)._value(grammarConcrete._lowerBound()._value()));
            }
            if (grammarConcrete._upperBound() != null)
            {
                result._upperBound(new MultiplicityValueImpl(model)._value(grammarConcrete._upperBound()._value()));
            }
            return result;
        }

        // Fallback: PureOne
        return (Multiplicity) model.getElement(MULTIPLICITY_PACKAGE + "PureOne");
    }

    /**
     * Look up a declared MultiplicityParameter from scope, or create a fresh one.
     */
    private static Multiplicity resolveOrCreateMultiplicityParameter(
            meta.pure.protocol.grammar.multiplicity.MultiplicityParameter grammarMulParam, MetadataAccess model, CompilationContext context)
    {
        if (grammarMulParam == null)
        {
            return null;
        }
        if (context != null)
        {
            PureLanguageCompilerContext plcc = context.compilerContextExtensions(PureLanguageCompilerContext.class);
            if (plcc != null)
            {
                meta.pure.metamodel.multiplicity.MultiplicityParameter scoped = plcc.lookupMultiplicityParameter(grammarMulParam._name());
                if (scoped != null)
                {
                    return scoped;
                }
            }
        }
        return new meta.pure.metamodel.multiplicity.UserDefinedMultiplicityParameterImpl(model)
                ._name(grammarMulParam._name());
    }

    private static String resolveCanonicalName(meta.pure.protocol.grammar.multiplicity.ConcreteMultiplicity m)
    {
        long lower = m._lowerBound() != null ? m._lowerBound()._value() : -1;
        Long upper = m._upperBound() != null ? m._upperBound()._value() : null;
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

    public static meta.pure.metamodel.multiplicity.MultiplicityParameter compileMultiplicityParameter(
            meta.pure.protocol.grammar.multiplicity.MultiplicityParameter grammarMultiplicityParameter,
            meta.pure.metamodel.type.generics.TypeAndMultiplicityParametersOwner owner,
            MetadataAccess model)
    {
        if (grammarMultiplicityParameter == null)
        {
            return null;
        }
        return new meta.pure.metamodel.multiplicity.UserDefinedMultiplicityParameterImpl(model)
                ._name(grammarMultiplicityParameter._name())
                ._owner(owner);
    }
}
