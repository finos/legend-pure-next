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
package org.finos.legend.pure.truffle.compiler.module;

import org.finos.legend.pure.truffle.runtime.PureRuntime;

import java.util.List;

/**
 * What {@link PureRuntime#compile()} produced: the compiled elements of every module it compiled, and the
 * compile errors of the module it stopped at (empty when every module compiled).
 */
public record CompilationResult(List<Object> elements, List<String> errors)
{
}
