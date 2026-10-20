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

package org.finos.legend.pure.platform.java.compiler.module.pdbModule;

import org.finos.legend.pure.m3.platform.pure.metamodel.SourceInformation;
import org.finos.legend.pure.m3.platform.pure.metamodel.extension.Stereotype;
import org.finos.legend.pure.m3.platform.pure.metamodel.extension.TaggedValue;
import org.finos.legend.pure.m3.platform.pure.metamodel.type.ElementOverride;
import org.finos.legend.pure.m3.platform.pure.metamodel.type.Enum;
import org.finos.legend.pure.m3.platform.pure.metamodel.type.generics.GenericTypeValue;

import java.util.List;

/**
 * A constant of a generated Java enum IS a metamodel {@link Enum}: a value of its enumeration, a
 * NamedElement, an AddressableElement and an AnnotatedElement, so it goes wherever the metamodel says an
 * enum value goes ({@code address(CITY)}, a match arm on Enum, a function taking a NamedElement).
 *
 * <p>The constant holds nothing itself. Its name is {@code java.lang.Enum.name()}; its classifier is its
 * enumeration; what the PDB records for it (stereotypes, tagged values, source information) is read from
 * the enumeration's metadata value of the same name. An enum value is immutable: the fluent setters and
 * {@code _copy} refuse.</p>
 */
public interface PureEnumConstant extends PureEnumValue, Enum
{
    @Override
    default GenericTypeValue classifierGenericType()
    {
        return (GenericTypeValue) Metadata.genericType(this);
    }

    @Override
    default SourceInformation sourceInformation()
    {
        return (SourceInformation) Metadata.enumValueSlot(this, "sourceInformation");
    }

    @Override
    default ElementOverride elementOverride()
    {
        return (ElementOverride) Metadata.enumValueSlot(this, "elementOverride");
    }

    @Override
    @SuppressWarnings("unchecked")
    default List<Stereotype> stereotypes()
    {
        return (List<Stereotype>) (List<?>) Metadata.enumValueSlots(this, "stereotypes");
    }

    @Override
    @SuppressWarnings("unchecked")
    default List<TaggedValue> taggedValues()
    {
        return (List<TaggedValue>) (List<?>) Metadata.enumValueSlots(this, "taggedValues");
    }

    @Override
    default Enum _copy()
    {
        throw immutable("copy");
    }

    @Override
    default Enum _name(Object value)
    {
        throw immutable("name");
    }

    @Override
    default Enum _name_add(Object value)
    {
        throw immutable("name");
    }

    @Override
    default Enum _stereotypes(Object value)
    {
        throw immutable("stereotypes");
    }

    @Override
    default Enum _stereotypes_add(Object value)
    {
        throw immutable("stereotypes");
    }

    @Override
    default Enum _taggedValues(Object value)
    {
        throw immutable("taggedValues");
    }

    @Override
    default Enum _taggedValues_add(Object value)
    {
        throw immutable("taggedValues");
    }

    @Override
    default Enum _elementOverride(Object value)
    {
        throw immutable("elementOverride");
    }

    @Override
    default Enum _elementOverride_add(Object value)
    {
        throw immutable("elementOverride");
    }

    @Override
    default Enum _classifierGenericType(Object value)
    {
        throw immutable("classifierGenericType");
    }

    @Override
    default Enum _classifierGenericType_add(Object value)
    {
        throw immutable("classifierGenericType");
    }

    @Override
    default Enum _sourceInformation(Object value)
    {
        throw immutable("sourceInformation");
    }

    @Override
    default Enum _sourceInformation_add(Object value)
    {
        throw immutable("sourceInformation");
    }

    private UnsupportedOperationException immutable(String what)
    {
        return new UnsupportedOperationException("Cannot change " + what + " of the enum value " + _purePath() + "." + ((java.lang.Enum<?>) this).name() + ": an enum value is immutable");
    }
}
