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

package org.finos.legend.pure.m3.specification;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.LanguageExtension;
import org.finos.legend.pure.m3.module.MetadataAccess;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilationContext;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.CompilerContextExtension;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.IndexEntry;
import org.finos.legend.pure.m3.module.sourceModule.topLevel.IslandHandler;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.structural.ValueSpecificationCompiler;
import platform.pure.metamodel.PackageableElement;
import platform.pure.metamodel.valuespecification.ValueSpecification;
import platform.pure.protocol.grammar.Package_PointerImpl;
import platform.pure.protocol.grammar.type.Type_PointerImpl;
import platform.pure.protocol.grammar.type.generics.UserDefinedGenericTypeImpl;
import platform.pure.protocol.grammar.valuespecification.AtomicValueImpl;
import platform.pure.protocol.grammar.valuespecification.Island;

import java.util.List;

/**
 * The specification's toy island handlers, as compiler-pure's test package declares them
 * ({@code platform::pure::compiler::test::islandTestExtension}): {@code echo} claims {@code #ECHO{…}#}
 * and compiles it to the String between the braces; {@code echoTwice} claims {@code #ECHO{{…}}#}, which
 * {@code echo} claims too, so ambiguity can be tested; {@code ref} claims {@code #REF{m::Person}#} and
 * compiles it to the element at that path — an island pointing into the graph. With them, this compiler and the Pure one agree
 * on pure/specification/compiler/tests/valueSpecification/island. It carries no section of its own.
 */
public final class IslandTestLanguageExtension implements LanguageExtension
{
    private static final ListIterable<IslandHandler> HANDLERS = Lists.immutable.with(echo("echo", "ECHO{", "}"), echo("echoTwice", "ECHO{{", "}}"), ref());

    @Override
    public ListIterable<IslandHandler> islandHandlers()
    {
        return HANDLERS;
    }

    private static IslandHandler echo(String name, String open, String close)
    {
        return new IslandHandler()
        {
            @Override
            public String name()
            {
                return name;
            }

            @Override
            public boolean claims(String text)
            {
                return text.startsWith(open) && text.endsWith(close);
            }

            @Override
            public ValueSpecification compile(Island island, MutableList<String> imports, MetadataAccess model, CompilationContext context)
            {
                String text = island._text();
                int bang = text.indexOf('!');
                if (bang >= 0)
                {
                    // Refused, AT the `!`: its line and column in the island's text, 1-based.
                    String before = text.substring(0, bang);
                    long line = before.length() - before.replace("\n", "").length() + 1;
                    long column = bang - before.lastIndexOf('\n');
                    return ValueSpecificationCompiler.islandErrorAt("An echo cannot contain '!'",
                            IslandHandler.sourceInformation(island, line, column, line, column), imports, model, context);
                }
                return ValueSpecificationCompiler.compile(new AtomicValueImpl()
                        ._p_sourceInformation(island._p_sourceInformation())
                        ._genericType(new UserDefinedGenericTypeImpl()._type(new Type_PointerImpl()._value("platform::pure::metamodel::type::primitives::String")))
                        ._value(text.substring(open.length(), text.length() - close.length())), imports, model, context);
            }
        };
    }

    // `#REF{m::Person}#`: the element at that path, as writing `m::Person` would give — an atomic value
    // holding a pointer to it, compiled and resolved (and recorded in the reverse index) like any other. A
    // path naming nothing is refused AT the path, inside the island.
    private static IslandHandler ref()
    {
        String open = "REF{";
        return new IslandHandler()
        {
            @Override
            public String name()
            {
                return "ref";
            }

            @Override
            public boolean claims(String text)
            {
                return text.startsWith(open) && text.endsWith("}");
            }

            @Override
            public ValueSpecification compile(Island island, MutableList<String> imports, MetadataAccess model, CompilationContext context)
            {
                String path = island._text().substring(open.length(), island._text().length() - 1);
                if (model.getElement(path) == null)
                {
                    return ValueSpecificationCompiler.islandErrorAt("The element '" + path + "' cannot be found",
                            IslandHandler.sourceInformation(island, 1, open.length() + 1, 1, open.length() + path.length()), imports, model, context);
                }
                return ValueSpecificationCompiler.compile(new AtomicValueImpl()
                        ._p_sourceInformation(island._p_sourceInformation())
                        ._value(new Package_PointerImpl()._value(path)), imports, model, context);
            }
        };
    }

    @Override
    public String sectionName()
    {
        return "IslandTest";
    }

    @Override
    public List<platform.pure.protocol.grammar.PackageableElement> parseSection(String content, String sourceId, int lineOffset)
    {
        return List.of();
    }

    @Override
    public CompilerContextExtension buildCompilerContextExtension()
    {
        return null;
    }

    @Override
    public PackageableElement firstPass(platform.pure.protocol.grammar.PackageableElement grammar, MetadataAccess model, CompilationContext context)
    {
        return null;
    }

    @Override
    public PackageableElement secondPass(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        return null;
    }

    @Override
    public PackageableElement thirdPass(IndexEntry entry, MetadataAccess model, CompilationContext context)
    {
        return null;
    }
}
