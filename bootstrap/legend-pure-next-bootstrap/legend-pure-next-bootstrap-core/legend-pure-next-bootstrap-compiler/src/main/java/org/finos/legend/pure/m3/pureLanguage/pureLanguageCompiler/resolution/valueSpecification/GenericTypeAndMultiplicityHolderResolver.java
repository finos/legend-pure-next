// Copyright 2026 Goldman Sachs
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

package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification;

import platform.pure.metamodel.multiplicity.Multiplicity;
import platform.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilationContext;
import platform.pure.metamodel.multiplicity.CompilerNotSetMultiplicity;
import platform.pure.metamodel.multiplicity.CompilerNotSetMultiplicityImpl;

public class GenericTypeAndMultiplicityHolderResolver
{
    public static GenericTypeAndMultiplicityHolder resolveGenericTypeAndMultiplicityHolder(GenericTypeAndMultiplicityHolder gm, MetadataAccess model, CompilationContext context)
    {
        if (gm._multiplicity() == null || gm._multiplicity() instanceof CompilerNotSetMultiplicity)
        {
            return (GenericTypeAndMultiplicityHolder)((GenericTypeAndMultiplicityHolder) gm._copy())
                    ._multiplicity((Multiplicity) model.getElement("platform::pure::metamodel::multiplicity::PureOne"));
        }
        return gm;
    }
}
