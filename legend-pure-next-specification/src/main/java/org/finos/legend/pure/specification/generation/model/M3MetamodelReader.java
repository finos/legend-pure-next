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

package org.finos.legend.pure.specification.generation.model;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;

/**
 * Reads an RDF Turtle file containing the M3 metamodel and populates an {@link M3Model}.
 *
 * <p>This reader consolidates all RDF parsing so that generators only depend on
 * the intermediate model and never need direct RDF access.</p>
 *
 * <p>All M3 property lookups use {@link #getM3Statement} / {@link #listM3Statements}
 * to avoid Jena's predicate-shadowing issue where M3 predicates like {@code :name}
 * are also defined as named RDF resources.</p>
 */
public class M3MetamodelReader
{
    private static final String M3_NS = "https://finos.org/legend/pure/m3#";

    private final Model model;
    private final Resource m3Class;
    private final Resource m3Enumeration;
    private final Resource m3Property;
    private final Resource m3ProfileType;
    private final Resource m3StereotypeType;
    private final Resource m3FunctionType;

    public M3MetamodelReader(String ttlPath)
    {
        this(RDFDataMgr.loadModel(ttlPath));
    }

    public M3MetamodelReader(Model model)
    {
        this.model = model;
        this.m3Class = model.createResource(M3_NS + "Class");
        this.m3Enumeration = model.createResource(M3_NS + "Enumeration");
        this.m3Property = model.createResource(M3_NS + "Property");
        this.m3ProfileType = model.createResource(M3_NS + "Profile");
        this.m3StereotypeType = model.createResource(M3_NS + "Stereotype");
        this.m3FunctionType = model.createResource(M3_NS + "FunctionType");
    }

    /**
     * Read the RDF model and return a fully populated {@link M3Model}.
     */
    public M3Model read()
    {
        M3Model m3Model = new M3Model();
        collectProfileInfo(m3Model);
        collectClassInfo(m3Model);
        collectEnumInfo(m3Model);
        collectPropertyInfo(m3Model);
        m3Model.computeClassesWithSubtypes();
        return m3Model;
    }

    // =========================================================================
    // Profile Collection
    // =========================================================================

    private void collectProfileInfo(M3Model m3Model)
    {
        for (ResIterator it = model.listSubjectsWithProperty(RDF.type, m3ProfileType); it.hasNext();)
        {
            Resource profileRes = it.next();
            String name = getName(profileRes);
            if (name == null)
            {
                continue;
            }

            ProfileInfo info = new ProfileInfo();
            info.name = name;
            info.packagePath = getPackagePath(profileRes);

            // Collect stereotypes belonging to this profile
            for (ResIterator stIt = model.listSubjectsWithProperty(RDF.type, m3StereotypeType); stIt.hasNext();)
            {
                Resource stRes = stIt.next();
                Statement profStmt = getM3Statement(stRes, "profile");
                if (profStmt != null
                        && profStmt.getObject().isResource()
                        && profStmt.getObject().asResource().equals(profileRes))
                {
                    String stName = getName(stRes);
                    if (stName != null)
                    {
                        info.stereotypes.add(stName);
                        m3Model.stereotypeDisplayNames().put(stRes.getURI(), name + "." + stName);
                    }
                }
            }

            // Collect tags belonging to this profile
            Resource tagType = model.createResource(M3_NS + "Tag");
            for (ResIterator tIt = model.listSubjectsWithProperty(RDF.type, tagType); tIt.hasNext();)
            {
                Resource tagRes = tIt.next();
                Statement profStmt = getM3Statement(tagRes, "profile");
                if (profStmt != null
                        && profStmt.getObject().isResource()
                        && profStmt.getObject().asResource().equals(profileRes))
                {
                    String tagName = getName(tagRes);
                    if (tagName != null)
                    {
                        info.tags.add(tagName);
                        m3Model.tagDisplayNames().put(tagRes.getURI(), name + "." + tagName);
                    }
                }
            }

            m3Model.profileInfoMap().put(info.name, info);
        }
    }

    // =========================================================================
    // Class Collection
    // =========================================================================

