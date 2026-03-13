// Copyright 2024 Goldman Sachs
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

package org.finos.legend.pure.specification.generation;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.RDFDataMgr;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.factory.SortedMaps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.map.sorted.MutableSortedMap;
import org.eclipse.collections.api.set.MutableSet;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Generates m3_protocol.ttl from m3.ttl.
 *
 * <p>Removes classes and properties with excluded or inferred stereotypes,
 * generalizations pointing to removed types, and properties whose types
 * reference removed types. Also transforms package paths from metamodel
 * to protocol. Additional TTL files can be merged into the output.</p>
 */
public class M3ProtocolGenerator
{
    private static final String M3_NS =
        "https://finos.org/legend/pure/m3#";
    private static final String RDF_TYPE_NS =
        "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    private final Model model;
    private final MutableSet<Resource> excludedTypes = Sets.mutable.empty();

    private final Resource m3Class;
    private final Resource m3PrimitiveType;
    private final Resource m3Property;
    private final Resource m3Enumeration;
    private final Resource m3Multiplicity;
    private final Resource m3Package;
    private final Resource m3Profile;
    private final Resource m3Stereotype;
    private final Property stereotypesProp;
    private final Property packageProp;
    private final Property nameProp;
    private final Property genericTypeProp;
    private final Property rawTypeProp;
    private final Property generalizationsProp;
    private final Property generalProp;
    private final Property ownerProp;
    private final Property multiplicityProp;
    private final Resource protocolInfoExcluded;
    private final Resource protocolInfoInferred;
    private final Resource protocolInfoPointer;
    private final Resource protocolInfoMainTaxonomy;
    private final Property taggedValuesProp;
    private final Property tagProp;
    private final Property valueProp;
    private final Property rdfType;
    private final Resource m3PureOne;
    private final Resource m3ZeroMany;
    private final Resource m3String;
    private final Resource m3Any;
    private final Resource protocolInfoNonPointerSubtypes;
    private Resource pointerValueClass;

    /**
     * Create a new generator for the given input file.
     *
     * @param inputPath path to m3.ttl input file
     */
    public M3ProtocolGenerator(String inputPath)
    {
        this.model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, inputPath);

