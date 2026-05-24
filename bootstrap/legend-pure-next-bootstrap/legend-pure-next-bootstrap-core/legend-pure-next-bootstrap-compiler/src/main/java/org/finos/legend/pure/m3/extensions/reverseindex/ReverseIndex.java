// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.m3.extensions.reverseindex;

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
 * Grammar-level element for the {@code ###ReverseIndex} test section.
 * Carries the raw expected text of the reverse reference index produced
 * during compilation — one line per {@code targetPath -> callerPath} pair.
 * The test assertion compares this expected text against what the compiler
 * actually recorded via {@code RecordingMetadataAccess}.
 */
public class ReverseIndex implements PackageableElement
{
    private Package_Pointer package_;
    private String value;

    @Override
    public String _name()
    {
        return "ReverseIndex";
    }

    @Override
    public PackageableElement _name(String value)
    {
        throw new UnsupportedOperationException("ReverseIndex name is fixed");
    }

    @Override
    public Package_Pointer _package()
    {
        return this.package_;
    }

    public ReverseIndex _package(Package_Pointer value)
    {
        this.package_ = value;
        return this;
    }

    public String _value()
    {
        return this.value;
    }

    public ReverseIndex _value(String value)
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
        throw new UnsupportedOperationException("ReverseIndex does not support copy");
    }
}
