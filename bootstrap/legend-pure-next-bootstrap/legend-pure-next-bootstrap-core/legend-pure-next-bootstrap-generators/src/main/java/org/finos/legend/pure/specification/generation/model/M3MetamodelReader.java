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
    private final boolean fullyQualifyTypes;
    private final boolean validate;

    private final Resource m3Class;
    private final Resource m3Enumeration;
    private final Resource m3Property;
    private final Resource m3ProfileType;
    private final Resource m3StereotypeType;
    private final Resource m3FunctionType;
    private final Resource m3PrimitiveType;

    public M3MetamodelReader(String ttlPath)
    {
        this(RDFDataMgr.loadModel(ttlPath), false, true);
    }

    public M3MetamodelReader(String ttlPath, boolean fullyQualifyTypes)
    {
        this(RDFDataMgr.loadModel(ttlPath), fullyQualifyTypes, true);
    }

    /**
     * Read a model that's a derived/post-transform artifact (e.g. m3_protocol.ttl).
     * Skips validation — annotations like {@code @pointer}/{@code @maybePointer}
     * have been consumed by the generator that produced this model and are no
     * longer present, so validating here would produce false positives.
     */
    public static M3MetamodelReader forDerivedModel(String ttlPath, boolean fullyQualifyTypes)
    {
        return new M3MetamodelReader(RDFDataMgr.loadModel(ttlPath), fullyQualifyTypes, false);
    }

    public M3MetamodelReader(Model model)
    {
        this(model, false, true);
    }

    public M3MetamodelReader(Model model, boolean fullyQualifyTypes)
    {
        this(model, fullyQualifyTypes, true);
    }

    private M3MetamodelReader(Model model, boolean fullyQualifyTypes, boolean validate)
    {
        this.model = model;
        this.fullyQualifyTypes = fullyQualifyTypes;
        this.validate = validate;
        this.m3Class = model.createResource(M3_NS + "Class");
        this.m3Enumeration = model.createResource(M3_NS + "Enumeration");
        this.m3Property = model.createResource(M3_NS + "Property");
        this.m3ProfileType = model.createResource(M3_NS + "Profile");
        this.m3StereotypeType = model.createResource(M3_NS + "Stereotype");
        this.m3FunctionType = model.createResource(M3_NS + "FunctionType");
        this.m3PrimitiveType = model.createResource(M3_NS + "PrimitiveType");
    }

    /**
     * Read the RDF model and return a fully populated {@link M3Model}.
     */
    public M3Model read()
    {
        M3Model m3Model = new M3Model();
        collectProfileInfo(m3Model);

        // Inject omitted profiles from protocol grammar (e.g. typemodifiers)
        m3Model.stereotypeDisplayNames().put(M3_NS + "meta_pure_profiles_typemodifiers_abstract", "typemodifiers.abstract");

        collectClassInfo(m3Model);
        collectEnumInfo(m3Model);
        collectPrimitiveInfo(m3Model);
        collectPropertyInfo(m3Model);
        m3Model.computeClassesWithSubtypes();
        if (validate)
        {
            M3ModelValidator.validate(m3Model);
        }
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
                Statement profStmt = getM3StatementMulti(stRes, "profile", "tag_profile");
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
                Statement profStmt = getM3StatementMulti(tagRes, "profile", "tag_profile");
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
    // Primitive Collection
    // =========================================================================

    private void collectPrimitiveInfo(M3Model m3Model)
    {
        for (ResIterator it = model.listSubjectsWithProperty(RDF.type, m3PrimitiveType); it.hasNext();)
        {
            Resource res = it.next();
            String name = getName(res);
            if (name != null)
            {
                m3Model.primitiveTypes().add(name);
            }
        }
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
                Statement valueStmt = getM3Statement(tvRes, "taggedValue_value");
                if (tagStmt != null && tagStmt.getObject().isResource()
                        && valueStmt != null)
                {
                    String tagUri = tagStmt.getObject().asResource().getURI();
                    String display = m3Model.tagDisplayNames().get(tagUri);
                    String value = getLiteralString(valueStmt);
                    if (display != null && value != null)
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
        Statement rawTypeStmt = getM3Statement(node.asResource(), "type");
        if (rawTypeStmt != null && rawTypeStmt.getObject().isResource())
        {
            Resource typeRes = rawTypeStmt.getObject().asResource();
            // Raw type names always short, to match classInfoMap keys. The
            // FQN-with-type-args form is captured separately on fullTypeName /
            // fullGeneralizations for the Pure renderer.
            return getLocalName(typeRes);
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
     * Extract a full generic type string (e.g., "Map<String, List<T>>" or "GenericTypeAndMultiplicityHolder<T|m>").
     * Recursively resolves type arguments and multiplicity arguments.
     */
    private String extractGenericTypeString(RDFNode node)
    {
        if (!node.isResource())
        {
            return null;
        }
        Resource gtRes = node.asResource();
        Statement rawTypeStmt = getM3Statement(gtRes, "type");
        if (rawTypeStmt == null || !rawTypeStmt.getObject().isResource())
        {
            return null;
        }
        Resource typeRes = rawTypeStmt.getObject().asResource();
        String rawName = fullyQualifyTypes ? getFqn(typeRes) : getName(typeRes);
        if (rawName == null)
        {
            return null;
        }

        MutableList<String> typeArgs = extractTypeArguments(gtRes);
        MutableList<String> mulArgs = extractMultiplicityArguments(gtRes);
        if (!typeArgs.isEmpty() || !mulArgs.isEmpty())
        {
            StringBuilder sb = new StringBuilder(rawName).append("<");
            sb.append(typeArgs.makeString(", "));
            if (!mulArgs.isEmpty())
            {
                sb.append("|").append(mulArgs.makeString(", "));
            }
            sb.append(">");
            return sb.toString();
        }
        return rawName;
    }

    /**
     * Extract multiplicity arguments from a GenericType resource.
     * These are referenced as :multiplicityArguments [ :multiplicityParameter "m" ].
     */
    private MutableList<String> extractMultiplicityArguments(Resource gtRes)
    {
        MutableList<String> result = Lists.mutable.empty();
        listM3Statements(gtRes, "multiplicityArguments").forEach(maStmt ->
        {
            if (maStmt.getObject().isResource())
            {
                Resource maRes = maStmt.getObject().asResource();
                // Check for multiplicityParameter reference: [ :classifierGenericType :GenericType_String ; :data "m" ]
                Statement mpStmt = getM3Statement(maRes, "MultiplicityParameter_name");
                if (mpStmt != null)
                {
                    String name = getLiteralString(mpStmt);
                    if (name != null)
                    {
                        result.add(name);
                    }
                }
            }
        });
        return result;
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
        // FunctionType arguments need the {A[m]->B[n]} surface form, not the rawType name.
        Statement rawTypeStmt = getM3Statement(taRes, "type");
        if (rawTypeStmt != null && rawTypeStmt.getObject().isResource())
        {
            Resource rawTypeRes = rawTypeStmt.getObject().asResource();
            if (rawTypeRes.hasProperty(RDF.type, m3FunctionType))
            {
                result.add(formatFunctionTypeArgument(rawTypeRes));
                return;
            }
        }

        String typeName = extractGenericTypeString(taRes);
        if (typeName == null)
        {
            throw new IllegalStateException("typeArgument " + taRes + " did not resolve to a type name");
        }
        result.add(typeName);
    }

    private String formatFunctionTypeArgument(Resource ftRes)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // Nullary FunctionTypes (e.g., {->Any[1]}) have no parameters at all — that's valid.
        MutableList<Statement> paramStmts = listM3Statements(ftRes, "parameters");
        for (int i = 0; i < paramStmts.size(); i++)
        {
            Statement paramStmt = paramStmts.get(i);
            if (!paramStmt.getObject().isResource())
            {
                throw new IllegalStateException("FunctionType " + ftRes + " parameter[" + i + "] is not a resource");
            }
            Resource paramRes = paramStmt.getObject().asResource();
            Statement gtStmt = getM3StatementMulti(paramRes, "genericType", "ValueSpecification_genericType");
            if (gtStmt == null || !gtStmt.getObject().isResource())
            {
                throw new IllegalStateException("FunctionType " + ftRes + " parameter " + paramRes + " has no genericType");
            }
            Resource gt = gtStmt.getObject().asResource();
            String paramTypeName = extractGenericTypeString(gt);
            if (paramTypeName == null)
            {
                throw new IllegalStateException("FunctionType " + ftRes + " parameter genericType " + gt + " did not resolve to a type name");
            }
            if (i > 0)
            {
                sb.append(", ");
            }
            sb.append(paramTypeName);
            Statement multStmt = getM3StatementMulti(paramRes, "multiplicity", "ValueSpecification_multiplicity");
            if (multStmt == null || !multStmt.getObject().isResource())
            {
                throw new IllegalStateException("FunctionType " + ftRes + " parameter " + paramRes + " has no multiplicity");
            }
            String paramMultName = getLocalName(multStmt.getObject().asResource());
            sb.append(mapMultiplicity(paramMultName));
        }

        sb.append("->");

        Statement retStmt = getM3Statement(ftRes, "returnType");
        if (retStmt == null || !retStmt.getObject().isResource())
        {
            throw new IllegalStateException("FunctionType " + ftRes + " has no returnType");
        }
        Resource retRes = retStmt.getObject().asResource();
        String retTypeName = extractGenericTypeString(retRes);
        if (retTypeName == null)
        {
            throw new IllegalStateException("FunctionType " + ftRes + " returnType " + retRes + " did not resolve to a type name");
        }
        sb.append(retTypeName);

        Statement retMultStmt = getM3Statement(ftRes, "returnMultiplicity");
        if (retMultStmt == null || !retMultStmt.getObject().isResource())
        {
            throw new IllegalStateException("FunctionType " + ftRes + " has no returnMultiplicity");
        }
        Resource retMultRes = retMultStmt.getObject().asResource();
        Statement mpStmt = getM3Statement(retMultRes, "MultiplicityParameter_name");
        if (mpStmt != null)
        {
            String mpName = getLiteralString(mpStmt);
            if (mpName == null)
            {
                throw new IllegalStateException("FunctionType " + ftRes + " returnMultiplicity " + retMultRes + " has empty MultiplicityParameter_name");
            }
            sb.append("[").append(mpName).append("]");
        }
        else
        {
            String retMultName = getLocalName(retMultRes);
            sb.append(mapMultiplicity(retMultName));
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
        if (genTypeStmt == null || !genTypeStmt.getObject().isResource())
        {
            throw new IllegalStateException("Property " + propRes + " has no genericType");
        }
        String name = extractRawTypeNameFromGenericType(genTypeStmt.getObject());
        if (name == null)
        {
            throw new IllegalStateException("Property " + propRes + " genericType did not resolve to a raw type name");
        }
        return name;
    }

    /**
     * Extract full generic type string for a property (e.g., "Map<String, List<T>>").
     */
    private String extractFullPropertyType(Resource propRes)
    {
        Statement genTypeStmt = getM3Statement(propRes, "genericType");
        if (genTypeStmt == null || !genTypeStmt.getObject().isResource())
        {
            throw new IllegalStateException("Property " + propRes + " has no genericType");
        }
        String full = extractGenericTypeString(genTypeStmt.getObject());
        if (full == null)
        {
            throw new IllegalStateException("Property " + propRes + " genericType did not resolve to a full type name");
        }
        return full;
    }

    private String extractMultiplicity(Resource propRes)
    {
        Statement multStmt = getM3Statement(propRes, "multiplicity");
        if (multStmt == null || !multStmt.getObject().isResource())
        {
            throw new IllegalStateException("Property " + propRes + " has no multiplicity");
        }
        return getLocalName(multStmt.getObject().asResource());
    }

    // =========================================================================
    // Type Parameter Extraction
    // =========================================================================

    private MutableList<String> extractTypeParameters(Resource classRes)
    {
        MutableList<String> result = Lists.mutable.empty();
        listM3StatementsMulti(classRes, "typeParameters", "TypeAndMultiplicityParametersOwner_typeParameters").forEach(tpStmt ->
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
            throw new IllegalStateException("TypeParameter " + tpRes + " has no name");
        }
        Statement contraStmt = getM3Statement(tpRes, "contravariant");
        if (contraStmt != null)
        {
            String contraVal = getLiteralString(contraStmt);
            if ("true".equals(contraVal))
            {
                return "-" + tpName;
            }
        }
        return tpName;
    }

    private MutableList<String> extractMultiplicityParameters(Resource classRes)
    {
        MutableList<String> result = Lists.mutable.empty();
        listM3StatementsMulti(classRes, "multiplicityParameters", "TypeAndMultiplicityParametersOwner_multiplicityParameters").forEach(mpStmt ->
        {
            String val = getLiteralString(mpStmt);
            if (val != null)
            {
                // Direct literal or typed primitive node form
                result.add(val);
                return;
            }
            if (!mpStmt.getObject().isResource())
            {
                throw new IllegalStateException("multiplicityParameter on " + classRes + " is neither a literal nor a resource");
            }
            // Blank node form: :multiplicityParameters [ :name "m" ]
            Resource mpRes = mpStmt.getObject().asResource();
            String name = getName(mpRes);
            if (name == null)
            {
                throw new IllegalStateException("multiplicityParameter " + mpRes + " on " + classRes + " has no name");
            }
            result.add(name);
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

    private Statement getM3StatementMulti(Resource res, String... localNames)
    {
        for (String localName : localNames)
        {
            Statement stmt = getM3Statement(res, localName);
            if (stmt != null)
            {
                return stmt;
            }
        }
        return null;
    }

    private MutableList<Statement> listM3StatementsMulti(Resource res, String... localNames)
    {
        MutableList<Statement> results = Lists.mutable.empty();
        for (String localName : localNames)
        {
            results.addAll(listM3Statements(res, localName));
        }
        return results;
    }

    /**
     * Get the "name" of a resource by trying all known name-like predicates.
     * Different types use different property resource URIs for their name:
     * PackageableElement uses :name, Property uses :abstractProperty_name,
     * Enum values use :enumValue, TypeParameter uses :typeParameter_name, etc.
     */
    private String getFqn(Resource res)
    {
        String name = getName(res);
        if (name == null)
        {
            name = getLocalName(res);
            if (name == null)
            {
                return null;
            }
        }
        String pkg = getPackagePath(res);
        if (pkg != null && !pkg.isEmpty())
        {
            if (name.startsWith("meta::"))
            {
                return name;
            }
            return pkg + "::" + name;
        }
        return name;
    }

    private String getName(Resource res)
    {
        for (String predicate : NAME_PREDICATES)
        {
            Statement stmt = getM3Statement(res, predicate);
            if (stmt != null)
            {
                String val = getLiteralString(stmt);
                if (val != null)
                {
                    return val;
                }
            }
        }
        return null;
    }

    /**
     * Extract a string value from a statement, handling both raw RDF literals
     * and typed primitive nodes (blank nodes with :classifierGenericType and :data).
     * This is the unified method for reading primitive values from the TTL.
     */
    private String getLiteralString(Statement stmt)
    {
        if (stmt.getObject().isLiteral())
        {
            return stmt.getString();
        }
        else if (stmt.getObject().isResource())
        {
            // Typed primitive node: [ :classifierGenericType :GenericType_X ; :data "value" ]
            Statement dataStmt = getM3Statement(stmt.getObject().asResource(), "data");
            if (dataStmt != null && dataStmt.getObject().isLiteral())
            {
                return dataStmt.getString();
            }
        }
        return null;
    }

    private static final String[] NAME_PREDICATES = {
            "name",                          // PackageableElement, Class, Enumeration, Profile, etc.
            "abstractProperty_name",         // AbstractProperty (Property instances)
            "enumValue",                     // Enum values (AggregationKind_None, etc.)
            "typeParameter_name",            // TypeParameter
            "MultiplicityParameter_name",    // MultiplicityParameter
            "ResolvedTypeParameter_name",    // ResolvedTypeParameter
            "ResolvedMultiplicityParameter_name", // ResolvedMultiplicityParameter
            "VariableExpression_name",       // VariableExpression
            "constraint_name",              // Constraint
            "Column_name",                  // Column values
            "stereotype_name",              // Stereotype
            "tag_value",                    // Tag
            "PackageableFunction_functionName", // PackageableFunction
    };

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
