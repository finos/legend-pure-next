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

package org.finos.legend.pure.m3.module.localModule.topLevel;

import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.function.Function;
import meta.pure.metamodel.function.property.Property;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement;

import java.util.function.Supplier;

/**
 * Collects compilation errors during a compilation pass.
 *
 * <p>Errors are first added to a staging area ({@link #addError}).
 * As the call stack unwinds, each layer enriches the staged errors
 * with its own context ({@link #enrichCurrentErrors}).
 * When the outermost handler (PackageableElement level) is reached,
 * it calls {@link #flushCurrentErrors} to move the staged errors
 * into the final error list.</p>
 */
public class CompilationContext
{
    private static final boolean DEBUG = Boolean.getBoolean("legend.pure.compileDebug");
    private int debugDepth = 0;

    private final MutableList<CompilationError> errors = Lists.mutable.empty();
    private final MutableList<CompilationError> currentErrors = Lists.mutable.empty();
    private int inferenceRollbackCount;
    private int candidateEvaluationCount;
    // Diagnostic: per-call-site rollback counts. Bucketed by stable semantic
    // tags (see {@link RollbackSite}) so resolver shape can be diffed against
    // the Pure compiler without coupling to Java source-line numbers.
    private final MutableMap<String, Integer> rollbackSites = Maps.mutable.empty();
    private String sourceId;
    private MutableList<String> imports = Lists.mutable.empty();

    /**
     * The element currently being processed by a compile pass plus its
     * fully-qualified path. Per-element loops in {@code TopLevelCompiler}
     * set these before invoking a handler and clear them after. The path
     * is supplied separately because the element's {@code _package} slot
     * is null before {@code updatePackageTree} runs in pass 3.
     */
    private meta.pure.metamodel.PackageableElement currentElement;
    private String currentElementPath;

    /**
     * Reverse reference index built as a side-effect of compilation:
     * for each target path looked up via {@code MetadataAccess.getElement},
     * the set of caller element paths that asked for it. Populated by
     * {@code RecordingMetadataAccess}. Persisted into the produced PDB and
     * consumed by validators / IDE features. Per-context lifetime.
     */
    private final MutableMap<String, org.eclipse.collections.api.set.MutableSet<String>> referencedBy = Maps.mutable.empty();

    /**
     * Identity-keyed map of {@code PackageableElement → path used to look
     * it up}. Populated by {@code RecordingMetadataAccess} on every
     * successful {@code getElement(path)}. Read by callers that need the
     * canonical full path of an element BEFORE {@code updatePackageTree}
     * runs (e.g. stereotype / tag sub-element recording during pass 2,
     * when {@code _package} is still null and
     * {@code _PackageableElement.path} would return just the name).
     */
    private final java.util.IdentityHashMap<meta.pure.metamodel.PackageableElement, String> resolvedPaths = new java.util.IdentityHashMap<>();

    private final MutableList<CompilerContextExtension> compilerContextExtensions;

    public CompilationContext(MutableList<CompilerContextExtension> compilerContextExtensions)
    {
        this.compilerContextExtensions = compilerContextExtensions;
    }

    // ========================================================================
    // Pure Language Compiler Context
    // ========================================================================

    /**
     * Return the Pure-language-specific compilation context for variable
     * scope and type parameter management.
     */
    public <T> T compilerContextExtensions(Class<T> clz)
    {
        return compilerContextExtensions.selectInstancesOf(clz).getFirst();
    }

    // ========================================================================
    // Debug support
    // ========================================================================

    public static boolean isDebug()
    {
        return DEBUG;
    }

    public void debug(Supplier<String> msg)
    {
        if (DEBUG)
        {
            String prefix = "\t".repeat(debugDepth);
            System.out.println(prefix + msg.get().replace("\n", "\n" + prefix));
        }
    }

    public void debug(String format, Object... args)
    {
        if (DEBUG)
        {
            String prefix = "\t".repeat(debugDepth);
            System.out.println(prefix + String.format(format, args).replace("\n", "\n" + prefix));
        }
    }

    /**
     * Wrap an expensive expression for lazy evaluation inside {@link #debug(String, Object...)}.
     * The supplier is only called when {@code String.format} invokes {@code toString()}.
     */
    public static Object lazy(Supplier<String> supplier)
    {
        return new Object()
        {
            @Override
            public String toString()
            {
                return supplier.get();
            }
        };
    }

