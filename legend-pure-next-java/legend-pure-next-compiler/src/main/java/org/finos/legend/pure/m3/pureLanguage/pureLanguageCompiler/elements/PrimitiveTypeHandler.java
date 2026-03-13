package org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.elements;

import meta.pure.metamodel.type.PrimitiveType;
import meta.pure.metamodel.type.PrimitiveTypeImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl;
import meta.pure.metamodel.valuespecification.VariableExpression;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.PureLanguageCompilerContext;
import org.finos.legend.pure.m3.module.localModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.ConstraintCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.GeneralizationCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.SourceInformationCompiler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.VariableCompiler;
import org.finos.legend.pure.next.parser.m3.helper._G_PackageableElement;

import java.util.Objects;

/**
 * Handler for PrimitiveType.
 */
public final class PrimitiveTypeHandler
{
    private PrimitiveTypeHandler()
    {
    }

    public static PrimitiveType firstPass(meta.pure.protocol.grammar.type.PrimitiveType grammar)
    {
        PrimitiveTypeImpl result = new PrimitiveTypeImpl()._name(grammar._name());
        if (grammar._sourceInformation() != null)
        {
            result._sourceInformation(SourceInformationCompiler.compile(grammar._sourceInformation()));
        }
        return result;
    }

    public static PrimitiveType secondPass(PrimitiveTypeImpl result, meta.pure.protocol.grammar.type.PrimitiveType grammar, MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        result._generalizations(grammar._generalizations()
                .collect(g -> GeneralizationCompiler.compile(g, result, imports, model, context))
                .select(Objects::nonNull));

        result._classifierGenericType(
                new ConcreteGenericTypeImpl()._rawType((Type) model.getElement("meta::pure::metamodel::type::PrimitiveType")));

        // Compile type variables (e.g., Primitive Varchar(x:Integer[1]))
        if (grammar._typeVariables() != null && grammar._typeVariables().notEmpty())
        {
            result._typeVariables(grammar._typeVariables()
                    .collect(v -> VariableCompiler.compileParameter(v, imports, model, context))
                    .select(Objects::nonNull));
        }

        // Create constraint shells (name, owner, source info) without expression sequences.
        // Expression sequences are compiled in thirdPass when all functions are available.
        if (grammar._constraints() != null && grammar._constraints().notEmpty())
        {
            result._constraints(grammar._constraints().collect(ConstraintCompiler::compileShell));
        }

        context.enrichCurrentErrors("primitive type '" + _G_PackageableElement.fullPath(grammar) + "'");
        return result;
    }

    /**
     * Third pass: compile and resolve expression sequences in constraint lambdas.
     * This must happen in third pass because the constraint body references functions
     * (e.g. length(), lessThan()) that are only available after all elements are indexed.
     */
    public static PrimitiveType thirdPass(PrimitiveType pt, meta.pure.protocol.grammar.type.PrimitiveType grammar,
                                 MutableList<String> imports, MetadataAccess model, CompilationContext context)
    {
        if (grammar._constraints() == null || grammar._constraints().isEmpty())
        {
            return pt;
        }

        VariableExpressionImpl thisVar = new VariableExpressionImpl()
                ._name("this")
                ._genericType(new ConcreteGenericTypeImpl()._rawType(pt))
                ._multiplicity((meta.pure.metamodel.multiplicity.Multiplicity) model.getElement("meta::pure::metamodel::multiplicity::PureOne"));

        // Push $this and type variables into scope
        MutableList<VariableExpression> scopeVars = Lists.mutable.<VariableExpression>with(thisVar);
        if (pt._typeVariables() != null)
        {
            scopeVars.addAll(pt._typeVariables());
        }
        context.compilerContextExtensions(PureLanguageCompilerContext.class).pushScope(scopeVars);

        try
        {
            // Type variables become extra lambda parameters so they are bound, not open
            MutableList<VariableExpression> extraParams = pt._typeVariables() != null
                    ? Lists.mutable.withAll(pt._typeVariables())
                    : null;

            ConstraintCompiler.resolveConstraints(pt._constraints(), grammar._constraints(), thisVar, extraParams, imports, model, context);
        }
        finally
        {
            context.compilerContextExtensions(PureLanguageCompilerContext.class).popScope();
        }

        return pt;
    }
}
