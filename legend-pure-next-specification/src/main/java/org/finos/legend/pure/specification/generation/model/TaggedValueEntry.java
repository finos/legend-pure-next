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

package org.finos.legend.pure.specification.generation.model;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;

/**
 * Represents a tagged value pair (tag name + literal value),
 * as found on classes, properties, and other metamodel elements.
 */
public class TaggedValueEntry
{
    public final String tag;
    public final String value;

    public TaggedValueEntry(String tag, String value)
    {
        this.tag = tag;
        this.value = value;
    }
}
