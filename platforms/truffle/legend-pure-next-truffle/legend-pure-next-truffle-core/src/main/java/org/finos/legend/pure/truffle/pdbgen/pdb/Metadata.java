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


package org.finos.legend.pure.truffle.pdbgen.pdb;

import java.util.List;

/**
 * The metadata hooks the generated PDB reader calls.
 *
 * <p>Only {@code classifier} is reachable from the reader, and only to stamp a
 * classifierGenericType onto its own working objects (FbsNode and friends).
 * Those never leave the reader, so there is nothing to look up: null is the
 * same answer the Java platform gives while it is decoding.</p>
 */
public final class Metadata
{
    private Metadata()
    {
    }

    public static Object classifier(String path)
    {
        return null;
    }

    public static Object classifier(String path, List<Object> typeArguments)
    {
        return null;
    }

    public static Object classifier(String path, List<Object> typeArguments, List<Object> typeVariableValues)
    {
        return null;
    }
}
