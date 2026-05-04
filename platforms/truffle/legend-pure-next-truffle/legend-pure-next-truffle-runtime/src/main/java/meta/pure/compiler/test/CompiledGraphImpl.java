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

package meta.pure.compiler.test;

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
 * Bootstrap-namespace grammar element for {@code ###CompiledGraph} sections,
 * placed in the {@code meta.pure.compiler.test} package so that
 * {@link org.finos.legend.pure.truffle.runtime.ProtocolTranslator} can
 * translate it to the PDB-generated
 * {@link org.finos.legend.pure.truffle.pdb.meta.pure.compiler.test.CompiledGraphImpl}.
 */
public class CompiledGraphImpl implements PackageableElement
{
    private String name;
    private Package_Pointer package_;
    private String value;

    @Override
    public String _name()
    {
        return this.name;
    }

    @Override
    public PackageableElement _name(String value)
    {
        this.name = value;
        return this;
    }

    @Override
    public Package_Pointer _package()
    {
        return this.package_;
    }

    public CompiledGraphImpl _package(Package_Pointer value)
    {
        this.package_ = value;
        return this;
    }

    public String _value()
    {
        return this.value;
    }

    public CompiledGraphImpl _value(String value)
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
        throw new UnsupportedOperationException();
    }
}
