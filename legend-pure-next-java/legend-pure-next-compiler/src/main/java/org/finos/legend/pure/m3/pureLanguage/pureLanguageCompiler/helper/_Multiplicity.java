package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.multiplicity.ConcreteMultiplicity;
import meta.pure.metamodel.multiplicity.InferredAdHocMultiplicityImpl;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.multiplicity.MultiplicityParameter;
import meta.pure.metamodel.multiplicity.MultiplicityValueImpl;
import meta.pure.metamodel.multiplicity.UserDefinedAdHocMultiplicityImpl;
import meta.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Sets;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.ParametersBinding;

/**
 * Helper methods for {@link Multiplicity}.
 */
public final class _Multiplicity
{
    private _Multiplicity()
    {
        // static utility
    }

    /**
     * Check whether the {@code general} multiplicity subsumes (is compatible with)
     * the {@code specific} multiplicity. In other words, a value whose actual
     * multiplicity is {@code specific} can be passed where {@code general} is expected.
     * <p>
     * If either multiplicity has a multiplicity parameter (e.g. {@code [m]}),
     * the check is skipped (returns {@code true}) because the parameter will
     * be resolved at a higher level.
     *
     * @param general  the expected multiplicity (from the parameter declaration)
     * @param specific the actual multiplicity (from the argument at the call site)
     * @return true if {@code general} subsumes {@code specific}
     */
    public static boolean subsumes(Multiplicity general, Multiplicity specific)
    {
        if (general == null || specific == null)
        {
            return true; // unknown — skip
        }

        // If either has a multiplicity parameter, skip concrete checking
        if (general instanceof MultiplicityParameter || specific instanceof MultiplicityParameter)
        {
            return true;
        }

        // Undefined multiplicities match only other undefined multiplicities
        boolean generalUndefined = general instanceof meta.pure.metamodel.multiplicity.UndefinedMultiplicity;
        boolean specificUndefined = specific instanceof meta.pure.metamodel.multiplicity.UndefinedMultiplicity;
        if (generalUndefined || specificUndefined)
        {
            return generalUndefined == specificUndefined;
        }

        long generalLower = lowerBound(general);
        long specificLower = lowerBound(specific);
        Long generalUpper = upperBound(general);
        Long specificUpper = upperBound(specific);

        // general.lower must be <= specific.lower
        if (generalLower > specificLower)
        {
            return false;
        }

        // If general is unbounded, it subsumes everything
        if (generalUpper == null)
        {
            return true;
        }

        // If specific is unbounded but general is not, fail
        if (specificUpper == null)
        {
            return false;
        }

        // general.upper must be >= specific.upper
        return generalUpper >= specificUpper;
    }

    /**
     * Check multiplicity compatibility with variance awareness.
     * Mirrors {@link _GenericType#isCompatible(GenericType, GenericType, MetadataAccess)}.
     * <p>
     * When {@code contravariant} is false (covariant): declared must subsume actual.
     * When {@code contravariant} is true: actual must subsume declared.
     *
     * @param declared      the declared multiplicity
     * @param actual        the actual multiplicity
     * @param contravariant whether the context is contravariant
     * @return true if the multiplicities are compatible
     */
    public static boolean isCompatible(Multiplicity declared, Multiplicity actual, boolean contravariant)
    {
        return contravariant ? subsumes(actual, declared) : subsumes(declared, actual);
    }

    /**
     * Returns true if the multiplicity is [*] (0..*) — the universal multiplicity
     * that subsumes all others.
     */
    public static boolean isUniversal(Multiplicity m)
    {
        return m instanceof ConcreteMultiplicity && lowerBound(m) == 0 && upperBound(m) == null;
    }

    /**
     * Return the lower bound of a multiplicity, defaulting to 0.
     */
    public static long lowerBound(Multiplicity m)
    {
        if (m instanceof ConcreteMultiplicity cm && cm._lowerBound() != null)
        {
            return cm._lowerBound()._value();
        }
        return 0;
    }

    /**
     * Return the upper bound of a multiplicity.
     * Uses Long.MAX_VALUE to represent an unlimited ('*') upper bound.
     */
    public static Long upperBound(Multiplicity m)
    {
        if (m instanceof ConcreteMultiplicity cm && cm._upperBound() != null)
        {
            return cm._upperBound()._value();
        }
        return null; // unbounded
    }

