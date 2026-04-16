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

package org.finos.legend.pure.next.parser.m3.helper;

import meta.pure.protocol.grammar.Package_Pointer;
import meta.pure.protocol.grammar.PackageableElement;

/**
 * Helper methods for grammar protocol {@link PackageableElement}.
 */
public class _G_PackageableElement
{
    private _G_PackageableElement()
    {
        // static utility
    }

    /**
     * Compute the fully-qualified path for a grammar PackageableElement,
     * e.g. {@code "my::package::MyClass"}.
     * Returns the element name alone if it has no package.
     */
    public static String fullPath(PackageableElement grammar)
    {
        return grammar._package() instanceof Package_Pointer pkg
                ? pkg._pointerValue() + "::" + grammar._name()
                : grammar._name();
    }
}
