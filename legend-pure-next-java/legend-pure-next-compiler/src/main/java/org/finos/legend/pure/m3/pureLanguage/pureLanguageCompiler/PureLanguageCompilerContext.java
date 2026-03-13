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

import meta.pure.metamodel.valuespecification.VariableExpression;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.factory.Stacks;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.stack.MutableStack;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilerContextExtension;

/**
 * Pure-language-specific compilation context that manages variable scopes
 * and type/multiplicity parameter names during the third compilation pass.
 *
 * <p>This is separated from {@link CompilationContext} because variable
 * scoping is specific to the Pure language compiler, not a generic
 * concern of the top-level compilation orchestrator.</p>
 */
public class PureLanguageCompilerContext implements CompilerContextExtension
{
    private final MutableStack<MutableList<VariableExpression>> variableScopes = Stacks.mutable.empty();
    private MutableSet<String> scopeTypeParamNames = Sets.mutable.empty();
    private MutableSet<String> scopeMultiplicityParamNames = Sets.mutable.empty();

    /**
     * Push a new variable scope (e.g. function parameters, lambda parameters).
     * Creates a defensive copy so the original list is not mutated.
     */
    public void pushScope(MutableList<VariableExpression> variables)
    {
        this.variableScopes.push(Lists.mutable.withAll(variables));
    }

    /**
     * Pop the most recent variable scope.
     */
    public void popScope()
    {
        this.variableScopes.pop();
    }

    /**
     * Add a variable to the current (topmost) scope.
     * Used to register variables introduced by {@code letFunction}.
     */
    public void addToCurrentScope(VariableExpression variable)
    {
        this.variableScopes.peek().add(variable);
    }

    /**
     * Set the in-scope type parameter names (from the enclosing function or class).
     */
    public void setScopeTypeParamNames(MutableSet<String> names)
    {
        this.scopeTypeParamNames = names;
    }

    /**
     * Return the in-scope type parameter names.
     */
    public MutableSet<String> scopeTypeParamNames()
    {
        return this.scopeTypeParamNames;
    }

    /**
     * Set the in-scope multiplicity parameter names (from the enclosing function or class).
     */
    public void setScopeMultiplicityParamNames(MutableSet<String> names)
    {
        this.scopeMultiplicityParamNames = names;
    }

    /**
     * Return the in-scope multiplicity parameter names.
     */
    public MutableSet<String> scopeMultiplicityParamNames()
    {
        return this.scopeMultiplicityParamNames;
    }

    /**
     * Resolve a variable name to its declaration by searching scopes
     * from innermost to outermost.
     *
     * @return the matching VariableExpression, or null if not found
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
