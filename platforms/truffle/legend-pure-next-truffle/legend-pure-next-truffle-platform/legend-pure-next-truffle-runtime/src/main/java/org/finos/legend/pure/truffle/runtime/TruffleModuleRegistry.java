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

package org.finos.legend.pure.truffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.finos.legend.pure.truffle.runtime.helper.TypeCache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the set of {@link TruffleModule}s a {@code PureContext} can resolve
 * against, and provides the composite {@link TruffleMetadataAccess} that
 * FlatBuffer wrappers, native nodes, and helpers consume.
 *
 * <p>Replaces the anonymous-class composite resolvers that used to be
 * defined inline in {@code TrufflePureTestRunner}, {@code TruffleCompileToPdbTest},
 * and {@code TruffleCompilerBinaryBuilder}. Centralising them here:</p>
 * <ul>
 *   <li>gives every module a stable {@link TruffleModule#name() name} and
 *       declared {@link TruffleModule#dependencies() dependencies};</li>
 *   <li>makes the reverse lookup ({@link #moduleOf(Object)} / {@link
 *       #moduleOfPath(String)}) cheap — needed by per-module caches
 *       (compiled function bodies, classifier-generic-type singletons,
 *       and shapes for user-defined classes);</li>
 *   <li>gives the system a real {@link #unregister(String) unregister}
 *       hook so a recompile can drop a module's state without touching
 *       unrelated modules.</li>
 * </ul>
 *
 * <p>Resolution walks modules in registration order (insertion-ordered
 * map). Callers register dependencies before dependents — typically
 * {@code core} first, then {@code compiler}, then any user PDBs.</p>
 */
public final class TruffleModuleRegistry implements TruffleMetadataAccess
{

    private static final int SLOT_FUNCTION_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("functionName");
    private static final int SLOT_PARAMETERS = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("parameters");
    private static final int SLOT_PROPERTIES = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("properties");
    private static final int SLOT_PROPERTY_NAME = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("name");
    private static final int SLOT_PROPERTY_GENERIC_TYPE = org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("genericType");
    private final LinkedHashMap<String, TruffleModule> modules = new LinkedHashMap<>();
    private final TypeCache typeCache = new TypeCache();
    // Lazy index keyed by (function shortName, arity) → resolved
    //   {@code PackageableFunction}s. Built on first access from
    //   {@link #functionsByNameAndArity(String,int)} by walking every module's
    //   {@link TruffleModule#elementPaths()} once and resolving each function
    //   path to confirm its name (path mangling is ambiguous, e.g.
    //   {@code unify} vs {@code unify_step1_pairwiseBind}) and parameter count.
    //   Replaces a per-call linear scan in
    //   {@code FindFunctionsByNameAndArityNode} (8.5% of self-host CPU when
    //   measured); after this index every lookup is two map gets. Invalidated
    //   on register / unregister so newly registered modules' functions become
    //   visible without forcing callers through a rebuild.
    private Map<String, Map<Integer, List<Object>>> functionsByNameArity;
    // Lazy index: property name → list of (otherPropName, otherEndClassPath) pairs,
    // one entry per Association involving a property of that name. Lets
    // {@code ^Person(firm=$firm)} look up "the property on Firm whose value is
    // built from this side" in O(1). Built by walking every Association across
    // registered modules; invalidated on register/unregister so cross-module
    // associations (e.g. test-only LA_* associations in core-tests.pdb) become
    // visible the moment their module is registered. Previously cached as a
    // static volatile on {@code NewWithKeysNode} which assumed one resolver per
    // JVM — broke when a surefire JVM ran tests with different module sets.
    private Map<String, List<String[]>> reverseAssocIndex;

    /**
     * Register a module. Validates that all declared dependencies are already
     * present so resolution order is unambiguous.
     */
    public void register(TruffleModule module)
    {
        for (String dep : module.dependencies())
        {
            if (!modules.containsKey(dep))
            {
                throw new IllegalStateException(
                        "Module '" + module.name() + "' declares dependency '" + dep
                                + "' which is not registered. Register dependencies first.");
            }
        }
        if (modules.containsKey(module.name()))
        {
            throw new IllegalStateException(
                    "Module '" + module.name() + "' is already registered. "
                            + "Call unregister(name) before re-registering.");
        }
        // Uniqueness check: a module may not declare a path that an already-
        // registered module already declares. A path collision means we'd
        // have two Java instances representing the same Pure element, which
        // breaks identity comparisons (`is`) and corrupts the type cache.
        // Compile-pure should never emit a top-level element at a path that
        // already exists in a dep PDB — if this fires, the compile result has
        // a duplicate of a core/compiler element and we want to find the bug,
        // not silently dedup.
        //
        // Exception: Package PDOs are allowed to collide — multiple modules
        // legitimately contribute children to the same package path (lean PDB
        // owns the canonical Package, companion {@code -tests} PDB contributes
        // a shadow Package with only test children, or two unrelated modules
        // happen to share a package path). {@link #getElement(String)} returns
        // a {@link MergedPackagePDO} that unions the children across all
        // contributors; the existing Package instances stay live, identity
        // comparisons still work for non-Package elements.
        java.util.List<String> collisions = new java.util.ArrayList<>();
        java.util.List<String> collisionOwners = new java.util.ArrayList<>();
        for (String path : module.elementPaths())
        {
            for (TruffleModule existing : modules.values())
            {
                if (existing.hasElement(path))
                {
                    Object hereElement = module.getElement(path);
                    if (org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(
                            hereElement, "meta::pure::metamodel::Package"))
                    {
                        continue;
                    }
                    collisions.add(path);
                    collisionOwners.add(existing.name());
                    if (collisions.size() >= 10) break;
                }
            }
            if (collisions.size() >= 10) break;
        }
        if (!collisions.isEmpty())
        {
            StringBuilder sb = new StringBuilder("Module '").append(module.name())
                    .append("' declares paths already present in registered module(s): ");
            for (int i = 0; i < collisions.size(); i++)
            {
                sb.append("\n  ").append(collisions.get(i))
                        .append("  (owned by '").append(collisionOwners.get(i)).append("')");
            }
            throw new IllegalStateException(sb.toString());
        }
        modules.put(module.name(), module);
        functionsByNameArity = null;
        reverseAssocIndex = null;
        // Drop the element cache. Without this, any path the new module owns
        // that some earlier lookup tried (and missed, caching {@link #ABSENT_PATH})
        // would keep returning null forever — including class-info population
        // for the new module's user classes. Compile-pure execution routinely
        // calls {@link #getElement} for not-yet-registered user paths during
        // its compile, so this staleness manifests as silent class-info
        // population failures (writeProperty: ... has no property ...).
        elementCache.clear();
    }

    /**
     * Manifest-level dependency check: every registered module's declared
     * {@code dependencies()} must itself be registered. {@link #register}
     * already enforces this at registration time (deps must be registered
     * first), so the explicit {@code validate()} is mostly a "setup is done,
     * confirm the closure" assertion — useful for CLI entry points where the
     * caller may register in any order or programmatically.
     *
     * <p>Reports every missing dependency (not just the first) so a caller
     * with multiple gaps sees them all at once.</p>
     */
    public void validate()
    {
        java.util.List<String> errors = new java.util.ArrayList<>();
        for (TruffleModule m : modules.values())
        {
            for (String dep : m.dependencies())
            {
                if (!modules.containsKey(dep))
                {
                    errors.add("module '" + m.name() + "' declares dependency '" + dep + "' which is not registered");
                }
            }
        }
        if (!errors.isEmpty())
        {
            StringBuilder sb = new StringBuilder("Registry validation failed (")
                    .append(errors.size()).append(" missing dependencies):");
            for (String e : errors) sb.append("\n  - ").append(e);
            throw new IllegalStateException(sb.toString());
        }
    }

    /**
     * Unregister a module and cascade-invalidate any module that depends on
     * it. After this returns, the named module and every transitive dependent
     * are gone from the registry; their associated state (caches, shapes,
     * etc.) becomes garbage as soon as the caller drops references.
     */
    public void unregister(String name)
    {
        TruffleModule removed = modules.get(name);
        if (removed == null)
        {
            return;
        }
        // Snapshot dependents before removing, so we can recurse.
        List<String> dependents = new ArrayList<>();
        for (TruffleModule m : modules.values())
        {
            if (m.dependencies().contains(name))
            {
                dependents.add(m.name());
            }
        }
        modules.remove(name);
        // Symmetric teardown of any Package-children mutations the removed
        // module made via {@link #foldChildrenInto}. For every Package path
        // the removed module contributed, find the remaining anchor Package
        // (first contributor among the still-registered modules) and
        // identity-remove the removed module's own Package children from
        // it. This is the lifecycle inverse of register/resolveAndMerge:
        // when the module was a non-anchor contributor, its children got
        // folded into the anchor, and now we undo that fold; when it was
        // the anchor itself, the mutated anchor PDO disappears with the
        // module and the new anchor's children are pristine, so the
        // identity-remove loop is a harmless no-op. Uniform across PDB and
        // in-memory modules.
        for (String path : removed.elementPaths())
        {
            Object removedElement = removed.getElement(path);
            if (!(removedElement instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject removedPkg)
                    || !org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(
                            removedPkg, "meta::pure::metamodel::Package"))
            {
                continue;
            }
            org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject newAnchor = null;
            for (TruffleModule m : modules.values())
            {
                Object e = m.getElement(path);
                if (e instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdo
                        && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(
                                pdo, "meta::pure::metamodel::Package"))
                {
                    newAnchor = pdo;
                    break;
                }
            }
            if (newAnchor == null)
            {
                continue;
            }
            Object kidsObj = removedPkg.readSlot(SLOT_CHILDREN);
            if (!(kidsObj instanceof org.finos.legend.pure.truffle.types.PureSequence removedKids))
            {
                continue;
            }
            List<Object> toRemove = new ArrayList<>(removedKids.size());
            for (int i = 0; i < removedKids.size(); i++)
            {
                Object k = removedKids.getBoxed(i);
                if (k != null) toRemove.add(k);
            }
            removeChildrenByIdentity(newAnchor, toRemove);
        }
        // Drop cached element lookups owned by this module — otherwise
        // re-registering with fresh content (e.g. an IDE recompile of
        // welcome.pure) would still serve the old objects from the cache.
        // The cache documents itself as "stable for the registry's lifetime,"
        // which holds for PDB modules but not for in-memory modules we
        // unregister + re-register.
        for (String path : removed.elementPaths())
        {
            elementCache.remove(path);
        }
        functionsByNameArity = null;
        reverseAssocIndex = null;
        for (String d : dependents)
        {
            unregister(d);
        }
    }

    /**
     * Lookup resolved {@code PackageableFunction}s by short name and arity.
     * O(1) — backed by an index keyed by (functionName, parameterCount) that's
     * built lazily on first access by resolving every module's element paths.
     * Returns the empty list when no function matches; never falls back to a
     * scan.
     */
    @TruffleBoundary
    public List<Object> functionsByNameAndArity(String name, int arity)
    {
        Map<String, Map<Integer, List<Object>>> idx = functionsByNameArity;
        if (idx == null)
        {
            idx = buildFunctionIndex();
            functionsByNameArity = idx;
        }
        Map<Integer, List<Object>> byArity = idx.get(name);
        if (byArity == null)
        {
            return List.of();
        }
        List<Object> hits = byArity.get(arity);
        return hits != null ? hits : List.of();
    }

    @TruffleBoundary
    private Map<String, Map<Integer, List<Object>>> buildFunctionIndex()
    {
        Map<String, Map<Integer, List<Object>>> idx = new HashMap<>();
        for (TruffleModule m : modules.values())
        {
            for (String path : m.elementPaths())
            {
                Object element = getElement(path);
                if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(element,
                        "meta::pure::metamodel::function::PackageableFunction", this))
                {
                    continue;
                }
                Object fnNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(element, SLOT_FUNCTION_NAME);
                if (!(fnNameObj instanceof String fnName))
                {
                    continue;
                }
                Object paramsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(element, SLOT_PARAMETERS);
                int arity = paramsObj instanceof org.finos.legend.pure.truffle.types.PureSequence ps ? ps.size() : 0;
                idx.computeIfAbsent(fnName, k -> new HashMap<>())
                   .computeIfAbsent(arity, k -> new ArrayList<>())
                   .add(element);
            }
        }
        return idx;
    }

    /**
     * Lookup the reverse-association entries for a given property name.
     * Returns a list of {@code [otherPropName, otherEndClassPath]} pairs,
     * one per Association declaring a property of that name across the
     * registered modules. Empty when no association uses the name.
     *
     * <p>O(1) after first call — backed by an index built lazily and
     * invalidated on every {@link #register} / {@link #unregister}, so a
     * test fixture that registers a tests-companion PDB after construction
     * still sees its associations the next time this is called.</p>
     */
    @TruffleBoundary
    public List<String[]> reverseAssociationCandidates(String propName)
    {
        Map<String, List<String[]>> idx = reverseAssocIndex;
        if (idx == null)
        {
            idx = buildReverseAssocIndex();
            reverseAssocIndex = idx;
        }
        List<String[]> hits = idx.get(propName);
        return hits != null ? hits : List.of();
    }

    @TruffleBoundary
    private Map<String, List<String[]>> buildReverseAssocIndex()
    {
        Map<String, List<String[]>> index = new HashMap<>();
        for (TruffleModule m : modules.values())
        {
            for (String path : m.elementPaths())
            {
                Object element = getElement(path);
                if (!org.finos.legend.pure.truffle.runtime.dynobj.PureObj.isType(element,
                        "meta::pure::metamodel::relationship::Association", this))
                {
                    continue;
                }
                Object propsObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(element, SLOT_PROPERTIES);
                if (!(propsObj instanceof org.finos.legend.pure.truffle.types.PureSequence props) || props.size() != 2)
                {
                    continue;
                }
                Object p0 = props.getBoxed(0);
                Object p1 = props.getBoxed(1);
                if (p0 != null && p1 != null)
                {
                    addAssocEntry(index, p0, p1);
                    addAssocEntry(index, p1, p0);
                }
            }
        }
        return index;
    }

    @TruffleBoundary
    private void addAssocEntry(Map<String, List<String[]>> index, Object matchProp, Object otherProp)
    {
        Object propNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(matchProp, SLOT_PROPERTY_NAME);
        if (!(propNameObj instanceof String propName)) return;
        Object otherGT = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(otherProp, SLOT_PROPERTY_GENERIC_TYPE);
        if (otherGT == null) return;
        Object targetType = org.finos.legend.pure.truffle.runtime.helper._GenericType.type(otherGT);
        if (targetType == null) return;
        String targetPath = org.finos.legend.pure.truffle.runtime.helper._PackageableElement.path(targetType, this);
        if (targetPath == null) return;
        Object otherNameObj = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.readBySlot(otherProp, SLOT_PROPERTY_NAME);
        if (otherNameObj instanceof String otherName)
        {
            index.computeIfAbsent(propName, k -> new ArrayList<>(2))
                    .add(new String[]{otherName, targetPath});
        }
    }

    /** All registered modules, in dependency-first order. */
    public Collection<TruffleModule> modules()
    {
        return modules.values();
    }

    public TruffleModule module(String name)
    {
        return modules.get(name);
    }

    /**
     * Find which module owns the given Pure path. Walks modules in registration
     * order and returns the first that {@link TruffleMetadataAccess#hasElement
     * hasElement(path)}.
     */
    public TruffleModule moduleOfPath(String path)
    {
        for (TruffleModule m : modules.values())
        {
            if (m.hasElement(path))
            {
                return m;
            }
        }
        return null;
    }

    /**
     * Find which module owns the given element instance (one previously
     * returned from {@link #getElement}). Uses each module's reverse-lookup
     * map; returns null for runtime-created instances that no module owns.
     */
    public TruffleModule moduleOf(Object element)
    {
        for (TruffleModule m : modules.values())
        {
            if (m.pathOf(element) != null)
            {
                return m;
            }
        }
        return null;
    }

    @Override
    public Object getElement(String path)
    {
        // Cache: string-keyed lookups dominate the JFR for the
        // metamodel_factories.pure compile (~40 samples on cumulative
        // resolver.getElement paths). The result is stable for the
        // registry's lifetime — once a module has registered an element,
        // it's the canonical instance. ConcurrentHashMap keeps the read
        // path lock-free; the {@link #ABSENT_PATH} sentinel caches
        // not-found results so repeated lookups for missing paths don't
        // re-iterate the module list.
        Object cached = elementCache.get(path);
        if (cached != null)
        {
            return cached == ABSENT_PATH ? null : cached;
        }
        // Resolve OUTSIDE any ConcurrentHashMap compute*-lambda. Reason:
        // {@link #foldChildrenInto} reads the contributing Package PDOs'
        // children slot, which triggers PointerRef resolution that re-enters
        // {@code getElement} for child paths. JDK ≥ 9 throws
        // "Recursive update" on any reentrant compute* call to the same
        // map, even for a different key, so the lambda form is unusable.
        // Manual get + putIfAbsent is fine: per-anchor synchronisation in
        // {@code foldChildrenInto} serialises concurrent merges of the same
        // path, and re-folding is idempotent (LinkedHashSet dedupe).
        Object resolved = resolveAndMerge(path);
        if (resolved == ABSENT_PATH) return null;
        Object existing = elementCache.putIfAbsent(path, resolved);
        return existing != null ? (existing == ABSENT_PATH ? null : existing) : resolved;
    }

    private Object resolveAndMerge(String path)
    {
        Object first = null;
        org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject anchorPdo = null;
        for (TruffleModule m : modules.values())
        {
            Object e = m.getElement(path);
            if (e == null) continue;
            if (first == null)
            {
                first = e;
                // Only Package paths can legitimately stack across modules
                // (the register() collision check rejects every other
                // duplicate). Capture the anchor PDO here so additional
                // contributors fold straight into its children slot.
                if (e instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject pdo
                        && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(
                                pdo, "meta::pure::metamodel::Package"))
                {
                    anchorPdo = pdo;
                }
                continue;
            }
            // Additional contributors beyond the first: every collision
            // must be a Package (register() enforces this), so fold its
            // children into the anchor PDO. Identity stays with the
            // anchor — callers comparing two Packages at this path via
            // {@code is} continue to see the same instance.
            if (anchorPdo != null
                    && e instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject otherPdo
                    && org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(
                            otherPdo, "meta::pure::metamodel::Package"))
            {
                foldChildrenInto(anchorPdo, otherPdo);
            }
        }
        return first == null ? ABSENT_PATH : first;
    }

    private static final int SLOT_CHILDREN =
            org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("children");
    private static final int SLOT_PACKAGE =
            org.finos.legend.pure.truffle.runtime.dynobj.PureClassRegistry.globalSlot("package");

    private static void foldChildrenInto(
            org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject anchor,
            org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject other)
    {
        // Per-anchor synchronisation so two threads racing on the same path
        // can't interleave reads/writes of the children slot.
        synchronized (anchor)
        {
            Object otherKidsObj = other.readSlot(SLOT_CHILDREN);
            if (!(otherKidsObj instanceof org.finos.legend.pure.truffle.types.PureSequence otherKids)
                    || otherKids.size() == 0)
            {
                return;
            }
            Object anchorKidsObj = anchor.readSlot(SLOT_CHILDREN);
            // Dedupe by child element name (not PDO identity). Two distinct
            // PDOs at the same name are either a benign Package collision
            // (two modules synthesise the same sub-namespace — kept once,
            // their own resolveAndMerge folds their children later) or a
            // real bug (two modules both define a Function / Class at the
            // same path) — fail loudly per the registry's uniqueness rule
            // for non-Package elements.
            java.util.LinkedHashMap<String, Object> byName = new java.util.LinkedHashMap<>();
            if (anchorKidsObj instanceof org.finos.legend.pure.truffle.types.PureSequence anchorKids)
            {
                for (int i = 0; i < anchorKids.size(); i++)
                {
                    Object k = anchorKids.getBoxed(i);
                    if (k == null) continue;
                    putOrFailOnDuplicate(byName, k);
                }
            }
            for (int i = 0; i < otherKids.size(); i++)
            {
                Object k = otherKids.getBoxed(i);
                if (k == null) continue;
                putOrFailOnDuplicate(byName, k);
            }
            anchor.writeSlot(SLOT_CHILDREN,
                    new org.finos.legend.pure.truffle.types.ObjectSequence(byName.values().toArray()));
            // Symmetric with attaching to anchor.children: every surviving
            // child's `.package` back-reference must point at the anchor that
            // now owns it. Compile-pure / PDB-load sets `.package` to whichever
            // Package PDO the producing module synthesised — which for an
            // inmem-built child means the inmem-side Package, not the
            // canonical anchor. Without this rewrite, walking
            // `pathToElement(parent).children[i].package` returns a different
            // PDO than `pathToElement(parent)` itself (identity inconsistency
            // that breaks `assertIs` on Package references). Idempotent for
            // anchor's own original children (their `.package` already is
            // anchor) and harmless for nulls/non-PDOs. No matching teardown
            // is needed because unregistering a module drops its child PDOs
            // entirely — the rewritten back-reference disappears with the
            // child, leaving nothing dangling.
            for (Object k : byName.values())
            {
                if (k instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject child)
                {
                    // Write unconditionally: reading the slot first would
                    // trigger lazy PDB decode for FlatBuffer-loaded children,
                    // which re-enters the resolver and recurses back through
                    // resolveAndMerge -> foldChildrenInto -> readSlot ad
                    // infinitum (StackOverflow). The write itself is a
                    // direct slot set, no decoding involved.
                    child.writeSlot(SLOT_PACKAGE, anchor);
                }
            }
        }
    }

    /**
     * Insert {@code k} into {@code byName} keyed on its child element name
     * (read from the global {@code "name"} slot). If a different PDO is
     * already present at that name, allow it only when both are
     * {@code Package} PDOs (a benign cross-module namespace collision —
     * the sub-Package's own {@link #resolveAndMerge} will fold their
     * deeper children later); throw otherwise so non-Package duplicates
     * surface immediately instead of being silently merged.
     */
    private static void putOrFailOnDuplicate(
            java.util.LinkedHashMap<String, Object> byName, Object k)
    {
        Object nameObj = ((org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject) k)
                .readSlot(SLOT_PROPERTY_NAME);
        if (!(nameObj instanceof String name))
        {
            throw new IllegalStateException(
                    "Package child has no String 'name' slot value; class="
                            + (k == null ? "null" : k.getClass().getName()));
        }
        Object existing = byName.get(name);
        if (existing == null || existing == k)
        {
            byName.put(name, k);
            return;
        }
        boolean existingIsPkg = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(
                existing, "meta::pure::metamodel::Package");
        boolean newIsPkg = org.finos.legend.pure.truffle.runtime.dynobj.PureObj.pureTypeIs(
                k, "meta::pure::metamodel::Package");
        if (existingIsPkg && newIsPkg)
        {
            // Benign Package collision — keep existing anchor; the sub-Package's
            // own resolveAndMerge handles the deeper children merge.
            return;
        }
        throw new IllegalStateException(
                "Duplicate child '" + name + "' while merging Package children: "
                        + "non-Package collision (existing=" + describePdo(existing)
                        + ", new=" + describePdo(k) + "). "
                        + "The registry's uniqueness rule should have rejected this at register-time; "
                        + "if you see this, two modules are claiming the same Pure path for a non-Package element.");
    }

    private static String describePdo(Object pdo)
    {
        if (!(pdo instanceof org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject p))
        {
            return String.valueOf(pdo);
        }
        String cls = p.classInfo == null ? p.getClass().getSimpleName() : p.classInfo.purePath;
        return cls + "@" + System.identityHashCode(p);
    }

    /**
     * Identity-remove children from a Package PDO's children slot. Used by
     * {@link #unregister} to undo the {@link #foldChildrenInto} contributions
     * an unregistering module made to a remaining anchor Package: pass the
     * unregistering module's own Package PDO's children as {@code toRemove}
     * — those are the references that were folded into the anchor, and only
     * those are removed (so contributions from other still-registered
     * modules survive).
     */
    private static void removeChildrenByIdentity(
            org.finos.legend.pure.truffle.runtime.dynobj.PureDynamicObject anchor,
            java.util.List<Object> toRemove)
    {
        if (toRemove.isEmpty())
        {
            return;
        }
        synchronized (anchor)
        {
            Object kidsObj = anchor.readSlot(SLOT_CHILDREN);
            if (!(kidsObj instanceof org.finos.legend.pure.truffle.types.PureSequence kids))
            {
                return;
            }
            java.util.Set<Object> removeSet = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>(toRemove.size() * 2));
            removeSet.addAll(toRemove);
            java.util.List<Object> kept = new java.util.ArrayList<>(kids.size());
            for (int i = 0; i < kids.size(); i++)
            {
                Object k = kids.getBoxed(i);
                if (!removeSet.contains(k))
                {
                    kept.add(k);
                }
            }
            anchor.writeSlot(SLOT_CHILDREN,
                    new org.finos.legend.pure.truffle.types.ObjectSequence(kept.toArray()));
        }
    }

    private static final Object ABSENT_PATH = new Object();
    private final java.util.concurrent.ConcurrentHashMap<String, Object> elementCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public boolean hasElement(String path)
    {
        for (TruffleModule m : modules.values())
        {
            if (m.hasElement(path))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public Set<String> elementPaths()
    {
        Set<String> all = new LinkedHashSet<>();
        for (TruffleModule m : modules.values())
        {
            all.addAll(m.elementPaths());
        }
        return all;
    }

    @Override
    public String pathOf(Object element)
    {
        for (TruffleModule m : modules.values())
        {
            String p = m.pathOf(element);
            if (p != null)
            {
                return p;
            }
        }
        return null;
    }

    @Override
    public TruffleTypeCache typeCache()
    {
        return typeCache;
    }
}
