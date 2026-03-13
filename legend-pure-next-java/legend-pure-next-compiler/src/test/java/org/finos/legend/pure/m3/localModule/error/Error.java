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

package org.finos.legend.pure.m3.localModule.error;

import meta.pure.protocol.grammar.Package_Pointer;
import meta.pure.protocol.grammar.PackageableElement;
import meta.pure.protocol.grammar.SourceInformation;
import meta.pure.protocol.grammar.extension.ElementWithStereotypes;
import meta.pure.protocol.grammar.extension.ElementWithTaggedValues;
import meta.pure.protocol.grammar.extension.Stereotype_Pointer;
import meta.pure.protocol.grammar.extension.TaggedValue;
import meta.pure.protocol.grammar.type.Any;
import org.eclipse.collections.api.list.MutableList;

/**
 * Grammar-level element for the {@code ###Error} test section.
 * Contains the raw expected-error text.
 */
public class Error implements PackageableElement
{
    private Package_Pointer package_;
    private String value;

    @Override
    public String _name()
    {
        return "Error";
    }

    @Override
    public PackageableElement _name(String value)
    {
        throw new UnsupportedOperationException("Error name is fixed");
    }

    @Override
    public Package_Pointer _package()
    {
        return this.package_;
    }

    public Error _package(Package_Pointer value)
    {
        this.package_ = value;
        return this;
    }

    public String _value()
    {
        return this.value;
    }

    public Error _value(String value)
    {
        this.value = value;
        return this;
    }

    @Override
    public SourceInformation _sourceInformation()
    {
        throw new UnsupportedOperationException("Error does not support sourceInformation");
    }

    @Override
    public Any _sourceInformation(SourceInformation value)
    {
        throw new UnsupportedOperationException("Error does not support sourceInformation");
    }

    @Override
    public MutableList<TaggedValue> _taggedValues()
    {
        throw new UnsupportedOperationException("Error does not support taggedValues");
    }

    @Override
    public ElementWithTaggedValues _taggedValues(MutableList<TaggedValue> value)
    {
        throw new UnsupportedOperationException("Error does not support taggedValues");
    }

    @Override
    public MutableList<Stereotype_Pointer> _stereotypes()
    {
        throw new UnsupportedOperationException("Error does not support stereotypes");
    }

    @Override
    public ElementWithStereotypes _stereotypes(MutableList<Stereotype_Pointer> value)
    {
        throw new UnsupportedOperationException("Error does not support stereotypes");
    }
}
