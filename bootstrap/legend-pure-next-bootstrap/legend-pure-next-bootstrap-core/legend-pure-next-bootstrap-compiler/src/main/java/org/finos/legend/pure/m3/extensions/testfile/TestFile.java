// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.m3.extensions.testfile;

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
 * Grammar-level element for the {@code ###File} test section. The body is
 * captured verbatim into {@code value}; the test harness splits the first
 * line off as the synthetic sourceId and treats the remainder as ###Pure
 * content for a separate compile-unit in {@code compileFiles}.
 */
public class TestFile implements PackageableElement
{
    private Package_Pointer package_;
    private String value;

    @Override
    public String _name()
    {
        return "TestFile";
    }

    @Override
    public PackageableElement _name(String value)
    {
        throw new UnsupportedOperationException("TestFile name is fixed");
    }

    @Override
    public Package_Pointer _package()
    {
        return this.package_;
    }

    public TestFile _package(Package_Pointer value)
    {
        this.package_ = value;
        return this;
    }

    public String _value()
    {
        return this.value;
    }

    public TestFile _value(String value)
    {
        this.value = value;
        return this;
    }

    @Override
    public SourceInformation _p_sourceInformation()
    {
        return null;
    }

    @Override
    public Any _p_sourceInformation(SourceInformation value)
    {
        return this;
    }

    @Override
    public MutableList<TaggedValue> _taggedValues()
    {
        return null;
    }

    @Override
    public ElementWithTaggedValues _taggedValues(MutableList<TaggedValue> value)
    {
        return this;
    }

    @Override
    public MutableList<Stereotype_Pointer> _stereotypes()
    {
        return null;
    }

    @Override
    public ElementWithStereotypes _stereotypes(MutableList<Stereotype_Pointer> value)
    {
        return this;
    }

    @Override
    public Any _copy()
    {
        throw new UnsupportedOperationException("TestFile does not support copy");
    }
}
