// Copyright 2026 Goldman Sachs
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

/**
 * The {@code --tests} modes of {@code pure-truffle compile}, which the compile hands to
 * {@code platform::pure::compiler::pdb::archive::compileModule}: where the split happens.
 */
public final class TestElementFilter
{
    public enum Mode
    {
        /** Strip tests; emit one lean PDB. */
        NONE,
        /** Keep everything; emit one PDB with {@code -with-tests} suffix. */
        WITH,
        /** Emit both lean PDB and tests-only PDB. */
        SPLIT;

        public static Mode parse(String s)
        {
            return switch (s)
            {
                case "none" -> NONE;
                case "with" -> WITH;
                case "split" -> SPLIT;
                default -> throw new IllegalArgumentException(
                        "Invalid --tests value '" + s + "' (expected: none, with, split)");
            };
        }
    }

    private TestElementFilter()
    {
    }
}