    public void debugDepthInc()
    {
        debugDepth++;
    }

    public void debugDepthDec()
    {
        debugDepth--;
    }

    // ========================================================================
    // Error management
    // ========================================================================

    /**
     * Stage a compilation error. The error will remain in the
     * staging area until {@link #flushCurrentErrors} is called.
     * If the error's SourceInformation has no sourceId, the
     * context's current sourceId is applied.
     */
    public void addError(CompilationError error)
    {
        applySourceId(error);
        this.currentErrors.add(error);
        debug("ERROR ADDED #%d: %s", currentErrorCount(), error.message());
    }

    public MutableList<CompilationError> snapshotErrorsFrom(int fromIndex)
    {
        return Lists.mutable.withAll(this.currentErrors.subList(fromIndex, this.currentErrors.size()));
    }

    public void addErrors(MutableList<CompilationError> errors)
    {
        this.currentErrors.addAll(errors);
    }

    private void applySourceId(CompilationError error)
    {
        if (sourceId != null && error.sourceInformation() != null
                && (error.sourceInformation()._sourceId() == null
                    || error.sourceInformation()._sourceId().isEmpty()))
        {
            error.sourceInformation()._sourceId(sourceId);
        }
    }

    /**
     * Return the number of currently staged errors.
     * Used by callers to snapshot the staging area size before
     * a sub-compilation, so that enrichment can be scoped.
     */
    public int currentErrorCount()
    {
        return this.currentErrors.size();
    }

    /**
     * Enrich all currently staged errors with additional context.
     * Called by each compilation layer as the call stack unwinds.
     * complete context-rich messages.
     */
    public void enrichCurrentErrors(String context)
    {
        this.currentErrors.forEach(e -> e.addContext(context));
    }

    /**
     * Enrich only staged errors added at or after the given index.
     * Permanent errors are not enriched here.
     * Use with {@link #currentErrorCount()} to scope enrichment
     * to errors added during a sub-compilation.
     *
     * @param fromIndex the staging area index to start enriching from
     * @param context   the context string to add
     */
    public void enrichCurrentErrorsFrom(int fromIndex, String context)
    {
        for (int i = fromIndex; i < this.currentErrors.size(); i++)
        {
            this.currentErrors.get(i).addContext(context);
        }
    }

    /**
     * Flush all staged errors (and permanent errors) into the
     * final error list and clear both staging areas.
     * Called at the PackageableElement level (handlers).
     */
    public void flushCurrentErrors()
    {
        this.errors.addAll(this.currentErrors);
        this.currentErrors.clear();
    }

    /**
     * Roll back staged errors to a previous checkpoint.
     * Removes all errors added after the given index.
     * Use with {@link #currentErrorCount()} to snapshot
     * the staging area before a speculative compilation.
     *
     * @param site stable semantic identifier of the call site (one of the
     *             {@link RollbackSite} constants). Bucketed into
     *             {@link #rollbackSites()} so resolver shape can be diffed
     *             across compilers without coupling tags to source-line
     *             numbers.
     */
    public void rollbackErrorsTo(int checkpoint, String site)
    {
        this.debug(() -> "ERRORS ROLLED BACK from " + this.currentErrors.size() + " to " + checkpoint);
        this.inferenceRollbackCount++;
        this.rollbackSites.merge(site, 1, Integer::sum);
        while (this.currentErrors.size() > checkpoint)
        {
            this.currentErrors.remove(this.currentErrors.size() - 1);
        }
    }

    /**
     * Snapshot the current reverse-reference index for later restoration via
     * {@link #restoreReferencedBy(java.util.Map)}. Pair this with
     * {@link #rollbackErrorsTo} during multi-candidate function resolution so
     * recordings made by failed candidate trials don't leak into the final
     * index. Without this, the index gets phantom edges from candidates that
     * lost specificity comparison — a real false-positive risk for downstream
     * validators (e.g. the lean-references check).
     *
     * <p>The snapshot is a defensive deep copy of the {@code target -> callers}
     * map; restoring it replaces the live map with the snapshot.</p>
     */
    public java.util.Map<String, org.eclipse.collections.api.set.MutableSet<String>> referencedByCheckpoint()
    {
        java.util.LinkedHashMap<String, org.eclipse.collections.api.set.MutableSet<String>> snapshot = new java.util.LinkedHashMap<>();
        this.referencedBy.forEachKeyValue((target, callers) ->
                snapshot.put(target, org.eclipse.collections.impl.factory.Sets.mutable.withAll(callers)));
        return snapshot;
    }

