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

package org.finos.legend.pure.specification.generation.model;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;

import static org.finos.legend.pure.specification.generation.model.ModelUtils.bareName;
import static org.finos.legend.pure.specification.generation.model.ModelUtils.collectAllProperties;
import static org.finos.legend.pure.specification.generation.model.ModelUtils.collectAllSubtypes;
import static org.finos.legend.pure.specification.generation.model.ModelUtils.findPointerSource;
import static org.finos.legend.pure.specification.generation.model.ModelUtils.getNonPointerSubtypesForType;
import static org.finos.legend.pure.specification.generation.model.ModelUtils.hasStereotype;
import static org.finos.legend.pure.specification.generation.model.ModelUtils.inlineEligibleSubtypes;
import static org.finos.legend.pure.specification.generation.model.ModelUtils.isAbstract;
import static org.finos.legend.pure.specification.generation.model.ModelUtils.isPointerEncodable;
import static org.finos.legend.pure.specification.generation.model.ModelUtils.isPointerSource;
import static org.finos.legend.pure.specification.generation.model.ModelUtils.isTransientCompilerOnly;
import static org.finos.legend.pure.specification.generation.model.ModelUtils.needsPointerStereotype;
import static org.finos.legend.pure.specification.generation.model.ModelUtils.reachableConcreteSubtypes;

/**
 * Validates the parsed M3 model against the @pointer / nonPointerSubtypes
 * contract before any generator runs. Collects every violation and reports
 * them all at once so an author fixing the TTL sees the full picture.
 *
 * <p>Two checks:</p>
 * <ol>
 *   <li><b>nonPointerSubtypes leafiness:</b> every entry of every
 *       {@code nonPointerSubtypes} tagged value resolves to a concrete
 *       (non-abstract) subtype of the declaring class.</li>
 *   <li><b>@pointer consistency:</b> a property carries the {@code pointer}
 *       stereotype iff at least one runtime value through that slot would have
 *       to be encoded as a pointer (computed via {@link
 *       ModelUtils#needsPointerStereotype}).</li>
 * </ol>
 */
public final class M3ModelValidator
{
    private M3ModelValidator()
    {
    }

    public static void validate(M3Model m3Model)
    {
        MutableList<String> errors = Lists.mutable.empty();
        validateNonPointerSubtypesEntries(m3Model, errors);
        validatePointerSourceInvariants(m3Model, errors);
        validateSubtypeBucketAssignment(m3Model, errors);
        validatePointerStereotypeConsistency(m3Model, errors);
        if (errors.notEmpty())
        {
            StringBuilder sb = new StringBuilder("M3 model validation failed (" + errors.size() + " error(s)):\n");
            for (String e : errors)
            {
                sb.append("  - ").append(e).append('\n');
            }
            throw new IllegalStateException(sb.toString());
        }
    }

    /**
     * Pointer-source roots are foundational definitions: their instances have
     * stable identity (path / owner+name / profile+name). The protocol
     * generator must not exclude them, and a class can have at most one
     * pointer-source ancestor (its definition taxonomy).
     */
    private static void validatePointerSourceInvariants(M3Model m3Model, MutableList<String> errors)
    {
        for (ClassInfo ci : m3Model.classInfoMap().valuesView())
        {
            // Pointer-source classes can't double as compiler-only or excluded.
            if (isPointerSource(ci))
            {
                if (isTransientCompilerOnly(ci))
                {
                    errors.add(ci.name + " carries @pointerSource and @transientCompilerOnly; pointer sources are foundational and must be serialized");
                }
                if (hasStereotype(ci.stereotypes, "excluded"))
                {
                    errors.add(ci.name + " carries @pointerSource and @excluded; pointer sources cannot be excluded from generation");
                }
            }
            // At most one pointer-source ancestor (the definition taxonomy).
            MutableSet<String> seen = Sets.mutable.empty();
            MutableSet<String> sources = Sets.mutable.empty();
            collectAllPointerSourceAncestors(m3Model, ci.name, seen, sources);
            if (sources.size() > 1)
            {
                errors.add(ci.name + " transitively extends multiple @pointerSource classes "
                        + sources + "; a class can have at most one definition taxonomy");
            }
        }
    }

