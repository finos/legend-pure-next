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

package org.finos.legend.pure.m3.pureLanguage.metadata.lazyFunctions;

import meta.pure.metamodel.function.FunctionDefinition;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * A function index entry for user-defined (non-native) functions.
 * <p>
 * Implements {@link FunctionDefinition} so it's transparent to the
 * evaluator — {@code _expressionSequence()} lazily resolves the real
 * compiled function via {@code model.getElement(fullPath)}.
 * </p>
 */
public class UserDefinedFunctionIndexEntry extends FunctionIndexEntry implements FunctionDefinition
{
    public UserDefinedFunctionIndexEntry(String fullPath, String functionName, FunctionType functionType, MetadataAccess model)
    {
        super(fullPath, functionName, functionType, model);
    }

    @Override
    public MutableList<ValueSpecification> _expressionSequence()
    {
        return ((FunctionDefinition) resolve())._expressionSequence();
    }

    @Override
    public FunctionDefinition _expressionSequence(MutableList<ValueSpecification> value)
    {
        throw new UnsupportedOperationException("FunctionIndexEntry is read-only");
    }
}
