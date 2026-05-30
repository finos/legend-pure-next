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

package org.finos.legend.pure.truffle.runtime.dynobj;

import org.finos.legend.pure.truffle.runtime.TruffleInstanceFactory;
import org.finos.legend.pure.truffle.runtime.TruffleMetadataAccess;

/**
 * Fluent builder for {@link PureDynamicObject}. Used by code-generated parsers
 * (notably the Truffle target of {@code PureLanguageVisitorMappingGenerator}) to express a
 * complete object construction as a single Java expression — required because
 * {@link PureObj#write} returns {@code void} and so doesn't compose into a
 * fluent chain.
 *
 * <p>Equivalent to:
 * <pre>{@code
 * Object o = TruffleInstanceFactory.createInstance(purePath, resolver);
 * PureObj.write(o, "k1", v1);
 * PureObj.write(o, "k2", v2);
 * return o;
 * }</pre>
 * in one expression:
 * <pre>{@code
 * PureObjBuilder.of(purePath, resolver).put("k1", v1).put("k2", v2).build();
 * }</pre>
 *
 * <p>Each {@code put} skips null values silently — they correspond to absent
 * optional properties and don't need to be materialised on the PDO.
 */
public final class PureObjBuilder
{
    private static final int SLOT_CLASSIFIER_GENERIC_TYPE = PureClassRegistry.globalSlot("classifierGenericType");

    private final Object pdo;
    private final String purePath;
    private final TruffleMetadataAccess resolver;

    private PureObjBuilder(String purePath, TruffleMetadataAccess resolver)
    {
        this.pdo = TruffleInstanceFactory.createInstance(purePath, resolver);
        this.purePath = purePath;
        this.resolver = resolver;
    }

    public static PureObjBuilder of(String purePath, TruffleMetadataAccess resolver)
    {
        return new PureObjBuilder(purePath, resolver);
    }

    public PureObjBuilder put(String name, Object value)
    {
        if (value == null) return this;
        PureObj.write(pdo, name, value);
        return this;
    }

    /**
     * Finalise and return the PDO. If the caller didn't explicitly set
     * {@code classifierGenericType} and a resolver is available, stamp one based on
     * {@code purePath}. Pure-runtime code (match dispatch, instanceOf, copy with keys)
     * reads CGT to recover the value's type — without it, {@code SpecializedMatchNode}
     * can't find a matching branch and throws.
     */
    public Object build()
    {
        if (resolver != null && PureObj.readBySlot(pdo, SLOT_CLASSIFIER_GENERIC_TYPE) == null)
        {
            Object classType = resolver.getElement(purePath);
            if (classType != null)
            {
                PureObj.write(pdo, "classifierGenericType",
                        org.finos.legend.pure.truffle.runtime.helper._GenericType
                                .buildUserDefinedGenericType(classType, resolver));
            }
        }
        return pdo;
    }
}
