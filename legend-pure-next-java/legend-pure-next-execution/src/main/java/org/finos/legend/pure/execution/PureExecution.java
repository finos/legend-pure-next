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

package org.finos.legend.pure.execution;

import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for executing compiled Pure functions.
 *
 * <p>Takes a compiled {@link FunctionDefinition} (e.g. a user-defined
 * function resolved from a {@code PureModel}) and evaluates it by
 * walking its expression tree.</p>
 *
 * <p>Arguments are wrapped in {@link meta.pure.metamodel.valuespecification.AtomicValueImpl}
 * using the function's parameter type declarations before evaluation begins.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * PureExecution execution = new PureExecution(metadataAccess);
 * Object result = execution.execute(myFunction, "World");
 * }</pre>
 */
public class PureExecution
{
    private final ValueSpecificationEvaluator evaluator;

    public PureExecution(MetadataAccess resolver)
    {
        this.evaluator = new ValueSpecificationEvaluator(new NativeRepository(resolver));
    }

    public PureExecution()
    {
        this((MetadataAccess) null);
    }

    /**
     * Execute a compiled function with the given arguments.
     *
     * <p>Each argument is wrapped in an {@code AtomicValueImpl} using
     * the corresponding parameter's declared {@code GenericType}
     * and {@code Multiplicity} from the function signature.</p>
     *
     * @param function the compiled function definition to execute
     * @param args     the argument values, in parameter order
     * @return the result value (unwrapped)
     */
    public Object execute(FunctionDefinition function, Object... args)
    {
        // Wrap each raw argument with the parameter's declared type
        List<VariableExpression> params = function._parameters();
        List<ValueSpecification> wrappedArgs = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++)
        {
            VariableExpression param = params.get(i);
            wrappedArgs.add(_E_ValueSpecification.wrap(args[i], param._genericType(), param._multiplicity()));
        }

        ValueSpecification result = evaluator.evaluateFunctionDefinition(function, wrappedArgs);
        return result != null ? _E_ValueSpecification.unwrap(result) : null;
    }
}