    private static void collectAllPointerSourceAncestors(M3Model m3Model, String name,
                                                         MutableSet<String> seen, MutableSet<String> sources)
    {
        if (!seen.add(name))
        {
            return;
        }
        ClassInfo ci = m3Model.classInfoMap().get(name);
        if (ci == null)
        {
            return;
        }
        if (isPointerSource(ci))
        {
            sources.add(name);
            return; // don't traverse above a pointer-source ancestor
        }
        for (String parent : ci.generalizations)
        {
            collectAllPointerSourceAncestors(m3Model, parent, seen, sources);
        }
    }

    /**
     * For every taxonomy declaring {@code nonPointerSubtypes}, every concrete
     * descendant must be classifiable into exactly one bucket:
     * <ul>
     *   <li>inline: listed in the taxonomy's {@code nonPointerSubtypes}</li>
     *   <li>pointer: extends a pointer-encodable root (PE / AbstractProperty /
     *       Stereotype / Tag)</li>
     *   <li>transient: marked {@code @transientCompilerOnly}</li>
     * </ul>
     * A concrete subtype that fits none is an error: the author has to
     * consciously declare its serialization fate.
     */
    private static void validateSubtypeBucketAssignment(M3Model m3Model, MutableList<String> errors)
    {
        for (ClassInfo ci : m3Model.classInfoMap().valuesView())
        {
            // Only taxonomies that declare nonPointerSubtypes participate.
            // Pure-mainTaxonomy classes (no nonPointerSubtypes) keep their old
            // "all-subtypes-inline" semantics until/unless they opt in.
            boolean declaresList = false;
            for (TaggedValueEntry tv : ci.taggedValues)
            {
                if ("nonPointerSubtypes".equals(bareName(tv.tag)))
                {
                    declaresList = true;
                    break;
                }
            }
            if (!declaresList)
            {
                continue;
            }
            MutableSet<String> inline = Sets.mutable.empty();
            for (String name : getNonPointerSubtypesForType(m3Model, ci.name))
            {
                inline.addAll(reachableConcreteSubtypes(m3Model, name));
            }
            for (String sub : collectAllSubtypes(m3Model, ci.name))
            {
                ClassInfo subCi = m3Model.classInfoMap().get(sub);
                if (subCi == null || isAbstract(subCi))
                {
                    continue;
                }
                if (isTransientCompilerOnly(subCi))
                {
                    continue;
                }
                if (inline.contains(sub))
                {
                    continue;
                }
                if (isPointerEncodable(m3Model, sub))
                {
                    continue;
                }
                errors.add(ci.name + " has unclassified concrete subtype '" + sub
                        + "': add it to nonPointerSubtypes (inline), make it extend a pointer-encodable root, or mark it @transientCompilerOnly");
            }
        }
    }

    /**
     * Every entry of every {@code nonPointerSubtypes} list must be a known
     * concrete subtype of the class that declares the tag.
     */
    private static void validateNonPointerSubtypesEntries(M3Model m3Model, MutableList<String> errors)
    {
        for (ClassInfo ci : m3Model.classInfoMap().valuesView())
        {
            for (TaggedValueEntry tv : ci.taggedValues)
            {
                if (!"nonPointerSubtypes".equals(bareName(tv.tag)))
                {
                    continue;
                }
                MutableSet<String> reachable = reachableConcreteSubtypes(m3Model, ci.name);
                for (String entry : tv.value.split(","))
                {
                    String trimmed = entry.trim();
                    if (trimmed.isEmpty())
                    {
                        continue;
                    }
                    ClassInfo entryClass = m3Model.classInfoMap().get(trimmed);
                    if (entryClass == null)
                    {
                        errors.add(ci.name + ".nonPointerSubtypes references unknown class '" + trimmed + "'");
                        continue;
                    }
                    if (isAbstract(entryClass))
                    {
                        errors.add(ci.name + ".nonPointerSubtypes lists abstract class '" + trimmed
                                + "'; expand to concrete leaf subtypes");
                        continue;
                    }
                    if (!reachable.contains(trimmed))
                    {
                        errors.add(ci.name + ".nonPointerSubtypes lists '" + trimmed
                                + "' which is not a subtype of " + ci.name);
                    }
                }
            }
        }
    }