    private void collectClassInfo(M3Model m3Model)
    {
        for (ResIterator it = model.listSubjectsWithProperty(RDF.type, m3Class); it.hasNext();)
        {
            Resource classRes = it.next();
            String name = getName(classRes);
            if (name == null)
            {
                continue;
            }

            ClassInfo info = new ClassInfo();
            info.name = name;
            info.packagePath = getPackagePath(classRes);
            info.uri = classRes.getURI();
            info.generalizations = extractRawGeneralizations(classRes);
            info.fullGeneralizations = extractFullGeneralizations(classRes);
            info.stereotypes = extractStereotypeDisplayNames(classRes, m3Model);
            info.taggedValues = extractTaggedValueDisplayNames(classRes, m3Model);
            info.typeParameters = extractTypeParameters(classRes);
            info.multiplicityParameters = extractMultiplicityParameters(classRes);

            // Track mainTaxonomy for JSON type annotations
            if (info.stereotypes.anySatisfy(s -> s.endsWith(".mainTaxonomy")))
            {
                m3Model.mainTaxonomyClasses().add(name);
            }

            m3Model.classInfoMap().put(info.name, info);
        }
    }

    // =========================================================================
    // Enum Collection
    // =========================================================================

    private void collectEnumInfo(M3Model m3Model)
    {
        for (ResIterator it = model.listSubjectsWithProperty(RDF.type, m3Enumeration); it.hasNext();)
        {
            Resource enumRes = it.next();
            String name = getName(enumRes);
            if (name == null)
            {
                continue;
            }

            EnumInfo info = new EnumInfo();
            info.name = name;
            info.packagePath = getPackagePath(enumRes);
            info.uri = enumRes.getURI();
            info.values = collectEnumValues(enumRes);

            m3Model.enumInfoMap().put(info.name, info);
        }
    }

    private MutableList<String> collectEnumValues(Resource enumRes)
    {
        MutableList<String> values = Lists.mutable.empty();
        for (ResIterator it = model.listSubjectsWithProperty(RDF.type, enumRes); it.hasNext();)
        {
            Resource valueRes = it.next();
            String valueName = getName(valueRes);
            if (valueName != null)
            {
                values.add(valueName);
            }
        }
        return values;
    }

    // =========================================================================
    // Property Collection
    // =========================================================================

    private void collectPropertyInfo(M3Model m3Model)
    {
        for (ResIterator it = model.listSubjectsWithProperty(RDF.type, m3Property); it.hasNext();)
        {
            Resource propRes = it.next();
            String name = getName(propRes);
            if (name == null)
            {
                continue;
            }

            String ownerName = getOwnerName(propRes);
            if (ownerName == null)
            {
                continue;
            }

            PropertyInfo info = new PropertyInfo();
            info.name = name;
            info.ownerName = ownerName;
            info.typeName = extractRawPropertyType(propRes);
            info.fullTypeName = extractFullPropertyType(propRes);
            info.multiplicity = extractMultiplicity(propRes);
            info.isMany = info.multiplicity != null
                    && (info.multiplicity.equals("ZeroMany") || info.multiplicity.equals("OneMany"));
            info.stereotypes = extractStereotypeDisplayNames(propRes, m3Model);
            info.taggedValues = extractTaggedValueDisplayNames(propRes, m3Model);

            m3Model.propertiesByOwner().getIfAbsentPut(ownerName, Lists.mutable::empty).add(info);
        }
    }

    // =========================================================================
    // Stereotype and Tagged Value Extraction (display names)
    // =========================================================================

    /**
     * Extract stereotype display names in "ProfileName.StereotypeName" format.
     * Uses the profile resolution maps built during profile collection.
     */
    private MutableList<String> extractStereotypeDisplayNames(Resource res, M3Model m3Model)
    {
        MutableList<String> result = Lists.mutable.empty();
        listM3Statements(res, "stereotypes").forEach(stmt ->
        {
            RDFNode obj = stmt.getObject();
            if (obj.isResource())
            {
                String uri = obj.asResource().getURI();
                String display = m3Model.stereotypeDisplayNames().get(uri);
                if (display != null)
                {
                    result.add(display);
                }
            }
        });
        return result;
    }

