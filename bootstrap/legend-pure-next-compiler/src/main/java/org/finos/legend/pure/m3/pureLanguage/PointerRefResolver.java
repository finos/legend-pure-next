// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.m3.pureLanguage;

import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.pdbModule.fbs.PointerRef;

/**
 * Resolves a typed {@link PointerRef} to the target metamodel object.
 *
 * <p>PointerRef carries a {@code kind} discriminator and a {@code path}
 * segment array. The kind determines how the path segments are interpreted:</p>
 * <ul>
 *   <li><b>Element (0)</b>: path[0] is the full element path</li>
 *   <li><b>Property (1)</b>: path[0] is owner, path[1] is property name</li>
 *   <li><b>QualifiedProperty (2)</b>: path[0] is owner, path[1] is QP name</li>
 *   <li><b>Stereotype (3)</b>: path[0] is profile, path[1] is stereotype value</li>
 *   <li><b>Tag (4)</b>: path[0] is profile, path[1] is tag value</li>
 * </ul>
 */
public final class PointerRefResolver
{
    private PointerRefResolver() {}

    public static Object resolve(PointerRef ref, MetadataAccess resolver)
    {
        if (ref == null || ref.pathLength() == 0)
        {
            return null;
        }
        return switch (ref.kind())
        {
            case 0 -> // Element
                    resolver.getElement(ref.path(0));
            case 1 -> // Property
            {
                meta.pure.metamodel.PackageableElement owner = resolver.getElement(ref.path(0));
                if (owner instanceof meta.pure.metamodel.SimplePropertyOwner spo && ref.pathLength() > 1)
                {
                    String propName = ref.path(1);
                    yield spo._properties().detect(p -> propName.equals(p._name()));
                }
                yield null;
            }
            case 2 -> // QualifiedProperty
            {
                meta.pure.metamodel.PackageableElement owner = resolver.getElement(ref.path(0));
                if (owner instanceof meta.pure.metamodel.PropertyOwner po && ref.pathLength() > 1)
                {
                    String qpName = ref.path(1);
                    yield po._qualifiedProperties().detect(p -> qpName.equals(p._name()));
                }
                yield null;
            }
            case 3 -> // Stereotype
            {
                meta.pure.metamodel.PackageableElement owner = resolver.getElement(ref.path(0));
                if (owner instanceof meta.pure.metamodel.extension.Profile p && ref.pathLength() > 1)
                {
                    String stName = ref.path(1);
                    yield p._p_stereotypes().detect(s -> stName.equals(s._value()));
                }
                yield null;
            }
            case 4 -> // Tag
            {
                meta.pure.metamodel.PackageableElement owner = resolver.getElement(ref.path(0));
                if (owner instanceof meta.pure.metamodel.extension.Profile p && ref.pathLength() > 1)
                {
                    String tagName = ref.path(1);
                    yield p._p_tags().detect(s -> tagName.equals(s._value()));
                }
                yield null;
            }
            default -> null;
        };
    }
}
