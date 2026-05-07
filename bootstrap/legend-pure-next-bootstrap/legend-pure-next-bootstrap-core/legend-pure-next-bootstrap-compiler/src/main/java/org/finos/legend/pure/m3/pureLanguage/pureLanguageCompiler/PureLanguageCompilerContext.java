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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler;

import meta.pure.metamodel.multiplicity.MultiplicityParameter;
import meta.pure.metamodel.type.generics.TypeAndMultiplicityParametersOwner;
import meta.pure.metamodel.type.generics.TypeParameter;
import meta.pure.metamodel.valuespecification.AtomicValue;
import meta.pure.metamodel.valuespecification.FunctionExpression;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.stack.MutableStack;
import org.eclipse.collections.impl.factory.Stacks;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationError;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilerContextExtension;
import org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions.FunctionIndexEntry;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._FunctionExpression;

/**
 * Pure-language-specific compilation context that manages variable scopes,
 * type/multiplicity parameter names, and the binding tree during the third
 * compilation pass.
 *
 * <p>The binding tree has two node types:
 * <ul>
 *   <li>{@link EnclosingOwnerParametersBinding} — the root, representing the function or
 *       class being compiled. Created by {@link #setEnclosingOwner}.</li>
 *   <li>{@link FunctionCallParametersBinding} — one per function call being resolved.
 *       Pushed/popped by {@link #pushBindingNode}/{@link #popBindingNode}.</li>
 * </ul>
 * </p>
 */
public class PureLanguageCompilerContext implements CompilerContextExtension
{
    private final MutableStack<MutableList<VariableExpression>> variableScopes = Stacks.mutable.empty();

    /**
     * The root of the binding tree for the element currently being compiled.
     * Null outside of function/class compilation.
     */
    private EnclosingOwnerParametersBinding enclosingOwnerParametersBinding;

    /** The current function call node, null when not inside a function call. */
    private FunctionCallParametersBinding currentFunctionCallNode;

    /**
     * Memoizes lambda body resolution outcomes by (sourceInfo, paramShape, openVar-types).
     * Cached value is a {@link CachedLambdaOutcome} — the resolved AtomicValue plus the
     * errors fired during body resolution. On hit we return the AV and replay the errors
     * so callers' error-count delta matches a fresh walk. Symmetric to the Pure compiler's
     * lambdaResolutionCache.
     */
    public static final class CachedLambdaOutcome
    {
        public final AtomicValue av;
        public final ImmutableList<CompilationError> errorsFired;
        public CachedLambdaOutcome(AtomicValue av, ImmutableList<CompilationError> errorsFired)
        {
            this.av = av;
            this.errorsFired = errorsFired;
        }
    }

    private final MutableMap<String, CachedLambdaOutcome> lambdaResolutionCache = Maps.mutable.empty();

    public MutableMap<String, CachedLambdaOutcome> lambdaResolutionCache()
    {
        return lambdaResolutionCache;
    }

    /**
     * Memoizes the early-exit gate of {@code processLambda} (lambda params not concrete).
     * Distinct from {@link #lambdaResolutionCache} because the gate doesn't run body
     * resolution — it just adds a fixed error and returns AV unchanged. Free-shape keys
     * (paramShape includes a free TypeParameter name) live here; concrete-shape keys live
     * in lambdaResolutionCache. They don't collide.
     */
    private final MutableSet<String> lambdaFailureCache = Sets.mutable.empty();

    public MutableSet<String> lambdaFailureCache()
    {
        return lambdaFailureCache;
    }

    // ========================================================================
    // Binding tree management
    // ========================================================================

    /**
     * Push a new {@link FunctionCallParametersBinding} when starting to resolve a function call.
     * Seeds it with any pre-resolved type/multiplicity parameter bindings.
     */
    public FunctionCallParametersBinding pushBindingNode(FunctionExpression expr, FunctionIndexEntry entry)
    {
        FunctionCallParametersBinding node = new FunctionCallParametersBinding(
                                                    expr, entry,
                                                    _FunctionExpression.extractResolvedParametersBinding(expr),
                                                    enclosingOwnerParametersBinding
                                             );
        currentFunctionCallNode = node;
        return node;
    }

    /**
     * Pop the current {@link FunctionCallParametersBinding} after function resolution completes.
     */
    public void popBindingNode()
    {
        currentFunctionCallNode = null;
    }