    /**
     * Restore the reverse index to a snapshot captured by
     * {@link #referencedByCheckpoint()}. Used by the function-application
     * resolver to roll back recordings made during a failed candidate trial.
     */
    public void restoreReferencedBy(java.util.Map<String, org.eclipse.collections.api.set.MutableSet<String>> snapshot)
    {
        this.referencedBy.clear();
        snapshot.forEach((target, callers) ->
                this.referencedBy.put(target, org.eclipse.collections.impl.factory.Sets.mutable.withAll(callers)));
    }

    public MutableMap<String, Integer> rollbackSites()
    {
        return this.rollbackSites;
    }

    /**
     * Return the list of finalized compilation errors.
     */
    public MutableList<CompilationError> errors()
    {
        return this.errors;
    }

    // ========================================================================
    // Statistics counters
    // ========================================================================

    /**
     * Increment the number of function candidates evaluated during resolution.
     */
    public void incrementCandidateEvaluationCount()
    {
        this.candidateEvaluationCount++;
    }

    /**
     * Return the number of inference rollbacks that occurred.
     */
    public int inferenceRollbackCount()
    {
        return this.inferenceRollbackCount;
    }

    /**
     * Return the number of function candidates evaluated.
     */
    public int candidateEvaluationCount()
    {
        return this.candidateEvaluationCount;
    }

    // ========================================================================
    // Source and import context
    // ========================================================================

    /**
     * Set the source identifier for the file currently being compiled.
     * This is used to populate empty sourceId fields on SourceInformation.
     */
    public void setSourceId(String sourceId)
    {
        this.sourceId = sourceId;
    }

    /**
     * Return the source identifier for the file currently being compiled.
     */
    public String getSourceId()
    {
        return this.sourceId;
    }

    /**
     * Set the element currently being processed alongside its full path.
     * Pass {@code (null, null)} to clear between elements.
     */
    public void setCurrentElement(meta.pure.metamodel.PackageableElement element, String fullPath)
    {
        this.currentElement = element;
        this.currentElementPath = fullPath;
    }

    public meta.pure.metamodel.PackageableElement getCurrentElement()
    {
        return this.currentElement;
    }

    public String getCurrentElementPath()
    {
        return this.currentElementPath;
    }

    /**
     * Run {@code action} with {@code subName} pushed onto the current caller
     * path: {@code "<currentPath>.<subName>"}. Used by per-sub-element
     * compile loops in handlers (Class properties, qualified properties,
     * constraints; Enumeration values; Profile stereotypes/tags) so any
     * reference resolution that happens inside the action attributes to
     * the sub-element rather than its enclosing PE.
     *
     * <p>Restores the previous path on exit (try/finally), so nested
     * pushes (e.g. constraint → expression-sequence → function call)
     * unwind cleanly.</p>
     */
    public <R> R withCallerSubElement(String subName, java.util.function.Supplier<R> action)
    {
        if (subName == null || currentElementPath == null)
        {
            return action.get();
        }
        String saved = this.currentElementPath;
        this.currentElementPath = saved + "." + subName;
        try
        {
            return action.get();
        }
        finally
        {
            this.currentElementPath = saved;
        }
    }

    /**
     * Record a cross-element reference: {@code currentElement} (the caller)
     * referenced {@code targetPath}. Set semantics — a caller resolving the
     * same target multiple times during one compile contributes a single
     * edge. No-op when no current element is set (e.g. inter-pass setup),
     * when caller equals target, or when {@code targetPath} sits under the
     * m3 metamodel namespace (those references are compiler-internal
     * plumbing and have been filtered out of the index by convention).
     */
    public void recordReference(String targetPath)
    {
        if (targetPath == null || currentElementPath == null
                || targetPath.equals(currentElementPath)
                || targetPath.startsWith("meta::pure::metamodel::"))
        {
            return;
        }
        referencedBy.getIfAbsentPut(targetPath, org.eclipse.collections.impl.factory.Sets.mutable::empty)
                .add(currentElementPath);
    }

