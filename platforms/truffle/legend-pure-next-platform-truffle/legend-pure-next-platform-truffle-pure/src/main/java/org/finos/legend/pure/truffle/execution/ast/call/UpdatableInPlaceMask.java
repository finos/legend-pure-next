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
package org.finos.legend.pure.truffle.execution.ast.call;

/**
 * Which arguments of a call to a user function hold the only reference to their value (pure/specification/
 * IN_PLACE_UPDATE_ANALYSIS.md): bit i for argument i. Passed after the arguments by a call that knows (RawUserFunctionCallNode);
 * the callee decodes it into its parameters' flags (PureFunctionRootNode). A call without one passes nothing updatable in place.
 */
public record UpdatableInPlaceMask(long bits)
{
    public boolean owns(int index)
    {
        return index < 64 && (bits & (1L << index)) != 0;
    }
}
