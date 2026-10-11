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

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._GenericType;
import org.finos.legend.pure.specification.generation.model.M3MetamodelReader;
import org.junit.jupiter.api.Test;
import platform.pure.metamodel.type.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A type's generalizations are ORDERED: the order in m3.ttl is the order every reader must see.
 *
 * <p>Turtle's comma form ({@code :generalizations [ … ], [ … ]}) is an unordered set of blank
 * nodes, and Jena hands them back in an order that changes on every load. That order reached the
 * generated Java PDB writer (Class's fields were written in a different order on every
 * {@code mvn generate-sources}, so regenerating the goldens rewrote all of them) and the
 * bootstrap metamodel. The fix is at the source: generalizations are an RDF collection
 * {@code ( [ … ] [ … ] )}, which has an order, and the readers walk it.</p>
 */
public class GeneralizationOrderTest
{
    private static final String M3_NS = "https://finos.org/legend/pure/m3#";

    // :Class's supertypes as m3.ttl declares them.
    private static final MutableList<String> CLASS_GENERALIZATIONS = Lists.mutable.with(
            "NamedType", "PropertyOwner", "ElementWithConstraints", "Testable", "TypeAndMultiplicityParametersOwner");

    @Test
    public void m3TtlDeclaresGeneralizationsAsOrderedLists()
    {
        assertEquals(Lists.mutable.empty(), unorderedGeneralizations("generated-specification/m3.ttl"));
    }

    @Test
    public void m3ProtocolTtlDeclaresGeneralizationsAsOrderedLists()
    {
        assertEquals(Lists.mutable.empty(), unorderedGeneralizations("generated-specification/m3_protocol.ttl"));
    }

    @Test
    public void generatorReaderKeepsSourceOrder()
    {
        M3MetamodelReader reader = new M3MetamodelReader(BootstrapModule.locateM3Ttl().toString());
        assertEquals(CLASS_GENERALIZATIONS, reader.read().classInfoMap().get("Class").generalizations);
    }

    @Test
    public void bootstrapReaderKeepsSourceOrder()
    {
        BootstrapModule module = new BootstrapModule(BootstrapModule.locateM3Ttl());
        Type type = (Type) module.getElement("platform::pure::metamodel::type::Class");
        assertEquals(CLASS_GENERALIZATIONS, type._generalizations().collect(g -> _GenericType.print(g._general(), false)));
    }

    // The subjects whose :generalizations value is not an RDF collection, as "<subject> -> <value>".
    private static MutableList<String> unorderedGeneralizations(String resource)
    {
        Model model = ModelFactory.createDefaultModel();
        try (InputStream in = GeneralizationOrderTest.class.getClassLoader().getResourceAsStream(resource))
        {
            assertNotNull(in, resource + " is not on the classpath");
            model.read(in, null, "TTL");
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        MutableList<String> offenders = Lists.mutable.empty();
        model.listStatements(null, model.createProperty(M3_NS + "generalizations"), (RDFNode) null).forEach(stmt ->
        {
            if (!isList(stmt))
            {
                offenders.add(stmt.getSubject().getLocalName() + " -> " + stmt.getObject());
            }
        });
        return offenders.sortThis();
    }

    private static boolean isList(Statement stmt)
    {
        RDFNode value = stmt.getObject();
        return value.isResource() && (value.equals(RDF.nil) || value.asResource().hasProperty(RDF.first));
    }
}
