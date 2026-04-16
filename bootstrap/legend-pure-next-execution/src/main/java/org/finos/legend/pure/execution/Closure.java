// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.execution;

import meta.pure.metamodel.function.LambdaFunction;
import meta.pure.metamodel.function.LambdaFunctionImpl;
import meta.pure.metamodel.valuespecification.ValueSpecification;

import java.util.Map;

/**
 * A closure: a {@link LambdaFunction} paired with variable bindings
 * captured at its definition site.
 *
 * <p>Extends {@code LambdaFunctionImpl} so it is fully transparent to all
 * existing code that checks {@code instanceof FunctionDefinition} or
 * {@code LambdaFunction} — no special-casing is needed anywhere except
 * in {@link ValueSpecificationEvaluator#evaluateFunctionDefinition}
 * which checks for a {@code Closure} to use its captured scope.</p>
 *
 * <p>When a lambda references variables from an enclosing scope
 * ("open variables"), those bindings are frozen at definition time and
 * restored at evaluation time. Without this, the lambda would inherit
 * the call-site scope, leading to incorrect behavior when an intermediate
 * function shadows a captured variable.</p>
 *
 * <p>Example of the problem this solves:</p>
 * <pre>
 *   let x = 'correct';
 *   helper(v | $v + $x);   // lambda captures x = 'correct'
 *
 *   function helper(fn) {
 *       let x = 'wrong';   // without Closure, this shadows the lambda's x
 *       $fn->eval('a');
 *   }
 * </pre>
 */
public final class Closure extends LambdaFunctionImpl
{
    private final Map<String, ValueSpecification> capturedScope;

    /**
     * Create a Closure by copying all properties from the source lambda
     * and attaching the captured variable bindings.
     *
     * @param source        the original lambda function
     * @param capturedScope the definition-site variable bindings for open variables
     */
    public Closure(LambdaFunction source, Map<String, ValueSpecification> capturedScope)
    {
        // Copy all LambdaFunction properties from the source
        this._classifierGenericType(source._classifierGenericType());
        this._sourceInformation(source._sourceInformation());
        this._parameters(source._parameters());
        this._expressionSequence(source._expressionSequence());
        this._openVariables(source._openVariables());
        this._elementOverride(source._elementOverride());
        this.capturedScope = capturedScope;
    }

    /**
     * The variable bindings captured at the lambda's definition site.
     * Only the variables listed in the lambda's {@code openVariables} are included.
     */
    public Map<String, ValueSpecification> capturedScope()
    {
        return capturedScope;
    }
}