    /**
     * Extract tagged values with display names in "ProfileName.TagName" format.
     */
    private MutableList<TaggedValueEntry> extractTaggedValueDisplayNames(Resource res, M3Model m3Model)
    {
        MutableList<TaggedValueEntry> result = Lists.mutable.empty();
        listM3Statements(res, "taggedValues").forEach(stmt ->
        {
            RDFNode obj = stmt.getObject();
            if (obj.isResource())
            {
                Resource tvRes = obj.asResource();
                Statement tagStmt = getM3Statement(tvRes, "tag");
                Statement valueStmt = getM3Statement(tvRes, "value");
                if (tagStmt != null && tagStmt.getObject().isResource()
                        && valueStmt != null)
                {
                    String tagUri = tagStmt.getObject().asResource().getURI();
                    String display = m3Model.tagDisplayNames().get(tagUri);
                    String value = valueStmt.getObject().isLiteral()
                            ? valueStmt.getLiteral().getString()
                            : valueStmt.getObject().toString();
                    if (display != null)
                    {
                        result.add(new TaggedValueEntry(display, value));
                    }
                }
            }
        });
        return result;
    }

    // =========================================================================
    // Generalization Extraction
    // =========================================================================

    /**
     * Extract raw type names for generalizations (e.g., "Class").
     * Used for subtype computation and Java generator.
     */
    private MutableList<String> extractRawGeneralizations(Resource classRes)
    {
        MutableList<String> result = Lists.mutable.empty();
        listM3Statements(classRes, "generalizations").forEach(genStmt ->
        {
            String typeName = extractRawTypeNameFromGeneralization(genStmt.getObject());
            if (typeName != null)
            {
                result.add(typeName);
            }
        });
        return result;
    }

    private String extractRawTypeNameFromGeneralization(RDFNode node)
    {
        if (!node.isResource())
        {
            return null;
        }
        Statement generalStmt = getM3Statement(node.asResource(), "general");
        if (generalStmt != null && generalStmt.getObject().isResource())
        {
            return extractRawTypeNameFromGenericType(generalStmt.getObject());
        }
        return null;
    }

    private String extractRawTypeNameFromGenericType(RDFNode node)
    {
        if (!node.isResource())
        {
            return null;
        }
        Statement rawTypeStmt = getM3Statement(node.asResource(), "rawType");
        if (rawTypeStmt != null && rawTypeStmt.getObject().isResource())
        {
            return getLocalName(rawTypeStmt.getObject().asResource());
        }
        return null;
    }

    /**
     * Extract full generic type strings for generalizations (e.g., "Class<T, U>").
     * Used for Pure rendering.
     */
    private MutableList<String> extractFullGeneralizations(Resource classRes)
    {
        MutableList<String> result = Lists.mutable.empty();
        listM3Statements(classRes, "generalizations").forEach(genStmt ->
        {
            String typeName = extractFullTypeNameFromGeneralization(genStmt.getObject());
            if (typeName != null)
            {
                result.add(typeName);
            }
        });
        return result;
    }

    private String extractFullTypeNameFromGeneralization(RDFNode node)
    {
        if (!node.isResource())
        {
            return null;
        }
        Statement generalStmt = getM3Statement(node.asResource(), "general");
        if (generalStmt != null && generalStmt.getObject().isResource())
        {
            return extractGenericTypeString(generalStmt.getObject());
        }
        return null;
    }

    // =========================================================================
    // Full Generic Type String Extraction
    // =========================================================================

    /**
     * Extract a full generic type string (e.g., "Map<String, List<T>>").
     * Recursively resolves type arguments.
     */
    private String extractGenericTypeString(RDFNode node)
    {
        if (!node.isResource())
        {
            return null;
        }
        Resource gtRes = node.asResource();
        Statement rawTypeStmt = getM3Statement(gtRes, "rawType");
        if (rawTypeStmt == null || !rawTypeStmt.getObject().isResource())
        {
            return null;
        }
        String rawName = getName(rawTypeStmt.getObject().asResource());
        if (rawName == null)
        {
            return null;
        }

        MutableList<String> typeArgs = extractTypeArguments(gtRes);
        if (!typeArgs.isEmpty())
        {
            return rawName + "<" + typeArgs.makeString(", ") + ">";
        }
        return rawName;
    }

    private MutableList<String> extractTypeArguments(Resource gtRes)
    {
        MutableList<String> result = Lists.mutable.empty();
        listM3Statements(gtRes, "typeArguments").forEach(taStmt ->
        {
            if (taStmt.getObject().isResource())
            {
                Resource taRes = taStmt.getObject().asResource();
                if (taRes.hasProperty(RDF.first))
                {
                    // Traverse RDF list
                    Resource listNode = taRes;
                    while (listNode != null && !listNode.equals(RDF.nil))
                    {
                        Statement firstStmt = listNode.getProperty(RDF.first);
                        if (firstStmt != null && firstStmt.getObject().isResource())
                        {
                            addTypeArgument(result, firstStmt.getObject().asResource());
                        }
                        Statement restStmt = listNode.getProperty(RDF.rest);
                        listNode = (restStmt != null && restStmt.getObject().isResource())
                                ? restStmt.getObject().asResource()
                                : null;
                    }
                }
                else
                {
                    addTypeArgument(result, taRes);
                }
            }
        });
        return result;
    }

