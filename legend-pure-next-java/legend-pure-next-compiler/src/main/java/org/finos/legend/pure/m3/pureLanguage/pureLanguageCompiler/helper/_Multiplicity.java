package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper;

import meta.pure.metamodel.multiplicity.ConcreteMultiplicityImpl;
import meta.pure.metamodel.multiplicity.InferredMultiplicityImpl;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.multiplicity.MultiplicityValueImpl;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.impl.factory.Sets;
import org.finos.legend.pure.m3.module.MetadataAccess;

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
        if (general._multiplicityParameter() != null || specific._multiplicityParameter() != null)
        {
            return true;
        }

        long generalLower = lowerBound(general);
        long specificLower = lowerBound(specific);
        long generalUpper = upperBound(general);   // -1 means unbounded (*)
        long specificUpper = upperBound(specific);  // -1 means unbounded (*)

        // general.lower must be <= specific.lower
        if (generalLower > specificLower)
        {
            return false;
        }

        // If general is unbounded, it subsumes everything
        if (generalUpper == -1)
        {
            return true;
        }

        // If specific is unbounded but general is not, fail
        if (specificUpper == -1)
        {
            return false;
        }

        // general.upper must be >= specific.upper
        return generalUpper >= specificUpper;
    }

    /**
     * Check multiplicity compatibility with variance awareness.
     * Mirrors {@link _GenericType#isCompatible(GenericType, GenericType, boolean)}.
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
     * Return the lower bound of a multiplicity, defaulting to 0.
     */
    public static long lowerBound(Multiplicity m)
    {
        if (m == null || m._lowerBound() == null)
        {
            return 0;
        }
        return m._lowerBound()._value();
    }

    /**
     * Return the upper bound of a multiplicity.
     * Returns -1 for unbounded (i.e. {@code [*]}).
     */
    public static long upperBound(Multiplicity m)
    {
        if (m == null || m._upperBound() == null)
        {
            return -1; // unbounded
        }
        return m._upperBound()._value();
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
        if (m._multiplicityParameter() != null)
        {
            return "[" + m._multiplicityParameter() + "]";
        }
        long lower = lowerBound(m);
        long upper = upperBound(m);
        if (upper == -1)
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
     * Returns true if the Multiplicity is concrete — i.e. it has no
     * multiplicity parameter reference (e.g. {@code [1]}, {@code [*]} are
     * concrete, but {@code [m]} is not).
     */
    public static boolean isConcrete(Multiplicity multiplicity)
    {
        return multiplicity != null && multiplicity._multiplicityParameter() == null;
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
        if (multiplicity._multiplicityParameter() == null)
        {
            return true;
        }
        return scopeMulParams.contains(multiplicity._multiplicityParameter());
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
        if (paramMul._multiplicityParameter() != null)
        {
            String name = paramMul._multiplicityParameter();
            // Only bind to concrete multiplicities (not other parameters)
            if (argMul._multiplicityParameter() == null)
            {
                bindings.multiplicityBindings().computeIfAbsent(name, k -> Sets.mutable.empty())
                        .add(argMul);
            }
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
        if (multiplicity._multiplicityParameter() != null)
        {
            String name = multiplicity._multiplicityParameter();
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
     * Convenience factory to avoid verbose inline {@code ConcreteMultiplicityImpl} construction.
     *
     * @param lower the lower bound
     * @param upper the upper bound (use -1 for unbounded / {@code [*]})
     * @return a new {@link ConcreteMultiplicityImpl}
     */
    public static Multiplicity concreteMultiplicity(long lower, long upper)
    {
        ConcreteMultiplicityImpl result = new ConcreteMultiplicityImpl()
                ._lowerBound(new MultiplicityValueImpl()._value(lower));
        if (upper >= 0)
        {
            result._upperBound(new MultiplicityValueImpl()._value(upper));
        }
        return result;
    }

    /**
     * Mark a Multiplicity as inferred (compiler-resolved).
     * Returns the named PackageableInferredMultiplicity (InferredPureOne, etc.)
     * for well-known patterns, otherwise creates an InferredMultiplicityImpl.
     */
    public static Multiplicity asInferred(Multiplicity multiplicity, MetadataAccess model)
    {
        if (multiplicity == null || multiplicity instanceof meta.pure.metamodel.Inferred)
        {
            return multiplicity;
        }

        // Try to return a named packageable inferred multiplicity for well-known patterns
        if (multiplicity._multiplicityParameter() == null)
        {
            long lower = lowerBound(multiplicity);
            long upper = upperBound(multiplicity);
            if (lower == 1 && upper == 1)
            {
                return (Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::InferredPureOne");
            }
            if (lower == 0 && upper == 1)
            {
                return (Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::InferredZeroOne");
            }
            if (lower == 0 && upper == -1)
            {
                return (Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::InferredZeroMany");
            }
            if (lower == 1 && upper == -1)
            {
                return (Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::InferredOneMany");
            }
        }

        return copyBoundsInto(multiplicity, new meta.pure.metamodel.multiplicity.InferredMultiplicityImpl());
    }

    private static Multiplicity copyBoundsInto(Multiplicity source, meta.pure.metamodel.multiplicity.InferredMultiplicityImpl target)
    {
        if (source._lowerBound() != null)
        {
            target._lowerBound(new MultiplicityValueImpl()._value(source._lowerBound()._value()));
        }
        if (source._upperBound() != null)
        {
            target._upperBound(new MultiplicityValueImpl()._value(source._upperBound()._value()));
        }
        if (source._multiplicityParameter() != null)
        {
            target._multiplicityParameter(source._multiplicityParameter());
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
    public static Multiplicity findCommonMultiplicity(MutableList<Multiplicity> multiplicities)
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
        long maxUpper = 0;
        for (Multiplicity m : multiplicities)
        {
            long lower = lowerBound(m);
            long upper = upperBound(m);
            minLower = Math.min(minLower, lower);
            maxUpper = (upper == -1 || maxUpper == -1) ? -1 : Math.max(maxUpper, upper);
        }
        InferredMultiplicityImpl result = new InferredMultiplicityImpl();
        result._lowerBound(new MultiplicityValueImpl()._value(minLower));
        if (maxUpper >= 0)
        {
            result._upperBound(new MultiplicityValueImpl()._value(maxUpper));
        }
        return result;
    }
}