    /**
     * Check if a multiplicity is singular (upper bound is 1).
     */
    public static boolean isSingular(Multiplicity m)
    {
        Long upper = upperBound(m);
        return upper != null && upper <= 1;
    }

    /**
     * Format a multiplicity for display in error messages.
     */
    public static String print(Multiplicity m)
    {
        if (m == null)
        {
            return "[*]";
        }
        if (m instanceof MultiplicityParameter mp)
        {
            return "[" + mp._name() + "]";
        }
        if (m instanceof meta.pure.metamodel.multiplicity.UndefinedMultiplicity)
        {
            return "[?]";
        }
        long lower = lowerBound(m);
        Long upper = upperBound(m);
        if (upper == null)
        {
            return lower == 0 ? "[*]" : "[" + lower + "..*]";
        }
        if (lower == upper)
        {
            return "[" + lower + "]";
        }
        return "[" + lower + ".." + upper + "]";
    }

    /**
     * Format a multiplicity without brackets, for use in type argument notation (e.g. {@code <T|1>}).
     */
    public static String printBare(Multiplicity m)
    {
        if (m == null)
        {
            return "*";
        }
        if (m instanceof MultiplicityParameter mp)
        {
            return mp._name();
        }
        if (m instanceof meta.pure.metamodel.multiplicity.UndefinedMultiplicity)
        {
            return "?";
        }
        long lower = lowerBound(m);
        Long upper = upperBound(m);
        if (upper == null)
        {
            return lower == 0 ? "*" : lower + "..*";
        }
        if (upper == lower)
        {
            return String.valueOf(lower);
        }
        return lower + ".." + upper;
    }

    /**
     * Returns true if the Multiplicity is concrete — i.e. it has no
     * multiplicity parameter reference (e.g. {@code [1]}, {@code [*]} are
     * concrete, but {@code [m]} is not).
     */
    public static boolean isConcrete(Multiplicity multiplicity)
    {
        return multiplicity != null && !(multiplicity instanceof MultiplicityParameter);
    }

    /**
     * Returns true if the Multiplicity is concrete in the given context —
     * i.e. it has no multiplicity parameter, or its parameter is
     * an in-scope enclosing parameter (which is valid).
     *
     * @param multiplicity     the multiplicity to check
     * @param scopeMulParams   the set of multiplicity parameter names that are in scope
     */
    public static boolean isConcreteInContext(Multiplicity multiplicity, MutableSet<String> scopeMulParams)
    {
        if (multiplicity == null)
        {
            return false;
        }
        if (!(multiplicity instanceof MultiplicityParameter mp))
        {
            return true;
        }
        return scopeMulParams.contains(mp._name());
    }

    /**
     * Walk a parameter multiplicity and an argument multiplicity in parallel.
     * When the parameter side references a multiplicity parameter (e.g. {@code m}),
     * record the argument side's concrete multiplicity in the bindings map.
     *
     * @param paramMul the parameter's multiplicity
     * @param argMul   the argument's multiplicity
     * @param bindings the parameter bindings to store the collected bindings
     */
    public static void collectMultiplicityParameterBindings(
            Multiplicity paramMul,
            Multiplicity argMul,
            ParametersBinding bindings)
    {
        if (paramMul == null || argMul == null)
        {
            return;
        }
        // If the parameter side has a multiplicity parameter, bind it
        // (including binding to other multiplicity parameters for transitive resolution)
        if (paramMul instanceof MultiplicityParameter mp)
        {
            String name = mp._name();
            bindings.multiplicityBindings().computeIfAbsent(name, k -> Sets.mutable.empty())
                    .add(argMul);
        }
    }

    /**
     * Substitute a multiplicity parameter reference with its bound concrete
     * multiplicity from the provided bindings. If the multiplicity has a
     * parameter name and a binding exists for it, return the bound value.
     * Otherwise return the multiplicity as-is.
     *
     * @param multiplicity the multiplicity to make concrete
     * @param bindings     the parameter bindings with multiplicity mappings
     * @return a concrete multiplicity with the parameter substituted, or the original
     */
    public static Multiplicity makeAsConcreteAsPossible(
            Multiplicity multiplicity,
            ParametersBinding bindings)
    {
        if (multiplicity == null || bindings == null || bindings.multiplicityBindings().isEmpty())
        {
            return multiplicity;
        }
        if (multiplicity instanceof MultiplicityParameter mp)
        {
            String name = mp._name();
            MutableSet<Multiplicity> boundMuls = bindings.multiplicityBindings().get(name);
            if (boundMuls != null && boundMuls.notEmpty())
            {
                // Use the first binding (all should be consistent if matching passed)
                return boundMuls.getFirst();
            }
        }
        return multiplicity;
    }