    private void addTypeArgument(MutableList<String> result, Resource taRes)
    {
        // Check for FunctionType
        Statement rawTypeStmt = getM3Statement(taRes, "rawType");
        if (rawTypeStmt != null && rawTypeStmt.getObject().isResource())
        {
            Resource rawTypeRes = rawTypeStmt.getObject().asResource();
            if (rawTypeRes.hasProperty(RDF.type, m3FunctionType))
            {
                result.add(formatFunctionTypeArgument(rawTypeRes));
                return;
            }
        }

        // Check for typeParameter reference
        Statement tpStmt = getM3Statement(taRes, "typeParameter");
        if (tpStmt != null && tpStmt.getObject().isResource())
        {
            String tpName = getName(tpStmt.getObject().asResource());
            if (tpName != null)
            {
                result.add(tpName);
                return;
            }
        }

        // Otherwise look for rawType
        String typeName = extractGenericTypeString(taRes);
        if (typeName != null)
        {
            result.add(typeName);
        }
    }

    private String formatFunctionTypeArgument(Resource ftRes)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        Statement paramStmt = getM3Statement(ftRes, "parameters");
        if (paramStmt != null && paramStmt.getObject().isResource())
        {
            Resource paramRes = paramStmt.getObject().asResource();
            Statement gtStmt = getM3Statement(paramRes, "genericType");
            if (gtStmt != null && gtStmt.getObject().isResource())
            {
                Resource gt = gtStmt.getObject().asResource();
                Statement tp = getM3Statement(gt, "typeParameter");
                if (tp != null && tp.getObject().isResource())
                {
                    sb.append(getName(tp.getObject().asResource()));
                }
            }
            Statement multStmt = getM3Statement(paramRes, "multiplicity");
            if (multStmt != null && multStmt.getObject().isResource())
            {
                String multName = getLocalName(multStmt.getObject().asResource());
                sb.append(mapMultiplicity(multName));
            }
        }

        sb.append("->");

        Statement retStmt = getM3Statement(ftRes, "returnType");
        if (retStmt != null && retStmt.getObject().isResource())
        {
            Resource retRes = retStmt.getObject().asResource();
            Statement tp = getM3Statement(retRes, "typeParameter");
            if (tp != null && tp.getObject().isResource())
            {
                sb.append(getName(tp.getObject().asResource()));
            }
        }

        Statement retMultStmt = getM3Statement(ftRes, "returnMultiplicity");
        if (retMultStmt != null && retMultStmt.getObject().isResource())
        {
            Resource retMultRes = retMultStmt.getObject().asResource();
            Statement mpStmt = getM3Statement(retMultRes, "multiplicityParameter");
            if (mpStmt != null && mpStmt.getObject().isLiteral())
            {
                sb.append("[").append(mpStmt.getString()).append("]");
            }
            else
            {
                String multName = getLocalName(retMultRes);
                sb.append(mapMultiplicity(multName));
            }
        }

