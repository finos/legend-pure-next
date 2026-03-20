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

import meta.pure.metamodel.function.NativeFunction;
import meta.pure.metamodel.type.FunctionType;
import org.finos.legend.pure.m3.module.MetadataAccess;

/**
 * A function index entry for native (built-in) functions.
 * <p>
 * Native functions are dispatched by their mangled signature through
 * the {@code NativeRepository}; they have no expression sequence.
 * </p>
 */
public class NativeFunctionIndexEntry extends FunctionIndexEntry implements NativeFunction
{
    public NativeFunctionIndexEntry(String fullPath, String functionName, FunctionType functionType, MetadataAccess model)
    {
        super(fullPath, functionName, functionType, model);
    }
}