    /**
     * Create a concrete multiplicity with the given lower and upper bounds.
     * Convenience factory to avoid verbose inline construction.
     *
     * @param lower the lower bound
     * @param upper the upper bound (use a negative value for unbounded / {@code [*]})
     * @return a new concrete Multiplicity
     */
    public static Multiplicity concreteMultiplicity(long lower, long upper, MetadataAccess model)
    {
        UserDefinedAdHocMultiplicityImpl result = new UserDefinedAdHocMultiplicityImpl(model)
                ._lowerBound(new MultiplicityValueImpl(model)._value(lower));
        if (upper >= 0)
        {
            result._upperBound(new MultiplicityValueImpl(model)._value(upper));
        }
        return result;
    }

    /**
     * Mark a Multiplicity as inferred (compiler-resolved).
     * Returns the named InferredPackageableMultiplicity (InferredPureOne, etc.)
     * for well-known patterns, otherwise creates an InferredAdHocMultiplicityImpl.
     * MultiplicityParameter instances are returned as-is since they have no bounds.
     */
    public static Multiplicity asInferred(Multiplicity multiplicity, MetadataAccess model)
    {
        if (multiplicity == null || multiplicity instanceof meta.pure.metamodel.Inferred
                || multiplicity instanceof meta.pure.metamodel.multiplicity.UndefinedMultiplicity)
        {
            return multiplicity;
        }

        // Convert multiplicity parameters to their inferred counterpart
        if (multiplicity instanceof MultiplicityParameter mp)
        {
            if (multiplicity instanceof meta.pure.metamodel.Inferred)
            {
                return multiplicity;
            }
            return new meta.pure.metamodel.multiplicity.InferredMultiplicityParameterImpl(model)
                    ._name(mp._name())
                    ._owner(mp._owner());
        }

        // Try to return a named packageable inferred multiplicity for well-known patterns
        long lower = lowerBound(multiplicity);
        Long upper = upperBound(multiplicity);
        if (lower == 1 && upper != null && upper == 1)
        {
            return (Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::InferredPureOne");
        }
        if (lower == 0 && upper != null && upper == 1)
        {
            return (Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::InferredZeroOne");
        }
        if (lower == 0 && upper == null)
        {
            return (Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::InferredZeroMany");
        }
        if (lower == 1 && upper == null)
        {
            return (Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::InferredOneMany");
        }

        return copyBoundsInto(multiplicity, new InferredAdHocMultiplicityImpl(model), model);
    }

    private static Multiplicity copyBoundsInto(Multiplicity source, InferredAdHocMultiplicityImpl target, MetadataAccess model)
    {
        if (source instanceof ConcreteMultiplicity cm)
        {
            if (cm._lowerBound() != null)
            {
                target._lowerBound(new MultiplicityValueImpl(model)._value(cm._lowerBound()._value()));
            }
            if (cm._upperBound() != null)
            {
                target._upperBound(new MultiplicityValueImpl(model)._value(cm._upperBound()._value()));
            }
        }
        return target;
    }

    /**
     * Find the common (widest) multiplicity for a list of multiplicities.
     * Takes the minimum lower bound and maximum upper bound across all inputs.
     *
     * @param multiplicities the multiplicities to unify
     * @return the common multiplicity
     */
    public static Multiplicity findCommonMultiplicity(MutableList<Multiplicity> multiplicities, MetadataAccess model)
    {
        if (multiplicities == null || multiplicities.isEmpty())
        {
            return null;
        }
        if (multiplicities.size() == 1)
        {
            return multiplicities.getFirst();
        }
        long minLower = Long.MAX_VALUE;
        Long maxUpper = 0L;
        for (Multiplicity m : multiplicities)
        {
            long lower = lowerBound(m);
            Long upper = upperBound(m);
            minLower = Math.min(minLower, lower);
            maxUpper = (upper == null || maxUpper == null) ? null : Math.max(maxUpper, upper);
        }
        InferredAdHocMultiplicityImpl result = new InferredAdHocMultiplicityImpl(model);
        result._lowerBound(new MultiplicityValueImpl(model)._value(minLower));
        if (maxUpper != null)
        {
            result._upperBound(new MultiplicityValueImpl(model)._value(maxUpper));
        }
        return result;
    }
}