        sb.append("}");
        return sb.toString();
    }

    // =========================================================================
    // Property Type Extraction
    // =========================================================================

    /**
     * Extract raw type name for a property (e.g., "String").
     */
    private String extractRawPropertyType(Resource propRes)
    {
        Statement genTypeStmt = getM3Statement(propRes, "genericType");
        if (genTypeStmt != null && genTypeStmt.getObject().isResource())
        {
            return extractRawTypeNameFromGenericType(genTypeStmt.getObject());
        }
        return "Object";
    }

    /**
     * Extract full generic type string for a property (e.g., "Map<String, List<T>>").
     */
    private String extractFullPropertyType(Resource propRes)
    {
        Statement genTypeStmt = getM3Statement(propRes, "genericType");
        if (genTypeStmt != null && genTypeStmt.getObject().isResource())
        {
            String full = extractGenericTypeString(genTypeStmt.getObject());
            if (full != null)
            {
                return full;
            }
        }
        return "Any";
    }

    private String extractMultiplicity(Resource propRes)
    {
        Statement multStmt = getM3Statement(propRes, "multiplicity");
        if (multStmt != null && multStmt.getObject().isResource())
        {
            return getLocalName(multStmt.getObject().asResource());
        }
        return null;
    }

    // =========================================================================
    // Type Parameter Extraction
    // =========================================================================

    private MutableList<String> extractTypeParameters(Resource classRes)
    {
        MutableList<String> result = Lists.mutable.empty();
        listM3Statements(classRes, "typeParameters").forEach(tpStmt ->
        {
            if (tpStmt.getObject().isResource())
            {
                Resource tpRes = tpStmt.getObject().asResource();
                if (tpRes.hasProperty(RDF.first))
                {
                    Resource listNode = tpRes;
                    while (listNode != null && !listNode.equals(RDF.nil))
                    {
                        Statement firstStmt = listNode.getProperty(RDF.first);
                        if (firstStmt != null && firstStmt.getObject().isResource())
                        {
                            result.add(formatTypeParameter(firstStmt.getObject().asResource()));
                        }
                        Statement restStmt = listNode.getProperty(RDF.rest);
                        listNode = (restStmt != null && restStmt.getObject().isResource())
                                ? restStmt.getObject().asResource()
                                : null;
                    }
                }
                else
                {
                    result.add(formatTypeParameter(tpRes));
                }
            }
        });
        return result;
    }

    private String formatTypeParameter(Resource tpRes)
    {
        String tpName = getName(tpRes);
        if (tpName == null)
        {
            return "";
        }
        Statement contraStmt = getM3Statement(tpRes, "contravariant");
        if (contraStmt != null && contraStmt.getObject().isLiteral() && contraStmt.getBoolean())
        {
            return "-" + tpName;
        }
        return tpName;
    }

    private MutableList<String> extractMultiplicityParameters(Resource classRes)
    {
        MutableList<String> result = Lists.mutable.empty();
        listM3Statements(classRes, "multiplicityParameters").forEach(mpStmt ->
        {
            if (mpStmt.getObject().isLiteral())
            {
                result.add(mpStmt.getString());
            }
        });
        return result;
    }

    // =========================================================================
    // RDF Utility Methods
    // =========================================================================

    private Statement getM3Statement(Resource res, String localName)
    {
        StmtIterator it = res.listProperties();
        while (it.hasNext())
        {
            Statement stmt = it.next();
            if (localName.equals(stmt.getPredicate().getLocalName()))
            {
                return stmt;
            }
        }
        return null;
    }

    private MutableList<Statement> listM3Statements(Resource res, String localName)
    {
        MutableList<Statement> results = Lists.mutable.empty();
        StmtIterator it = res.listProperties();
        while (it.hasNext())
        {
            Statement stmt = it.next();
            if (localName.equals(stmt.getPredicate().getLocalName()))
            {
                results.add(stmt);
            }
        }
        return results;
    }

    private String getName(Resource res)
    {
        Statement stmt = getM3Statement(res, "name");
        return stmt != null ? stmt.getString() : null;
    }

    private String getPackagePath(Resource res)
    {
        Statement pkgStmt = getM3Statement(res, "package");
        if (pkgStmt != null && pkgStmt.getObject().isResource())
        {
            String pkgUri = getLocalName(pkgStmt.getObject().asResource());
            if (pkgUri != null)
            {
                return pkgUri.replace("_", "::");
            }
        }
        return null;
    }

    private String getOwnerName(Resource res)
    {
        Statement ownerStmt = getM3Statement(res, "owner");
        if (ownerStmt != null && ownerStmt.getObject().isResource())
        {
            return getName(ownerStmt.getObject().asResource());
        }
        return null;
    }

    private String getLocalName(Resource res)
    {
        String uri = res.getURI();
        if (uri != null && uri.contains("#"))
        {
            return uri.substring(uri.lastIndexOf('#') + 1);
        }
        if (uri != null && uri.contains("/"))
        {
            return uri.substring(uri.lastIndexOf('/') + 1);
        }
        return null;
    }

    private String mapMultiplicity(String multiplicity)
    {
        return switch (multiplicity)
        {
            case null -> "[1]";
            case "PureOne" -> "[1]";
            case "ZeroOne" -> "[0..1]";
            case "ZeroMany" -> "[*]";
            case "OneMany" -> "[1..*]";
            default -> "[" + multiplicity + "]";
        };
    }
}
