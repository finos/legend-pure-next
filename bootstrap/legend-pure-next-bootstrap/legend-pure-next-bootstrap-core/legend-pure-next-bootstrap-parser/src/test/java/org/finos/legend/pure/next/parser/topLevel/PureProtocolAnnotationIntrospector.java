// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.pure.next.parser.topLevel;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.Annotated;
import tools.jackson.databind.introspect.AnnotatedClass;
import tools.jackson.databind.introspect.AnnotatedMember;
import tools.jackson.databind.introspect.AnnotatedMethod;
import tools.jackson.databind.introspect.JacksonAnnotationIntrospector;
import tools.jackson.databind.jsontype.impl.StdTypeResolverBuilder;

/**
 * Synthesizes Jackson polymorphism, type-id, and ignore behavior for the
 * generated Pure protocol classes (under {@code meta.pure.protocol.*}) without
 * requiring those classes to carry Jackson annotations.
 *
 * <p>For any class in the protocol package this introspector emits, on
 * serialization, a {@code _type} discriminator whose value is the simple class
 * name with the {@code Impl} suffix removed (e.g. {@code PureFileImpl} →
 * {@code "PureFile"}). It also tells Jackson to skip the {@code _copy}
 * accessor that exists on every generated impl.</p>
 *
 * <p>Property names ({@code _name() → "name"}) are handled by the accessor
 * naming strategy configured on the {@code ObjectMapper}, not here.</p>
 */
final class PureProtocolAnnotationIntrospector extends JacksonAnnotationIntrospector
{
    private static final String PROTOCOL_PACKAGE_PREFIX = "meta.pure.protocol.";
    private static final String IMPL_SUFFIX = "Impl";

    @Override
    public Object findTypeResolverBuilder(MapperConfig<?> config, Annotated annotated)
    {
        if (annotated instanceof AnnotatedClass ac && isProtocolType(ac.getRawType()))
        {
            return new StdTypeResolverBuilder(JsonTypeInfo.Id.NAME, JsonTypeInfo.As.PROPERTY, "_type");
        }
        return super.findTypeResolverBuilder(config, annotated);
    }

    @Override
    public String findTypeName(MapperConfig<?> config, AnnotatedClass ac)
    {
        if (isProtocolType(ac.getRawType()))
        {
            String simple = ac.getRawType().getSimpleName();
            return simple.endsWith(IMPL_SUFFIX)
                    ? simple.substring(0, simple.length() - IMPL_SUFFIX.length())
                    : simple;
        }
        return super.findTypeName(config, ac);
    }

    @Override
    public boolean hasIgnoreMarker(MapperConfig<?> config, AnnotatedMember member)
    {
        // _copy() is generated on every protocol impl as part of the Any contract;
        // the underscore-prefix accessor naming would otherwise pick it up as a
        // property called "copy".
        if (member instanceof AnnotatedMethod m
                && "_copy".equals(m.getName())
                && m.getParameterCount() == 0
                && isProtocolType(m.getDeclaringClass()))
        {
            return true;
        }
        return super.hasIgnoreMarker(config, member);
    }

    private static boolean isProtocolType(Class<?> raw)
    {
        return raw != null && raw.getName().startsWith(PROTOCOL_PACKAGE_PREFIX);
    }
}
