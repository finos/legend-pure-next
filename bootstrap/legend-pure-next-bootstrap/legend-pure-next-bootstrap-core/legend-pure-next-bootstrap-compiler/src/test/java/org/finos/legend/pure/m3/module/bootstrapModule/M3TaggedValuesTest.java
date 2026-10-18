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

package org.finos.legend.pure.m3.module.bootstrapModule;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Test;
import platform.pure.metamodel.extension.ElementWithTaggedValues;
import platform.pure.metamodel.extension.Profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * m3.ttl's tagged values reach the bootstrap metamodel.
 *
 * <p>{@code {ProtocolInfo.nonPointerSubtypes = '…'}} on Type, GenericType, FunctionDefinition and
 * Multiplicity says which subtypes a pointer-typed slot may still carry INLINE. The Java fbs
 * generator reads it from m3.ttl; the Pure one (compiler-pure pdb/fbsgen) reads it from the
 * compiled classes in core.pdb — which only works if the bootstrap reader puts it there, as it
 * already does for stereotypes.</p>
 */
public class M3TaggedValuesTest
{
    @Test
    public void classesCarryTheirTaggedValues()
    {
        BootstrapModule module = new BootstrapModule(BootstrapModule.locateM3Ttl());
        ElementWithTaggedValues type = (ElementWithTaggedValues) module.getElement("platform::pure::metamodel::type::Type");
        assertEquals(Lists.mutable.with("nonPointerSubtypes=FunctionType,RelationType,TypeParameter"),
                Lists.mutable.withAll(type._taggedValues()).collect(tv -> tv._tag()._value() + "=" + tv._value()));
    }

    @Test
    public void profilesOwnTheirTags()
    {
        BootstrapModule module = new BootstrapModule(BootstrapModule.locateM3Ttl());
        Profile protocolInfo = (Profile) module.getElement("platform::pure::metamodel::ProtocolInfo");
        assertEquals(Lists.mutable.with("nonPointerSubtypes"),
                Lists.mutable.withAll(protocolInfo._p_tags()).collect(t -> t._value()));
    }
}
