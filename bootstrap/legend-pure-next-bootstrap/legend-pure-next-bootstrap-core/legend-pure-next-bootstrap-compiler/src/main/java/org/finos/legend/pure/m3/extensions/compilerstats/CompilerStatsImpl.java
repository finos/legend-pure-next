// Copyright 2026 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.m3.extensions.compilerstats;

import platform.pure.metamodel.Package;
import platform.pure.metamodel.PackageableElement;
import platform.pure.metamodel.SourceInformation;
import platform.pure.metamodel.extension.Stereotype;
import platform.pure.metamodel.extension.TaggedValue;
import platform.pure.metamodel.type.ElementOverride;
import platform.pure.metamodel.type.generics.GenericTypeValue;
import org.eclipse.collections.api.list.MutableList;

/**
 * Compiled model element for {@code ###CompilerStats} test sections.
 * Carries the expected resolver-telemetry text.
 */
public class CompilerStatsImpl implements PackageableElement
{
    private String name;
    private Package package_;
    private String value;

    @Override
    public String _name()
    {
        return this.name;
    }

    public CompilerStatsImpl _name(String value)
    {
        this.name = value;
        return this;
    }

    @Override
    public Package _package()
    {
        return this.package_;
    }

    public CompilerStatsImpl _package(Package value)
    {
        this.package_ = value;
        return this;
    }

    public String _value()
    {
        return this.value;
    }

    public CompilerStatsImpl _value(String value)
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
    public GenericTypeValue _classifierGenericType()
    {
        return null;
    }

    @Override
    public PackageableElement _classifierGenericType(GenericTypeValue value)
    {
        return this;
    }

    @Override
    public CompilerStatsImpl _copy()
    {
        throw new UnsupportedOperationException();
    }
}
