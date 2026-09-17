package org.finos.legend.pure.runtime.compilation;

import meta.pure.metamodel.PackageableElement;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.JavaCompiler;
import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.compilation.Compilation;
import org.finos.legend.pure.m3.compilation.CompilationContext;
import org.finos.legend.pure.m3.module.CompilationResult;
import org.finos.legend.pure.m3.module.ModuleRegistry;
import org.finos.legend.pure.m3.module.sourceModule.SourceModule;
import org.finos.legend.pure.m3.pureLanguage.PureLanguageExtension;

import java.util.ArrayList;
import java.util.List;

/**
 * Bootstrap's Java compiler ({@link JavaCompiler}) as a compilation strategy: one compiler over
 * the runtime's {@link ModuleRegistry}, with {@link PureLanguageExtension} and the runtime's
 * language extensions (their parser and compiler halves). Each source module is attached to the
 * registry and compiled against it; the registry's modules stay attached as they are.
 *
 * <p>Needs no compiler.pdb, so it can build core.pdb.</p>
 */
public final class JavaCompilation implements Compilation
{
    private final ModuleRegistry registry;
    private final MutableList<LanguageExtension> extensions;
    private final JavaCompiler compiler;

    private JavaCompilation(CompilationContext context)
    {
        if (!(context.registry() instanceof ModuleRegistry moduleRegistry))
        {
            throw new IllegalStateException("JavaCompilation compiles against a ModuleRegistry (got "
                    + (context.registry() == null ? "null" : context.registry().getClass().getSimpleName()) + ")");
        }
        this.registry = moduleRegistry;
        this.extensions = Lists.mutable.empty();
        if (context.languageExtensions().stream().noneMatch(PureLanguageExtension.class::isInstance))
        {
            this.extensions.add(new PureLanguageExtension());
        }
        this.extensions.addAll(context.languageExtensions());
        this.compiler = JavaCompiler.withRegistry(moduleRegistry).withExtensions(this.extensions).build();
    }

    public static Builder builder()
    {
        return new Builder();
    }

    @Override
    public CompilationResult compile(SourceModule module)
    {
        module.attach(this.registry, this.extensions);
        CompilationResult result = this.compiler.compile(module);
        List<PackageableElement> elements = new ArrayList<>();
        if (result.errors().isEmpty())
        {
            for (String path : module.elementPaths())
            {
                if (this.registry.hasElement(path))
                {
                    continue;
                }
                PackageableElement element = module.getElement(path);
                if (element != null)
                {
                    elements.add(element);
                }
            }
        }
        return new CompilationResult(elements, result.errors(), result.statistics(), result.referencedBy());
    }

    /** One pass of the Java compiler over the registry: its source modules compile in place. */
    @Override
    public CompilationResult compileSourceModules()
    {
        return this.compiler.compile();
    }

    public static final class Builder implements Compilation.Builder
    {
        private Builder()
        {
        }

        @Override
        public JavaCompilation build(CompilationContext context)
        {
            return new JavaCompilation(context);
        }
    }
}
