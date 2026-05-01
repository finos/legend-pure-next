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
import meta.pure.metamodel.function.FunctionWithParameters;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import meta.pure.metamodel.valuespecification.VariableExpression;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.next.parser.ParserExtension;

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
    private final MetadataAccess resolver;


    private PureExecution(MetadataAccess resolver, Iterable<? extends NativeExtension> extensions, List<? extends ParserExtension> parserExtensions)
    {
        this.resolver = resolver;
        this.evaluator = new ValueSpecificationEvaluator(
                NativeRepository.builder()
                        .withResolver(resolver)
                        .withParserExtensions(parserExtensions)
                        .withNativeExtensions(extensions)
                        .build()
        );
    }

    public static class Builder
    {
        private MetadataAccess resolver;
        private final List<NativeExtension> nativeExtensions = new ArrayList<>();
        private final List<ParserExtension> parserExtensions = new ArrayList<>();

        public Builder withResolver(MetadataAccess resolver)
        {
            this.resolver = resolver;
            return this;
        }

        public Builder withNativeExtensions(Iterable<? extends NativeExtension> extensions)
        {
            if (extensions != null)
            {
                extensions.forEach(this.nativeExtensions::add);
            }
            return this;
        }

        public Builder withParserExtensions(Iterable<? extends ParserExtension> extensions)
        {
            if (extensions != null)
            {
                extensions.forEach(this.parserExtensions::add);
            }
            return this;
        }

        public PureExecution build()
        {
            return new PureExecution(resolver, nativeExtensions, parserExtensions);
        }
    }

    public static Builder builder()
    {
        return new Builder();
    }

    // Retained for backward compatibility
    public PureExecution(MetadataAccess resolver)
    {
        this(resolver, null, List.of());
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
        return executeAny(function, args);
    }

    /**
     * Execute any callable: {@link FunctionDefinition}, {@link
     * meta.pure.metamodel.function.NativeFunction}, or other
     * {@link FunctionWithParameters}. Delegates dispatch to
     * {@link ValueSpecificationEvaluator#executeFunction}, which handles
     * each callable kind appropriately.
     *
     * <p>Useful when the caller only knows the function as
     * {@code FunctionWithParameters} (e.g. a CLI/orchestrator that resolves
     * a function by path and may get back a native or a user function).</p>
     */
    public Object execute(FunctionWithParameters function, Object... args)
    {
        return executeAny(function, args);
    }

    private Object executeAny(FunctionWithParameters function, Object[] args)
    {
        // Wrap each raw argument with the parameter's declared type
        List<VariableExpression> params = function._parameters();
        List<ValueSpecification> wrappedArgs = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++)
        {
            VariableExpression param = params.get(i);
            wrappedArgs.add(_E_ValueSpecification.wrap(args[i], param._genericType(), param._multiplicity(), this.resolver));
        }

        ValueSpecification fnVS = _E_ValueSpecification.wrap(function, null, null, this.resolver);
        ValueSpecification result = evaluator.executeFunction(fnVS, wrappedArgs);
        return result != null ? _E_ValueSpecification.unwrap(result) : null;
    }
}
