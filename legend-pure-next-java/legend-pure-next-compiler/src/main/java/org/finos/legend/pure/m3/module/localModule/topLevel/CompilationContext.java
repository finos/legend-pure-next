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
import org.eclipse.collections.api.list.MutableList;
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
    private static final boolean DEBUG = false;
    private int debugDepth = 0;

    private final MutableList<CompilationError> errors = Lists.mutable.empty();
    private final MutableList<CompilationError> currentErrors = Lists.mutable.empty();
    private String sourceId;
    private MutableList<String> imports = Lists.mutable.empty();

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
     */
    public void rollbackErrorsTo(int checkpoint)
    {
        this.debug(() -> "ERRORS ROLLED BACK from " + this.currentErrors.size() + " to " + checkpoint);
        while (this.currentErrors.size() > checkpoint)
        {
            this.currentErrors.remove(this.currentErrors.size() - 1);
        }
    }

    /**
     * Return the list of finalized compilation errors.
     */
    public MutableList<CompilationError> errors()
    {
        return this.errors;
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