    /**
     * Return the current function call node, or null between function calls.
     */
    public FunctionCallParametersBinding currentFunctionCallNode()
    {
        return currentFunctionCallNode;
    }

    // ========================================================================
    // Variable scope management
    // ========================================================================

    /** Push a new variable scope (e.g. function parameters, lambda parameters). */
    public void pushScope(MutableList<VariableExpression> variables)
    {
        this.variableScopes.push(Lists.mutable.withAll(variables));
    }

    /** Pop the most recent variable scope. */
    public void popScope()
    {
        this.variableScopes.pop();
    }

    /** Add a variable to the current (topmost) scope (used for {@code letFunction}). */
    public void addToCurrentScope(VariableExpression variable)
    {
        this.variableScopes.peek().add(variable);
    }

    // ========================================================================
    // Enclosing owner (the function or class being compiled)
    // ========================================================================

    /**
     * Set the enclosing owner and create the root {@link EnclosingOwnerParametersBinding}.
     */
    public void setEnclosingOwner(TypeAndMultiplicityParametersOwner owner)
    {
        this.enclosingOwnerParametersBinding = new EnclosingOwnerParametersBinding(owner);
    }

    /** Clear the enclosing owner (called after compilation of function/class finishes). */
    public void clearEnclosingOwner()
    {
        this.enclosingOwnerParametersBinding = null;
        this.currentFunctionCallNode = null;
    }

    /** Return the root-level bindings for the enclosing function/class. */
    public EnclosingOwnerParametersBinding enclosingBindings()
    {
        return enclosingOwnerParametersBinding;
    }

    // ========================================================================
    // Helpers to extract params from the enclosing owner
    // ========================================================================

    private MutableList<TypeParameter> enclosingTypeParams()
    {
        return enclosingOwnerParametersBinding != null ? enclosingOwnerParametersBinding.owner()._typeParameters() : null;
    }

    private MutableList<MultiplicityParameter> enclosingMultiplicityParams()
    {
        return enclosingOwnerParametersBinding != null ? enclosingOwnerParametersBinding.owner()._multiplicityParameters() : null;
    }

    // ========================================================================
    // Unified lookup — checks enclosing scope, then current binding node
    // ========================================================================

    /**
     * Look up a TypeParameter by name. Checks the enclosing owner's params first,
     * then the current binding node's own type params.
     */
    public TypeParameter lookupTypeParameter(String name)
    {
        MutableList<TypeParameter> params = enclosingTypeParams();
        if (params != null)
        {
            TypeParameter tp = params.detect(p -> p._name().equals(name));
            if (tp != null)
            {
                return tp;
            }
        }
        if (currentFunctionCallNode != null)
        {
            return currentFunctionCallNode.ownTypeParams().get(name);
        }
        return null;
    }

    /**
     * Look up a MultiplicityParameter by name. Checks the enclosing owner's params first,
     * then the current binding node's own multiplicity params.
     */
    public MultiplicityParameter lookupMultiplicityParameter(String name)
    {
        MutableList<MultiplicityParameter> params = enclosingMultiplicityParams();
        if (params != null)
        {
            MultiplicityParameter mp = params.detect(p -> p._name().equals(name));
            if (mp != null)
            {
                return mp;
            }
        }
        if (currentFunctionCallNode != null)
        {
            return currentFunctionCallNode.ownMulParams().get(name);
        }
        return null;
    }

    /**
     * Return the enclosing type parameter names (from the function or class being compiled).
     */
    public MutableSet<String> inScopeTypeParamNames()
    {
        MutableList<TypeParameter> params = enclosingTypeParams();
        return params != null ? params.collect(TypeParameter::_name).toSet() : Sets.mutable.empty();
    }

    /**
     * Return the enclosing multiplicity parameter names (from the function or class being compiled).
     */
    public MutableSet<String> inScopeMultiplicityParamNames()
    {
        MutableList<MultiplicityParameter> params = enclosingMultiplicityParams();
        return params != null ? params.collect(MultiplicityParameter::_name).toSet() : Sets.mutable.empty();
    }

    /**
     * Resolve a variable name to its declaration by searching scopes
     * from innermost to outermost.
     */
    public VariableExpression resolveVariable(String name)
    {
        for (int i = this.variableScopes.size() - 1; i >= 0; i--)
        {
            MutableList<VariableExpression> scope = this.variableScopes.peekAt(i);
            VariableExpression match = scope.detect(v -> v._name().equals(name));
            if (match != null)
            {
                return match;
            }
        }
        return null;
    }
}
