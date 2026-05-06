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
 * Represents an M3 Class from the metamodel.
 */
public class ClassInfo
{
    public String name;
    public String packagePath;
    public String uri;
    /** Raw type names (e.g., "Class") — used for subtype computation */
    public MutableList<String> generalizations = Lists.mutable.empty();
    /** Full generic type strings (e.g., "Class<T, U>") — used for Pure rendering */
    public MutableList<String> fullGeneralizations = Lists.mutable.empty();
    public MutableList<String> stereotypes = Lists.mutable.empty();
    public MutableList<TaggedValueEntry> taggedValues = Lists.mutable.empty();
    public MutableList<String> typeParameters = Lists.mutable.empty();
    public MutableList<String> multiplicityParameters = Lists.mutable.empty();
}
