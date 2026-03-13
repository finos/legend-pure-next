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

import meta.pure.metamodel.Package;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.SourceInformation;
import meta.pure.metamodel.extension.Stereotype;
import meta.pure.metamodel.extension.TaggedValue;
import meta.pure.metamodel.type.ElementOverride;
import meta.pure.metamodel.type.generics.GenericType;
import org.eclipse.collections.api.list.MutableList;

/**
 * Compiled model element for {@code ###Error} test sections.
 * Carries the expected error message text.
 */
public class ErrorImpl implements PackageableElement
{
    private String name;
    private Package package_;
    private String value;

    @Override
    public String _name()
    {
        return this.name;
    }

    public ErrorImpl _name(String value)
    {
        this.name = value;
        return this;
    }

    @Override
    public Package _package()
    {
        return this.package_;
    }

    public ErrorImpl _package(Package value)
    {
        this.package_ = value;
        return this;
    }

    public String _value()
    {
        return this.value;
    }

    public ErrorImpl _value(String value)
    {
        this.value = value;
        return this;
    }

    @Override
    public SourceInformation _sourceInformation()
    {
        return null;
    }

    @Override
    public PackageableElement _sourceInformation(SourceInformation value)
    {
        return this;
    }

    @Override
    public MutableList<TaggedValue> _taggedValues()
    {
        return null;
    }

    @Override
    public PackageableElement _taggedValues(MutableList<TaggedValue> value)
    {
        return this;
    }

    @Override
    public MutableList<Stereotype> _stereotypes()
    {
        return null;
    }

    @Override
    public PackageableElement _stereotypes(MutableList<Stereotype> value)
    {
        return this;
    }

    @Override
    public ElementOverride _elementOverride()
    {
        return null;
    }

    @Override
    public PackageableElement _elementOverride(ElementOverride value)
    {
        return this;
    }

    @Override
    public GenericType _classifierGenericType()
    {
        return null;
    }

    @Override
    public PackageableElement _classifierGenericType(GenericType value)
    {
        return this;
    }
}
