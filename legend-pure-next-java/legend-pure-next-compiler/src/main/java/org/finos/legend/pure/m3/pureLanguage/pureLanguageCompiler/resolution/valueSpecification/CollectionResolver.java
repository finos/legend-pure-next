package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.resolution.valueSpecification;

import meta.pure.metamodel.valuespecification.Collection;
import meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;

public class CollectionResolver
{
    public static Collection resolveCollection(Collection col, MetadataAccess model, CompilationContext context)
    {
        return (Collection) col._values(col._values().collect(x -> ValueSpecificationResolver.resolve(x, model, context)))
                               ._genericType(_GenericType.asInferred(_GenericType.findCommonGenericType(col._values().collect(ValueSpecification::_genericType), model)));
    }
}
