package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification;

import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.valuespecification.GenericTypeAndMultiplicityHolder;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;

public class GenericTypeAndMultiplicityHolderResolver
{
    public static GenericTypeAndMultiplicityHolder resolveGenericTypeAndMultiplicityHolder(GenericTypeAndMultiplicityHolder gm, MetadataAccess model, CompilationContext context)
    {
        if (gm._multiplicity() == null)
        {
            gm._multiplicity((Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::PureOne"));
        }
        return gm;
    }
}
