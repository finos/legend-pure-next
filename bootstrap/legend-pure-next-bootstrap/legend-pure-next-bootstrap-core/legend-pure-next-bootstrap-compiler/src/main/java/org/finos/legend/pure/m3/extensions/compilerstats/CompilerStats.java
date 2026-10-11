// Copyright 2026 Goldman Sachs
// ©2026 JP Morgan Chase & Co. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package org.finos.legend.pure.m3.extensions.compilerstats;

import platform.pure.protocol.grammar.Package_Pointer;
import platform.pure.protocol.grammar.PackageableElement;
import platform.pure.protocol.grammar.SourceInformation;
import platform.pure.protocol.grammar.extension.ElementWithStereotypes;
import platform.pure.protocol.grammar.extension.ElementWithTaggedValues;
import platform.pure.protocol.grammar.extension.Stereotype_Pointer;
import platform.pure.protocol.grammar.extension.TaggedValue;
import platform.pure.protocol.grammar.type.Any;
import org.eclipse.collections.api.list.MutableList;

/**
 * Grammar-level element for the {@code ###CompilerStats} test section.
 * Contains the raw expected resolver-telemetry text — total
 * {@code inferenceRollbacks}, {@code candidateEvaluations}, and the
 * {@code rollbackSites} histogram that the test assertion compares against.
 */
public class CompilerStats implements PackageableElement
{
    private Package_Pointer package_;
    private String value;

    @Override
    public String _name()
    {
        return "CompilerStats";
    }

    @Override
    public PackageableElement _name(String value)
    {
        throw new UnsupportedOperationException("CompilerStats name is fixed");
    }

    @Override
    public Package_Pointer _package()
    {
        return this.package_;
    }

    public CompilerStats _package(Package_Pointer value)
    {
        this.package_ = value;
        return this;
    }

    public String _value()
    {
        return this.value;
    }

    public CompilerStats _value(String value)
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
        throw new UnsupportedOperationException("CompilerStats does not support copy");
    }
}
