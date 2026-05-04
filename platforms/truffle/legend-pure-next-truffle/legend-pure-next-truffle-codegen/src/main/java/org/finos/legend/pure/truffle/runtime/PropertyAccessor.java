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

package org.finos.legend.pure.truffle.runtime;

/**
 * Common contract for getting a property by name on every generated metamodel
 * class — both {@code XImpl} (mutable POJOs) and {@code XFlatBufferWrapper}
 * (read-only FB-backed). Implemented via a generated {@code switch} on the
 * property name in each class. Replaces {@code MethodHandle.invoke}-based
 * reflection in {@code PropertyReadNode}, which was opaque to Graal partial
 * evaluation and contributed to "Too deep inlining" compilation failures.
 *
 * <p>Returns {@link #ABSENT} when the receiver has no property by that name —
 * callers like {@code PropertyReadNode.executeOrAbsent} use this token to
 * distinguish "property doesn't exist" from "property is the empty sequence".</p>
 */
public interface PropertyAccessor
{
    /** Sentinel returned when the receiver has no such property. */
    Object ABSENT = new Object();

    Object readProperty(String name);

    /**
     * Set the named property to {@code value}. Read-only receivers
     * (FlatBuffer wrappers) throw {@link UnsupportedOperationException};
     * mutable {@code XImpl} classes assign directly. Throws
     * {@link IllegalArgumentException} when the name doesn't match a
     * declared property — distinguishes "no such property" from "property
     * exists but is empty".
     */
    void writeProperty(String name, Object value);
}