    /**
     * Every property whose slot could hold a pointer-encoded value at runtime
     * must declare exactly one of:
     * <ul>
     *   <li>{@code @pointer} — the slot is always a path reference. Required
     *       when the property's type has no {@code nonPointerSubtypes} (every
     *       reachable concrete subtype is pointer-encoded).</li>
     *   <li>{@code @maybePointer} — the slot can be a path reference OR one of
     *       the inline subtypes listed by {@code nonPointerSubtypes} on the
     *       type. Required when {@code nonPointerSubtypes} is set.</li>
     *   <li>{@code @nonPointer} — opt out: the slot is always inline despite
     *       its type allowing pointer encoding (e.g., source-of-truth slots
     *       like {@code Section.elements}).</li>
     * </ul>
     * Mismatches (wrong stereotype for the type's shape, or missing stereotype
     * on a pointer-eligible slot) are validation errors so authors must make
     * the encoding decision explicit.
     */
    private static void validatePointerStereotypeConsistency(M3Model m3Model, MutableList<String> errors)
    {
        MutableSet<String> seen = Sets.mutable.empty();
        for (ClassInfo ci : m3Model.classInfoMap().valuesView())
        {
            for (PropertyInfo prop : collectAllProperties(m3Model, ci))
            {
                if (hasStereotype(prop.stereotypes, "excluded"))
                {
                    continue;
                }
                String key = (prop.ownerName == null ? "" : prop.ownerName) + "." + prop.name;
                if (!seen.add(key))
                {
                    continue;
                }
                boolean hasPointer = hasStereotype(prop.stereotypes, "pointer");
                boolean hasMaybe = hasStereotype(prop.stereotypes, "maybePointer");
                boolean hasNonPointer = hasStereotype(prop.stereotypes, "nonPointer");
                int annotationCount = (hasPointer ? 1 : 0) + (hasMaybe ? 1 : 0) + (hasNonPointer ? 1 : 0);
                if (annotationCount > 1)
                {
                    errors.add(key + " declares more than one of @pointer/@maybePointer/@nonPointer; pick exactly one");
                    continue;
                }
                boolean needs = needsPointerStereotype(m3Model, prop);
                // typeHasInlineList = inline subtypes are actually reachable
                // from prop.type (narrower than just "ancestor declares it").
                boolean typeHasInlineList = inlineEligibleSubtypes(m3Model, prop).notEmpty();

                if (!needs)
                {
                    if (hasPointer || hasMaybe)
                    {
                        errors.add(key + " (typed " + prop.typeName
                                + ") declares @" + (hasPointer ? "pointer" : "maybePointer")
                                + " but no pointer-eligible subtype is reachable");
                    }
                    if (hasNonPointer)
                    {
                        errors.add(key + " (typed " + prop.typeName
                                + ") declares @nonPointer but no pointer-eligible subtype is reachable");
                    }
                    continue;
                }
                // pointer-eligible non-empty
                if (hasNonPointer)
                {
                    continue; // explicit opt-out, valid
                }
                if (typeHasInlineList)
                {
                    if (!hasMaybe)
                    {
                        errors.add(key + " (typed " + prop.typeName
                                + ") must declare @maybePointer: type has nonPointerSubtypes (use @nonPointer to opt out)");
                    }
                }
                else
                {
                    if (!hasPointer)
                    {
                        errors.add(key + " (typed " + prop.typeName
                                + ") must declare @pointer: pointer-eligible with no inline subtypes (use @nonPointer to opt out)");
                    }
                }
            }
        }
    }
}
