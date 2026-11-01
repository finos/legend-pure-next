// Copyright 2024 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
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

import platform.pure.metamodel.function.FunctionWithParameters;
import platform.pure.metamodel.valuespecification.ValueSpecification;
import platform.pure.metamodel.valuespecification.VariableExpression;
import org.finos.legend.pure.execution.natives.NativeRegistry;
import org.finos.legend.pure.execution.natives.NativesExtension;
import org.finos.legend.pure.m3.module.MetadataAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * The tree-walking execution strategy: a {@link ValueSpecificationEvaluator} over a
 * {@link NativeRegistry}.
 *
 * <p>Arguments are wrapped in {@link platform.pure.metamodel.valuespecification.AtomicValueImpl}
 * using the function's parameter type declarations before evaluation begins.</p>
 */
public final class InterpretedExecution implements Execution
{
    private final ValueSpecificationEvaluator evaluator;
    private final MetadataAccess registry;

    private InterpretedExecution(MetadataAccess registry, NativeRegistry natives)
    {
        this.registry = registry;
        this.evaluator = new ValueSpecificationEvaluator(natives);
    }

    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * Execute any callable: {@link platform.pure.metamodel.function.FunctionDefinition},
     * {@link platform.pure.metamodel.function.NativeFunction}, or other
     * {@link FunctionWithParameters}. Each argument is wrapped using the corresponding
     * parameter's declared {@code GenericType} and {@code Multiplicity}; dispatch is
     * {@link ValueSpecificationEvaluator#executeFunction}.
     */
    @Override
    public Object execute(FunctionWithParameters function, Object... args)
    {
        List<VariableExpression> params = function._parameters();
        List<ValueSpecification> wrappedArgs = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++)
        {
            VariableExpression param = params.get(i);
            wrappedArgs.add(_E_ValueSpecification.wrap(args[i], param._genericType(), param._multiplicity(), this.registry));
        }

        ValueSpecification fnVS = _E_ValueSpecification.wrap(function, null, null, this.registry);
        evaluator.beginExecution();
        try
        {
            ValueSpecification result = evaluator.executeFunction(fnVS, wrappedArgs);
            return result != null ? _E_ValueSpecification.unwrap(result) : null;
        }
        finally
        {
            evaluator.endExecution();
        }
    }

    public static final class Builder implements Execution.Builder
    {
        private NativeRegistry natives;
        private final List<NativesExtension> nativeExtensions = new ArrayList<>();

        private Builder()
        {
        }

        /**
         * Use an already-built native registry instead of building one from the runtime's
         * context and the native extensions below.
         */
        public Builder withNatives(NativeRegistry natives)
        {
            this.natives = natives;
            return this;
        }

        public Builder withNativeExtensions(Iterable<? extends NativesExtension> extensions)
        {
            if (extensions != null)
            {
                extensions.forEach(this.nativeExtensions::add);
            }
            return this;
        }

        @Override
        public InterpretedExecution build(ExecutionContext context)
        {
            if (this.natives != null)
            {
                if (!context.languageExtensions().isEmpty() || !context.grammarExtensions().isEmpty() || !this.nativeExtensions.isEmpty())
                {
                    throw new IllegalStateException("withNatives(registry) takes a fully built NativeRegistry: "
                            + "the runtime's language/grammar extensions and withNativeExtensions would be ignored");
                }
                return new InterpretedExecution(context.registry(), checked(this.natives, context.registry()));
            }
            // M3 + Top grammars are auto-registered by NativeRegistry if not
            // explicitly provided; see NativeRegistry's private constructor.
            return new InterpretedExecution(context.registry(), checked(NativeRegistry.builder()
                    .withResolver(context.registry())
                    .withParserExtensions(context.languageExtensions())
                    .withGrammarExtensions(context.grammarExtensions())
                    .withCompilation(context.compilation())
                    .withNativeExtensions(this.nativeExtensions)
                    .build(), context.registry()));
        }

        /**
         * The natives against what the loaded modules declare, keyed by full id: every native a loaded module
         * declares is implemented here, and no entry is for an element that is not a native — a function written
         * in Pure is never replaced. An entry for an element no loaded module has is inert: its module is not
         * loaded. Only a registry that knows its modules is checked.
         */
        private static NativeRegistry checked(NativeRegistry natives, org.finos.legend.pure.m3.module.MetadataAccess registry)
        {
            org.eclipse.collections.api.list.MutableList<org.finos.legend.pure.m3.module.Module> modules =
                    registry instanceof org.finos.legend.pure.m3.module.ModuleRegistry r ? org.eclipse.collections.impl.factory.Lists.mutable.withAll(r.modules())
                    : registry instanceof org.finos.legend.pure.m3.module.ScopedMetadataAccess s ? s.modules()
                    : null;
            if (modules == null)
            {
                return natives;
            }
            org.eclipse.collections.api.set.SetIterable<String> ids = natives.ids();
            org.eclipse.collections.api.list.MutableList<String> missing = modules
                    .flatCollect(module -> org.eclipse.collections.impl.factory.Sets.adapt(module.elementPaths()).select(module::isNativeFunction))
                    .reject(id -> ids.contains(id) || NativeRegistry.NOT_ON_THIS_PLATFORM.containsKey(id))
                    .distinct().sortThis();
            org.eclipse.collections.api.list.MutableList<String> listedAsMissing = ids.select(NativeRegistry.NOT_ON_THIS_PLATFORM::containsKey).toSortedList();
            org.eclipse.collections.api.list.MutableList<String> notNatives = ids
                    .select(id -> registry.hasElement(id) && modules.noneSatisfy(module -> module.isNativeFunction(id)))
                    .toSortedList();
            if (missing.notEmpty() || notNatives.notEmpty() || listedAsMissing.notEmpty())
            {
                throw new IllegalStateException("the natives on the bootstrap do not match the loaded modules"
                        + (missing.isEmpty() ? "" : "; declared but not implemented: " + missing.makeString(", "))
                        + (listedAsMissing.isEmpty() ? "" : "; implemented but listed as not on this platform: " + listedAsMissing.makeString(", "))
                        + (notNatives.isEmpty() ? "" : "; implemented but not natives (a function written in Pure is never replaced): " + notNatives.makeString(", ")));
            }
            return natives;
        }
    }
}