        this.m3Class = model.createResource(M3_NS + "Class");
        this.m3PrimitiveType = model.createResource(M3_NS + "PrimitiveType");
        this.m3Property = model.createResource(M3_NS + "Property");
        this.m3Enumeration = model.createResource(M3_NS + "Enumeration");
        this.m3Multiplicity = model.createResource(M3_NS + "Multiplicity");
        this.m3Package = model.createResource(M3_NS + "Package");
        this.m3Profile = model.createResource(M3_NS + "Profile");
        this.m3Stereotype = model.createResource(M3_NS + "Stereotype");
        this.stereotypesProp = model.createProperty(M3_NS, "stereotypes");
        this.packageProp = model.createProperty(M3_NS, "package");
        this.nameProp = model.createProperty(M3_NS, "name");
        this.genericTypeProp = model.createProperty(M3_NS, "genericType");
        this.rawTypeProp = model.createProperty(M3_NS, "rawType");
        this.generalizationsProp =
            model.createProperty(M3_NS, "generalizations");
        this.generalProp = model.createProperty(M3_NS, "general");
        this.ownerProp = model.createProperty(M3_NS, "owner");
        this.multiplicityProp = model.createProperty(M3_NS, "multiplicity");
        this.protocolInfoExcluded =
            model.createResource(M3_NS + "ProtocolInfo_excluded");
        this.protocolInfoInferred =
            model.createResource(M3_NS + "ProtocolInfo_inferred");
        this.protocolInfoPointer =
            model.createResource(M3_NS + "ProtocolInfo_pointer");
        this.protocolInfoMainTaxonomy =
            model.createResource(M3_NS + "ProtocolInfo_mainTaxonomy");
        this.taggedValuesProp = model.createProperty(M3_NS, "taggedValues");
        this.tagProp = model.createProperty(M3_NS, "tag");
        this.valueProp = model.createProperty(M3_NS, "value");
        this.rdfType = model.createProperty(RDF_TYPE_NS, "type");
        this.m3PureOne = model.createResource(M3_NS + "PureOne");
        this.m3ZeroMany = model.createResource(M3_NS + "ZeroMany");
        this.m3String = model.createResource(M3_NS + "String");
        this.m3Any = model.createResource(M3_NS + "Any");
        this.protocolInfoNonPointerSubtypes =
            model.createResource(M3_NS + "ProtocolInfo_nonPointerSubtypes");
    }

    /**
     * Generate the protocol model and write to output path.
     *
     * @param outputPath          path to write m3_protocol.ttl
     * @param additionalTtlPaths  extra TTL files to merge into the model
     * @throws IOException if file cannot be written
     */
    public void generate(Path outputPath, List<String> additionalTtlPaths) throws IOException
    {
        System.out.println("Generating m3_protocol.ttl...");
        System.out.println("Original model size: " + model.size());

        identifyExcludedTypes();
        System.out.println("Found " + excludedTypes.size() + " to exclude");

        int removedClasses = removeExcludedClasses();
        System.out.println("Removed " + removedClasses + " classes");

        int removedProperties = removeExcludedProperties();
        System.out.println("Removed " + removedProperties + " properties");

        int removedGens = removeGeneralizationsToExcludedTypes();
        System.out.println("Removed " + removedGens + " generalizations");

        int removedTypeRefs = removePropertiesReferencingExcludedTypes();
        System.out.println("Removed " + removedTypeRefs + " type refs");

        int pointerTypes = processPointerProperties();
        System.out.println("Created " + pointerTypes + " pointer types");

        int transformed = transformPackagePaths();
        System.out.println("Transformed " + transformed + " packages");

        loadAdditionalTtl(additionalTtlPaths);

        System.out.println("Final model size: " + model.size());

        if (outputPath.getParent() != null)
        {
            Files.createDirectories(outputPath.getParent());
        }
        writeFormattedTtl(outputPath);
        System.out.println("Wrote protocol model to: " + outputPath);
    }

    /**
     * Load additional TTL files into the model.
     */
    private void loadAdditionalTtl(List<String> paths)
    {
        if (paths == null)
        {
            return;
        }
        paths.forEach(path ->
        {
            System.out.println("Loading additional TTL: " + path);
            RDFDataMgr.read(model, path);
        });
    }

    private void writeFormattedTtl(Path outputPath) throws IOException
    {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(outputPath.toFile())))
        {
            writeHeader(w);
            writeClasses(w);
            writePrimitiveTypes(w);
            writeEnumerations(w);
            writeMultiplicities(w);
            writePackages(w);
            writeProfilesAndStereotypes(w);
        }
    }

    private void writeHeader(BufferedWriter w) throws IOException
    {
        w.write("# Pure M3 Protocol Model - RDF Turtle\n");
        w.write("# Generated from m3.ttl\n");
        w.write("# Namespace: https://finos.org/legend/pure/m3#\n\n");
        w.write("@prefix : <https://finos.org/legend/pure/m3#> .\n");
        w.write("@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n\n");
    }

    private void writeClasses(BufferedWriter w) throws IOException
    {
        writeSectionHeader(w, "CLASSES AND THEIR PROPERTIES");

        MutableSortedMap<String, Resource> classes = SortedMaps.mutable.empty();
        ResIterator iter = model.listSubjectsWithProperty(rdfType, m3Class);
        while (iter.hasNext())
        {
            Resource r = iter.next();
            String name = getLocalName(r);
            if (name != null)
            {
                classes.put(name, r);
            }
        }

        classes.forEachKeyValue((name, r) ->
        {
            try
            {
                writeClass(w, r);
                writePropertiesForOwner(w, r);
                w.write("\n");
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        });
    }

    private void writePrimitiveTypes(BufferedWriter w) throws IOException
    {
        writeSectionHeader(w, "PRIMITIVE TYPES");

        MutableSortedMap<String, Resource> primitives = SortedMaps.mutable.empty();
        ResIterator iter = model.listSubjectsWithProperty(rdfType, m3PrimitiveType);
        while (iter.hasNext())
        {
            Resource r = iter.next();
            String name = getLocalName(r);
            if (name != null)
            {
                primitives.put(name, r);
            }
        }

        primitives.forEachKeyValue((name, r) ->
        {
            try
            {
                w.write(":" + name + " a :PrimitiveType ;\n");
                Statement nameStmt = model.getProperty(r, nameProp);
                if (nameStmt != null)
                {
                    w.write("    :name \"" + nameStmt.getString() + "\" ;\n");
                }
                Statement pkgStmt = model.getProperty(r, packageProp);
                if (pkgStmt != null && pkgStmt.getObject().isResource())
                {
                    w.write("    :package :" + getLocalName(pkgStmt.getResource()) + " ;\n");
                }

                MutableList<String> gens = Lists.mutable.empty();
                StmtIterator genIter = model.listStatements(r, generalizationsProp, (RDFNode) null);
                while (genIter.hasNext())
                {
                    Statement genStmt = genIter.next();
                    if (genStmt.getObject().isResource())
                    {
                        Resource genRes = genStmt.getObject().asResource();
                        Statement generalStmt = model.getProperty(genRes, generalProp);
                        if (generalStmt != null && generalStmt.getObject().isResource())
                        {
                            Resource generalRes = generalStmt.getObject().asResource();
                            Statement rawStmt = model.getProperty(generalRes, rawTypeProp);
                            if (rawStmt != null && rawStmt.getObject().isResource())
                            {
                                gens.add(getLocalName(rawStmt.getResource()));
                            }
                        }
                    }
                }

                if (!gens.isEmpty())
                {
                    w.write("    :generalizations\n");
                    for (int i = 0; i < gens.size(); i++)
                    {
                        String sep = (i < gens.size() - 1) ? "," : " .";
                        w.write("        [ :general [ :rawType :" + gens.get(i) + " ] ]" + sep + "\n");
                    }
                }
                else
                {
                    w.write("    .\n");
                }
                w.write("\n");
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        });
    }

    private void writeClass(BufferedWriter w, Resource r) throws IOException
    {
        String name = getLocalName(r);
        w.write(":" + name + " a :Class ;\n");

        // Write stereotypes (only mainTaxonomy for protocol)
        MutableList<String> stereos = Lists.mutable.empty();
        StmtIterator stereoIter = model.listStatements(r, stereotypesProp, (RDFNode) null);
        while (stereoIter.hasNext())
        {
            Statement stereoStmt = stereoIter.next();
            if (stereoStmt.getObject().isResource())
            {
                Resource stereoRes = stereoStmt.getObject().asResource();
                if (stereoRes.equals(protocolInfoMainTaxonomy))
                {
                    stereos.add(getLocalName(stereoRes));
                }
            }
        }
        stereos.forEach(stereo ->
        {
            try
            {
                w.write("    :stereotypes :" + stereo + " ;\n");
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        });

        Statement nameStmt = model.getProperty(r, nameProp);
        if (nameStmt != null)
        {
            w.write("    :name \"" + nameStmt.getString() + "\" ;\n");
        }

        Statement pkgStmt = model.getProperty(r, packageProp);
        if (pkgStmt != null && pkgStmt.getObject().isResource())
        {
            w.write("    :package :" + getLocalName(pkgStmt.getResource()) + " ;\n");
        }

        MutableList<String> gens = Lists.mutable.empty();
        StmtIterator genIter = model.listStatements(r, generalizationsProp, (RDFNode) null);
        while (genIter.hasNext())
        {
            Statement genStmt = genIter.next();
            if (genStmt.getObject().isResource())
            {
                Resource genRes = genStmt.getObject().asResource();
                Statement generalStmt = model.getProperty(genRes, generalProp);
                if (generalStmt != null && generalStmt.getObject().isResource())
                {
                    Resource generalRes = generalStmt.getObject().asResource();
                    Statement rawStmt = model.getProperty(generalRes, rawTypeProp);
                    if (rawStmt != null && rawStmt.getObject().isResource())
                    {
                        gens.add(getLocalName(rawStmt.getResource()));
                    }
                }
            }
        }

        if (!gens.isEmpty())
        {
            w.write("    :generalizations\n");
            for (int i = 0; i < gens.size(); i++)
            {
                String sep = (i < gens.size() - 1) ? "," : " .";
                w.write("        [ :general [ :rawType :" + gens.get(i) + " ] ]" + sep + "\n");
            }
        }
        else
        {
            w.write("    .\n");
        }
    }

    private void writePropertiesForOwner(BufferedWriter w, Resource owner) throws IOException
    {
        MutableList<Resource> props = Lists.mutable.empty();
        ResIterator iter = model.listSubjectsWithProperty(rdfType, m3Property);
        while (iter.hasNext())
        {
            Resource propRes = iter.next();
            Statement ownerStmt = model.getProperty(propRes, ownerProp);
            if (ownerStmt != null && ownerStmt.getObject().isResource()
                    && ownerStmt.getResource().equals(owner))
            {
                props.add(propRes);
            }
        }

        props.sortThis((a, b) -> getLocalName(a).compareTo(getLocalName(b)));

        for (Resource propRes : props)
        {
            writeProperty(w, propRes);
        }
    }

    private void writeProperty(BufferedWriter w, Resource r) throws IOException
    {
        String name = getLocalName(r);
        w.write("\n:" + name + " a :Property ;\n");

        Statement nameStmt = model.getProperty(r, nameProp);
        if (nameStmt != null)
        {
            w.write("    :name \"" + nameStmt.getString() + "\" ;\n");
        }

        Statement ownerStmt = model.getProperty(r, ownerProp);
        if (ownerStmt != null && ownerStmt.getObject().isResource())
        {
            w.write("    :owner :" + getLocalName(ownerStmt.getResource()) + " ;\n");
        }

        Statement genTypeStmt = model.getProperty(r, genericTypeProp);
        if (genTypeStmt != null && genTypeStmt.getObject().isResource())
        {
            Resource genTypeRes = genTypeStmt.getObject().asResource();
            Statement rawStmt = model.getProperty(genTypeRes, rawTypeProp);
            if (rawStmt != null && rawStmt.getObject().isResource())
            {
                w.write("    :genericType [ :rawType :" + getLocalName(rawStmt.getResource()) + " ] ;\n");
            }
        }

        Statement multStmt = model.getProperty(r, multiplicityProp);
        if (multStmt != null && multStmt.getObject().isResource())
        {
            w.write("    :multiplicity :" + getLocalName(multStmt.getResource()) + " .\n");
        }
        else
        {
            w.write("    .\n");
        }
    }

    private void writeEnumerations(BufferedWriter w) throws IOException
    {
        writeSectionHeader(w, "ENUMERATIONS");

        MutableSortedMap<String, Resource> enums = SortedMaps.mutable.empty();
        ResIterator iter = model.listSubjectsWithProperty(rdfType, m3Enumeration);
        while (iter.hasNext())
        {
            Resource r = iter.next();
            String name = getLocalName(r);
            if (name != null)
            {
                enums.put(name, r);
            }
        }

        enums.forEachKeyValue((name, r) ->
        {
            try
            {
                writeEnumeration(w, r);
                writeEnumValues(w, r);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        });
    }

    private void writeEnumeration(BufferedWriter w, Resource r) throws IOException
    {
        String name = getLocalName(r);
        w.write(":" + name + " a :Enumeration ;\n");

        Statement nameStmt = model.getProperty(r, nameProp);
        if (nameStmt != null)
        {
            w.write("    :name \"" + nameStmt.getString() + "\" ;\n");
        }

        Statement pkgStmt = model.getProperty(r, packageProp);
        if (pkgStmt != null && pkgStmt.getObject().isResource())
        {
            w.write("    :package :" + getLocalName(pkgStmt.getResource()) + " .\n");
        }
        else
        {
            w.write("    .\n");
        }
    }

    private void writeEnumValues(BufferedWriter w, Resource enumRes) throws IOException
    {
        String enumName = getLocalName(enumRes);
        ResIterator valIter = model.listSubjectsWithProperty(rdfType, enumRes);
        while (valIter.hasNext())
        {
            Resource valRes = valIter.next();
            w.write("\n:" + getLocalName(valRes) + " a :" + enumName + " ;\n");
            Statement nameStmt = model.getProperty(valRes, nameProp);
            if (nameStmt != null)
            {
                w.write("    :name \"" + nameStmt.getString() + "\" .\n");
            }
            else
            {
                w.write("    .\n");
            }
        }
    }

    private void writeMultiplicities(BufferedWriter w) throws IOException
    {
        writeSectionHeader(w, "MULTIPLICITIES");

        ResIterator iter = model.listSubjectsWithProperty(rdfType, m3Multiplicity);
        while (iter.hasNext())
        {
            Resource r = iter.next();
            String name = getLocalName(r);
            w.write(":" + name + " a :Multiplicity ;\n");

            Statement nameStmt = model.getProperty(r, nameProp);
            if (nameStmt != null)
            {
                w.write("    :name \"" + nameStmt.getString() + "\" .\n\n");
            }
            else
            {
                w.write("    .\n\n");
            }
        }
    }

    private void writePackages(BufferedWriter w) throws IOException
    {
        writeSectionHeader(w, "PACKAGES");

        // Collect all distinct package resources referenced via :package
        MutableSortedMap<String, Resource> packages = SortedMaps.mutable.empty();
        StmtIterator iter = model.listStatements(null, packageProp, (RDFNode) null);
        while (iter.hasNext())
        {
            Statement stmt = iter.next();
            if (stmt.getObject().isResource())
            {
                Resource pkgRes = stmt.getObject().asResource();
                String localName = getLocalName(pkgRes);
                if (localName != null)
                {
                    packages.put(localName, pkgRes);
                }
            }
        }

        packages.forEachKeyValue((localName, pkgRes) ->
        {
            try
            {
                w.write(":" + localName + " a :Package ;\n");

                // Derive simple name from localName (last segment after last _)
                String simpleName = localName;
                int lastUnderscore = localName.lastIndexOf('_');
                if (lastUnderscore >= 0)
                {
                    simpleName = localName.substring(lastUnderscore + 1);
                }
                w.write("    :name \"" + simpleName + "\" ;");

                // Derive parent package from localName (everything before last _)
                if (lastUnderscore > 0)
                {
                    String parentLocalName = localName.substring(0, lastUnderscore);
                    w.write("\n    :package :" + parentLocalName + " .\n\n");
                }
                else
                {
                    w.write("\n    .\n\n");
                }
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        });
    }

    private void writeProfilesAndStereotypes(BufferedWriter w) throws IOException
    {
        writeSectionHeader(w, "PROFILES AND STEREOTYPES");

        ResIterator profileIter = model.listSubjectsWithProperty(rdfType, m3Profile);
        while (profileIter.hasNext())
        {
            writeProfile(w, profileIter.next());
        }

        ResIterator stereotypeIter = model.listSubjectsWithProperty(rdfType, m3Stereotype);
        while (stereotypeIter.hasNext())
        {
            writeStereotype(w, stereotypeIter.next());
        }
    }

    private void writeProfile(BufferedWriter w, Resource r) throws IOException
    {
        String name = getLocalName(r);
        w.write(":" + name + " a :Profile ;\n");

        Statement nameStmt = model.getProperty(r, nameProp);
        if (nameStmt != null)
        {
            w.write("    :name \"" + nameStmt.getString() + "\" ;\n");
        }

        Statement pkgStmt = model.getProperty(r, packageProp);
        if (pkgStmt != null && pkgStmt.getObject().isResource())
        {
            w.write("    :package :" + getLocalName(pkgStmt.getResource()) + " .\n\n");
        }
        else
        {
            w.write("    .\n\n");
        }
    }

    private void writeStereotype(BufferedWriter w, Resource r) throws IOException
    {
        String name = getLocalName(r);
        w.write(":" + name + " a :Stereotype ;\n");

        Statement nameStmt = model.getProperty(r, nameProp);
        if (nameStmt != null)
        {
            w.write("    :name \"" + nameStmt.getString() + "\" ;\n");
        }

        Property profileProp = model.createProperty(M3_NS, "profile");
        Statement profileStmt = model.getProperty(r, profileProp);
        if (profileStmt != null && profileStmt.getObject().isResource())
        {
            w.write("    :profile :" + getLocalName(profileStmt.getResource()) + " .\n\n");
        }
        else
        {
            w.write("    .\n\n");
        }
    }

    private void writeSectionHeader(BufferedWriter w, String title) throws IOException
    {
        String line = "======================================";
        w.write("# " + line + line + "=\n");
        w.write("# " + title + "\n");
        w.write("# " + line + line + "=\n\n");
    }

    private String getLocalName(Resource res)
    {
        if (res == null)
        {
            return null;
        }
        String uri = res.getURI();
        if (uri == null)
        {
            return null;
        }
        int idx = uri.lastIndexOf('#');
        return idx >= 0 ? uri.substring(idx + 1) : uri;
    }

    private void identifyExcludedTypes()
    {
        ResIterator iter = model.listSubjectsWithProperty(rdfType, m3Class);
        while (iter.hasNext())
        {
            Resource r = iter.next();
            StmtIterator stmts = model.listStatements(r, stereotypesProp, (RDFNode) null);
            while (stmts.hasNext())
            {
                Statement stmt = stmts.next();
                RDFNode node = stmt.getObject();
                if (node.isResource())
                {
                    Resource stereotype = node.asResource();
                    if (stereotype.equals(protocolInfoExcluded)
                            || stereotype.equals(protocolInfoInferred))
                    {
                        excludedTypes.add(r);
                        break;
                    }
                }
            }
        }
    }

    private int removeExcludedClasses()
    {
        int count = 0;
        for (Resource r : excludedTypes)
        {
            MutableList<Statement> toRemove = Lists.mutable.empty();
            StmtIterator iter = model.listStatements(r, null, (RDFNode) null);
            while (iter.hasNext())
            {
                toRemove.add(iter.next());
            }
            model.remove(toRemove);
            count++;
        }
        return count;
    }

    private int removeExcludedProperties()
    {
        int count = 0;
        MutableList<Resource> propsToRemove = Lists.mutable.empty();

        ResIterator iter = model.listSubjectsWithProperty(rdfType, m3Property);
        while (iter.hasNext())
        {
            Resource r = iter.next();
            StmtIterator stmts = model.listStatements(r, stereotypesProp, (RDFNode) null);
            while (stmts.hasNext())
            {
                Statement stmt = stmts.next();
                RDFNode node = stmt.getObject();
                if (node.isResource())
                {
                    Resource stereotype = node.asResource();
                    if (stereotype.equals(protocolInfoExcluded)
                            || stereotype.equals(protocolInfoInferred))
                    {
                        propsToRemove.add(r);
                        break;
                    }
                }
            }
        }

        for (Resource r : propsToRemove)
        {
            MutableList<Statement> toRemove = Lists.mutable.empty();
            StmtIterator stmtIter = model.listStatements(r, null, (RDFNode) null);
            while (stmtIter.hasNext())
            {
                toRemove.add(stmtIter.next());
            }
            model.remove(toRemove);
            count++;
        }

        return count;
    }

    private int removeGeneralizationsToExcludedTypes()
    {
        int count = 0;
        MutableList<Statement> toRemove = Lists.mutable.empty();

        StmtIterator iter = model.listStatements(null, generalizationsProp, (RDFNode) null);
        while (iter.hasNext())
        {
            Statement genStmt = iter.next();
            RDFNode genNode = genStmt.getObject();

            if (genNode.isResource())
            {
                Resource genRes = genNode.asResource();
                Statement generalStmt = model.getProperty(genRes, generalProp);
                if (generalStmt != null && generalStmt.getObject().isResource())
                {
                    Resource generalRes = generalStmt.getObject().asResource();
                    Statement rawStmt = model.getProperty(generalRes, rawTypeProp);
                    if (rawStmt != null && rawStmt.getObject().isResource())
                    {
                        Resource rawType = rawStmt.getObject().asResource();
                        if (excludedTypes.contains(rawType))
                        {
                            toRemove.add(genStmt);
                            removeBlankNodeStatements(genRes, toRemove);
                            count++;
                        }
                    }
                }
            }
        }

        model.remove(toRemove);
        return count;
    }

    private int removePropertiesReferencingExcludedTypes()
    {
        int count = 0;
        MutableList<Resource> propsToRemove = Lists.mutable.empty();

        ResIterator iter = model.listSubjectsWithProperty(rdfType, m3Property);
        while (iter.hasNext())
        {
            Resource r = iter.next();
            Statement genTypeStmt = model.getProperty(r, genericTypeProp);
            if (genTypeStmt != null && genTypeStmt.getObject().isResource())
            {
                Resource genTypeRes = genTypeStmt.getObject().asResource();
                Statement rawStmt = model.getProperty(genTypeRes, rawTypeProp);
                if (rawStmt != null && rawStmt.getObject().isResource())
                {
                    Resource rawType = rawStmt.getObject().asResource();
                    if (excludedTypes.contains(rawType))
                    {
                        propsToRemove.add(r);
                    }
                }
            }
        }

        for (Resource r : propsToRemove)
        {
            MutableList<Statement> toRemove = Lists.mutable.empty();
            StmtIterator stmtIter = model.listStatements(r, null, (RDFNode) null);
            while (stmtIter.hasNext())
            {
                toRemove.add(stmtIter.next());
            }
            model.remove(toRemove);
            count++;
        }

        return count;
    }

    private int transformPackagePaths()
    {
        MutableList<Statement> toRemove = Lists.mutable.empty();
        MutableList<Statement> toAdd = Lists.mutable.empty();

        StmtIterator iter = model.listStatements(null, packageProp, (RDFNode) null);
        while (iter.hasNext())
        {
            Statement pkgStmt = iter.next();
            RDFNode pkgNode = pkgStmt.getObject();

            if (pkgNode.isResource())
            {
                Resource oldPkg = pkgNode.asResource();
                String oldUri = oldPkg.getURI();

                if (oldUri != null
                        && oldUri.contains("meta_pure_metamodel")
                        && !oldUri.contains("meta_pure_protocol_grammar"))
                {
                    String newUri = oldUri.replace(
                        "meta_pure_metamodel", "meta_pure_protocol_grammar");
                    Resource newPkg = model.createResource(newUri);

                    toRemove.add(pkgStmt);
                    toAdd.add(model.createStatement(
                        pkgStmt.getSubject(), packageProp, newPkg));
                }
            }
        }

        model.remove(toRemove);
        model.add(toAdd);

        return toRemove.size();
    }

    private int processPointerProperties()
    {
        int count = 0;
        MutableList<Resource> pointerProps = Lists.mutable.empty();

        // Find all properties with pointer stereotype
        ResIterator iter = model.listSubjectsWithProperty(rdfType, m3Property);
        while (iter.hasNext())
        {
            Resource r = iter.next();
            StmtIterator stmts = model.listStatements(r, stereotypesProp, (RDFNode) null);
            while (stmts.hasNext())
            {
                Statement stmt = stmts.next();
                RDFNode node = stmt.getObject();
                if (node.isResource() && node.asResource().equals(protocolInfoPointer))
                {
                    pointerProps.add(r);
                    break;
                }
            }
        }

        // Group pointer properties by original type name
        MutableMap<String, MutableList<Resource>> propsByType = Maps.mutable.empty();
        MutableMap<String, Resource> originalTypeByName = Maps.mutable.empty();

        pointerProps.forEach(propRes ->
        {
            Statement genTypeStmt = model.getProperty(propRes, genericTypeProp);
            if (genTypeStmt == null || !genTypeStmt.getObject().isResource())
            {
                return;
            }

            Resource genTypeRes = genTypeStmt.getObject().asResource();
            Statement rawStmt = model.getProperty(genTypeRes, rawTypeProp);
            if (rawStmt == null || !rawStmt.getObject().isResource())
            {
                return;
            }

            Resource originalType = rawStmt.getObject().asResource();
            String originalTypeName = getLocalName(originalType);

            propsByType.getIfAbsentPut(originalTypeName, Lists.mutable::empty).add(propRes);
            originalTypeByName.putIfAbsent(originalTypeName, originalType);
        });

        // Process each type exactly once, reading nonPointerSubtypes
        // from the type's <<requiresPointer>> tagged values
        for (String originalTypeName : propsByType.keysView())
        {
            MutableList<Resource> props = propsByType.get(originalTypeName);
            Resource originalType = originalTypeByName.get(originalTypeName);

            // Read nonPointerSubtypes from the TYPE itself
            MutableSet<String> nonPointerSubtypes = getNonPointerSubtypes(originalType);

            if (!nonPointerSubtypes.isEmpty())
            {
                // Create _Protocol + _Pointer once using the first property
                Resource firstProp = props.get(0);
                Statement firstGenTypeStmt = model.getProperty(firstProp, genericTypeProp);
                Resource firstGenTypeRes = firstGenTypeStmt.getObject().asResource();

                count += processPolymorphicPointer(
                    firstProp, firstGenTypeRes, originalType,
                    originalTypeName, nonPointerSubtypes);

                // For remaining properties, just swap rawType to _Protocol
                String protocolTypeName = originalTypeName + "_Protocol";
                Resource protocolType = model.createResource(M3_NS + protocolTypeName);
                for (int i = 1; i < props.size(); i++)
                {
                    Resource propRes = props.get(i);
                    Statement genTypeStmt = model.getProperty(propRes, genericTypeProp);
                    Resource genTypeRes = genTypeStmt.getObject().asResource();
                    Statement oldRawStmt = model.getProperty(genTypeRes, rawTypeProp);
                    if (oldRawStmt != null)
                    {
                        model.remove(oldRawStmt);
                    }
                    model.add(genTypeRes, rawTypeProp, protocolType);
                    model.remove(propRes, stereotypesProp, protocolInfoPointer);
                    removeTaggedValues(propRes);
                }
            }
            else
            {
                // Simple pointer: create _Pointer once using the first property
                Resource firstProp = props.get(0);
                Statement firstGenTypeStmt = model.getProperty(firstProp, genericTypeProp);
                Resource firstGenTypeRes = firstGenTypeStmt.getObject().asResource();

                count += processSimplePointer(
                    firstProp, firstGenTypeRes, originalType,
                    originalTypeName);

                // For remaining properties, just swap rawType to _Pointer
                String ptrTypeName = originalTypeName + "_Pointer";
                Resource ptrType = model.createResource(M3_NS + ptrTypeName);
                for (int i = 1; i < props.size(); i++)
                {
                    Resource propRes = props.get(i);
                    Statement genTypeStmt = model.getProperty(propRes, genericTypeProp);
                    Resource genTypeRes = genTypeStmt.getObject().asResource();
                    Statement oldRawStmt = model.getProperty(genTypeRes, rawTypeProp);
                    if (oldRawStmt != null)
                    {
                        model.remove(oldRawStmt);
                    }
                    model.add(genTypeRes, rawTypeProp, ptrType);
                    model.remove(propRes, stereotypesProp, protocolInfoPointer);
                }
            }
        }

        return count;
    }

    /**
     * Read the nonPointerSubtypes tagged value from a type.
     */
    private MutableSet<String> getNonPointerSubtypes(Resource typeRes)
    {
        MutableSet<String> result = Sets.mutable.empty();
        StmtIterator tvIter = model.listStatements(typeRes, taggedValuesProp, (RDFNode) null);
        while (tvIter.hasNext())
        {
            Statement tvStmt = tvIter.next();
            if (tvStmt.getObject().isResource())
            {
                Resource tvRes = tvStmt.getObject().asResource();
                Statement tagStmt = model.getProperty(tvRes, tagProp);
                if (tagStmt != null
                        && tagStmt.getObject().isResource()
                        && tagStmt.getObject().asResource().equals(protocolInfoNonPointerSubtypes))
                {
                    Statement valStmt = model.getProperty(tvRes, valueProp);
                    if (valStmt != null)
                    {
                        String val = valStmt.getString();
                        for (String name : val.split(","))
                        {
                            result.add(name.trim());
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * Process a pointer property that has nonPointerSubtypes.
     * Creates a _Protocol intermediate class as the union type.
     */
    private int processPolymorphicPointer(
            Resource propRes,
            Resource genTypeRes,
            Resource originalType,
            String originalTypeName,
            MutableSet<String> nonPointerSubtypes)
    {
        // Create _Protocol class (e.g., Type_Protocol)
        String protocolTypeName = originalTypeName + "_Protocol";
        Resource protocolType = model.createResource(M3_NS + protocolTypeName);
        model.add(protocolType, rdfType, m3Class);
        model.add(protocolType, nameProp, protocolTypeName);
        model.add(protocolType, stereotypesProp, protocolInfoMainTaxonomy);

        // Set package from original type
        Statement origPkgStmt = model.getProperty(originalType, packageProp);
        if (origPkgStmt != null && origPkgStmt.getObject().isResource())
        {
            model.add(protocolType, packageProp, origPkgStmt.getObject().asResource());
        }

        // Create Type_Pointer as subtype of Type_Protocol
        String pointerTypeName = originalTypeName + "_Pointer";
        Resource pointerType = model.createResource(M3_NS + pointerTypeName);
        model.add(pointerType, rdfType, m3Class);
        model.add(pointerType, nameProp, pointerTypeName);
        if (origPkgStmt != null && origPkgStmt.getObject().isResource())
        {
            model.add(pointerType, packageProp, origPkgStmt.getObject().asResource());
        }

        // Add generalization: Type_Pointer -> Type_Protocol
        addGeneralization(pointerType, protocolType);

        // Create "pointerValue" property on Type_Pointer
        String valuePropId = pointerTypeName + "_pointerValue";
        Resource valuePropRes = model.createResource(M3_NS + valuePropId);
        model.add(valuePropRes, rdfType, m3Property);
        model.add(valuePropRes, nameProp, "pointerValue");
        model.add(valuePropRes, ownerProp, pointerType);
        model.add(valuePropRes, multiplicityProp, m3PureOne);

        Resource valueGenType = model.createResource();
        model.add(valueGenType, rawTypeProp, m3String);
        model.add(valuePropRes, genericTypeProp, valueGenType);

        // Create "extraPointerValues" property on Pointer
        addExtraPointerValuesProperty(pointerType, pointerTypeName);

        // Make each non-pointer subtype also generalize Type_Protocol
        nonPointerSubtypes.forEach(subtypeName ->
        {
            Resource subtypeRes = model.createResource(M3_NS + subtypeName);
            addGeneralization(subtypeRes, protocolType);
        });

        // Update rawType to point to Type_Protocol
        Statement oldRawStmt = model.getProperty(genTypeRes, rawTypeProp);
        if (oldRawStmt != null)
        {
            model.remove(oldRawStmt);
        }
        model.add(genTypeRes, rawTypeProp, protocolType);

        // Remove pointer stereotype and tagged values
        model.remove(propRes, stereotypesProp, protocolInfoPointer);
        removeTaggedValues(propRes);

        return 1;
    }

    /**
     * Process a simple pointer property (no nonPointerSubtypes).
     * Replaces the original type entirely with a Pointer class.
     */
    private int processSimplePointer(
            Resource propRes,
            Resource genTypeRes,
            Resource originalType,
            String originalTypeName)
    {
        // Create new pointer type name
        String pointerTypeName = originalTypeName + "_Pointer";
        Resource pointerType = model.createResource(M3_NS + pointerTypeName);

        // Add pointer type as a Class
        model.add(pointerType, rdfType, m3Class);
        model.add(pointerType, nameProp, pointerTypeName);

        // Get the package of the original type
        Statement origPkgStmt = model.getProperty(originalType, packageProp);
        if (origPkgStmt != null && origPkgStmt.getObject().isResource())
        {
            model.add(pointerType, packageProp, origPkgStmt.getObject().asResource());
        }

        // Create "pointerValue" property with type String
        String valuePropId = pointerTypeName + "_pointerValue";
        Resource valuePropRes = model.createResource(M3_NS + valuePropId);
        model.add(valuePropRes, rdfType, m3Property);
        model.add(valuePropRes, nameProp, "pointerValue");
        model.add(valuePropRes, ownerProp, pointerType);
        model.add(valuePropRes, multiplicityProp, m3PureOne);

        // Add genericType pointing to String
        Resource valueGenType = model.createResource();
        model.add(valueGenType, rawTypeProp, m3String);
        model.add(valuePropRes, genericTypeProp, valueGenType);

        // Create "extraPointerValues" property on Pointer
        addExtraPointerValuesProperty(pointerType, pointerTypeName);

        // Replace rawType with pointer type
        Statement oldRawStmt = model.getProperty(genTypeRes, rawTypeProp);
        if (oldRawStmt != null)
        {
            model.remove(oldRawStmt);
        }
        model.add(genTypeRes, rawTypeProp, pointerType);

        // Remove the pointer stereotype
        model.remove(propRes, stereotypesProp, protocolInfoPointer);

        return 1;
    }

    /**
     * Add the extraPointerValues property to a pointer type.
     * Creates the PointerValue class if it doesn't already exist.
     */
    private void addExtraPointerValuesProperty(Resource pointerType, String pointerTypeName)
    {
        ensurePointerValueClass();

        String extraPropId = pointerTypeName + "_extraPointerValues";
        Resource extraPropRes = model.createResource(M3_NS + extraPropId);
        model.add(extraPropRes, rdfType, m3Property);
        model.add(extraPropRes, nameProp, "extraPointerValues");
        model.add(extraPropRes, ownerProp, pointerType);
        model.add(extraPropRes, multiplicityProp, m3ZeroMany);

        Resource extraGenType = model.createResource();
        model.add(extraGenType, rawTypeProp, pointerValueClass);
        model.add(extraPropRes, genericTypeProp, extraGenType);
    }

    /**
     * Ensure the PointerValue class exists in the model.
     * Creates it on first call with value (String) and sourceInformation (SourceInformation) properties.
     */
    private void ensurePointerValueClass()
    {
        if (pointerValueClass != null)
        {
            return;
        }

        String className = "PointerValue";
        pointerValueClass = model.createResource(M3_NS + className);
        model.add(pointerValueClass, rdfType, m3Class);
        model.add(pointerValueClass, nameProp, className);

        // Package: meta_pure_protocol_grammar (same as other protocol classes)
        Resource protocolPkg = model.createResource(M3_NS + "meta_pure_protocol_grammar");
        model.add(pointerValueClass, packageProp, protocolPkg);

        // Generalization: PointerValue -> Any
        addGeneralization(pointerValueClass, m3Any);

        // Property: value (String, PureOne)
        String valuePropId = className + "_value";
        Resource pvValueProp = model.createResource(M3_NS + valuePropId);
        model.add(pvValueProp, rdfType, m3Property);
        model.add(pvValueProp, nameProp, "value");
        model.add(pvValueProp, ownerProp, pointerValueClass);
        model.add(pvValueProp, multiplicityProp, m3PureOne);
        Resource pvValueGenType = model.createResource();
        model.add(pvValueGenType, rawTypeProp, m3String);
        model.add(pvValueProp, genericTypeProp, pvValueGenType);

        // sourceInformation is inherited from Any, no need to add it here
    }

    /**
     * Add a generalization from subtype to supertype in the model.
     */
    private void addGeneralization(Resource subtype, Resource supertype)
    {
        Resource generalGenType = model.createResource();
        model.add(generalGenType, rawTypeProp, supertype);

        Resource generalization = model.createResource();
        model.add(generalization, generalProp, generalGenType);

        model.add(subtype, generalizationsProp, generalization);
    }

    /**
     * Remove all tagged values from a property.
     */
    private void removeTaggedValues(Resource propRes)
    {
        MutableList<Statement> toRemove = Lists.mutable.empty();
        StmtIterator tvIter = model.listStatements(propRes, taggedValuesProp, (RDFNode) null);
        while (tvIter.hasNext())
        {
            Statement tvStmt = tvIter.next();
            toRemove.add(tvStmt);
            if (tvStmt.getObject().isResource())
            {
                removeBlankNodeStatements(tvStmt.getObject().asResource(), toRemove);
            }
        }
        model.remove(toRemove);
    }

    private void removeBlankNodeStatements(Resource res, MutableList<Statement> toRemove)
    {
        if (res.isAnon())
        {
            StmtIterator iter = model.listStatements(res, null, (RDFNode) null);
            while (iter.hasNext())
            {
                Statement stmt = iter.next();
                toRemove.add(stmt);
                if (stmt.getObject().isResource()
                        && stmt.getObject().asResource().isAnon())
                {
                    removeBlankNodeStatements(stmt.getObject().asResource(), toRemove);
                }
            }
        }
    }

    /**
     * Main entry point for standalone execution.
     *
     * @param args command line arguments: inputFile outputFile
     * @throws IOException if files cannot be read or written
     */
    public static void main(String[] args) throws IOException
    {
        if (args.length < 2)
        {
            System.err.println("Usage: M3ProtocolGenerator <in.ttl> <out.ttl> [additional.ttl ...]");
            System.exit(1);
        }

        List<String> additional = args.length > 2 ? List.of(args).subList(2, args.length) : List.of();
        M3ProtocolGenerator generator = new M3ProtocolGenerator(args[0]);
        generator.generate(Paths.get(args[1]), additional);
    }
}