    /**
     * Record a reference to a resolved function. Called at every site where
     * the compiler sets {@code FunctionExpression._func} after matching a
     * candidate — function calls bypass {@code MetadataAccess.getElement}
     * (resolved by name + arity via {@code PureLanguageMetadata}), so the
     * generic {@link #recordReference} hook wouldn't see them otherwise.
     *
     * <p>For {@code Property} / {@code QualifiedProperty} (which extend
     * {@code Function} but aren't top-level PEs), the recorded path is
     * {@code owner_path.property_name} — sub-element notation. Same applies
     * to enum values (represented as Properties on an Enumeration).</p>
     */
    public void recordFunctionReference(meta.pure.metamodel.function.Function fn)
    {
        if (fn == null) return;
        String path;
        if (fn instanceof org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.FunctionIndexEntry fie)
        {
            path = fie.fullPath();
        }
        else if (fn instanceof meta.pure.metamodel.function.property.AbstractProperty prop)
        {
            // Property / QualifiedProperty: owner is the Class or Enumeration
            // that declares it. Both implement SimplePropertyOwner; in practice
            // they're always PackageableElement too (Class, Association,
            // Enumeration). Sub-element path = ownerPath.propertyName. Use
            // {@link #getResolvedPath} first — during pass 2 the owner's
            // {@code _package} is null and {@code _PackageableElement.path}
            // would return just the bare name.
            Object owner = prop._owner();
            if (!(owner instanceof meta.pure.metamodel.PackageableElement ownerPe) || prop._name() == null)
            {
                return;
            }
            String ownerPath = getResolvedPath(ownerPe);
            if (ownerPath == null)
            {
                ownerPath = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(ownerPe);
            }
            if (ownerPath == null) return;
            path = ownerPath + "." + prop._name();
        }
        else if (fn instanceof meta.pure.metamodel.PackageableElement pe)
        {
            path = org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._PackageableElement.path(pe);
        }
        else
        {
            return;
        }
        recordReference(path);
    }

    /**
     * @return the reverse reference index. Keys are target element paths;
     *         values are the set of caller element paths that referenced
     *         them during compilation.
     */
    public MutableMap<String, org.eclipse.collections.api.set.MutableSet<String>> referencedBy()
    {
        return this.referencedBy;
    }

    /**
     * Remember the path used to resolve {@code element}. Called from the
     * recording wrapper after every successful {@code getElement(path)}.
     */
    public void rememberResolvedPath(meta.pure.metamodel.PackageableElement element, String path)
    {
        if (element != null && path != null)
        {
            resolvedPaths.put(element, path);
        }
    }

    /**
     * @return the path that was used to look up {@code element} during
     *         compilation, or {@code null} if it wasn't looked up via
     *         {@code MetadataAccess.getElement}. Useful for callers that
     *         need the canonical full path BEFORE the package tree is
     *         built (e.g. stereotype / tag sub-element recording).
     */
    public String getResolvedPath(meta.pure.metamodel.PackageableElement element)
    {
        return element == null ? null : resolvedPaths.get(element);
    }


    /**
     * Set the import paths for the element currently being compiled.
     * These represent the packages visible at the call site.
     */
    public void setImports(MutableList<String> imports)
    {
        this.imports = imports;
    }

    /**
     * Return the current import paths.
     */
    public MutableList<String> imports()
    {
        return this.imports;
    }

    /**
     * Check if a function at the given path is visible from the current import scope.
     */
    public boolean isElementVisible(String elementPath)
    {
        int lastSep = elementPath.lastIndexOf("::");
        String pkgPath = lastSep > 0 ? elementPath.substring(0, lastSep) : "";
        if (pkgPath.isEmpty())
        {
            return true; // root-level or no-package functions are always visible
        }
        return this.imports.anySatisfy(imp -> imp.equals(pkgPath));
    }

    public static String debugFunc(Function func)
    {
        if (func instanceof PackageableElement pe)
        {
            return _PackageableElement.path(pe);
        }
        if (func instanceof Property prop)
        {
            return "Property:" + prop._name();
        }
        return String.valueOf(func);
    }
}
