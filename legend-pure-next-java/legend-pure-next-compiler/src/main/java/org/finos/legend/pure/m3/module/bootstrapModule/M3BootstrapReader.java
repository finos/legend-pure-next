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

package org.finos.legend.pure.m3.module.bootstrapModule;

import meta.pure.metamodel.Package;
import meta.pure.metamodel.PackageImpl;
import meta.pure.metamodel.SimplePropertyOwner;
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.extension.ProfileImpl;
import meta.pure.metamodel.extension.Stereotype;
import meta.pure.metamodel.extension.StereotypeImpl;
import meta.pure.metamodel.multiplicity.ConcreteMultiplicityImpl;
import meta.pure.metamodel.multiplicity.ConcretePackageableMultiplicityImpl;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.multiplicity.MultiplicityValueImpl;
import meta.pure.metamodel.multiplicity.PackageableInferredMultiplicityImpl;
import meta.pure.metamodel.multiplicity.PackageableMultiplicity;
import meta.pure.metamodel.relationship.Generalization;
import meta.pure.metamodel.relationship.GeneralizationImpl;
import meta.pure.metamodel.type.ClassImpl;
import meta.pure.metamodel.type.EnumerationImpl;
import meta.pure.metamodel.function.property.PropertyImpl;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.FunctionTypeImpl;
import meta.pure.metamodel.type.PrimitiveTypeImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.ConcreteGenericTypeImpl;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.MultiplicityParameter;
import meta.pure.metamodel.type.generics.MultiplicityParameterImpl;
import meta.pure.metamodel.type.generics.TypeParameter;
import meta.pure.metamodel.type.generics.TypeParameterImpl;
import meta.pure.metamodel.valuespecification.VariableExpressionImpl;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;


/**
 * Reads {@code m3.ttl} from the classpath and bootstraps all M3 types
 * (classes, primitive types, enumerations, and profiles) into the
 * package tree and element index.
 *
 * <p>Uses Apache Jena for proper TTL/RDF parsing.</p>
 */
public class M3BootstrapReader
{
    private static final String M3_TTL_PATH = "specification/m3.ttl";
    private static final String M3_NS = "https://finos.org/legend/pure/m3#";

    private M3BootstrapReader()
    {
    }

    /**
     * Read m3.ttl from the classpath and bootstrap all M3 types into the
     * given root package and index.
     */
    public static void bootstrap(Package root, MutableMap<String, PackageableElement> index)
    {
        // Root must be in the index so that it is serialized to PDB.
        // Without this, PDB-loaded packages cannot resolve their parent
        // chain back through Root, breaking path computation.
        index.put("::", root);
        try (InputStream is = M3BootstrapReader.class.getClassLoader()
                .getResourceAsStream(M3_TTL_PATH))
        {
            if (is == null)
            {
                throw new RuntimeException("Cannot find " + M3_TTL_PATH + " on classpath");
            }
            Model model = ModelFactory.createDefaultModel();
            RDFDataMgr.read(model, is, Lang.TURTLE);

            // Build helper resources
            Resource m3Class = model.createResource(M3_NS + "Class");
            Resource m3PrimitiveType = model.createResource(M3_NS + "PrimitiveType");
            Resource m3Enumeration = model.createResource(M3_NS + "Enumeration");
            Resource m3Profile = model.createResource(M3_NS + "Profile");
            Resource m3ConcretePackageableMultiplicity = model.createResource(M3_NS + "ConcretePackageableMultiplicity");
            Resource m3PackageableInferredMultiplicity = model.createResource(M3_NS + "PackageableInferredMultiplicity");

            // First pass: create all elements
            bootstrapType(model, m3Class, root, index, "Class");
            bootstrapType(model, m3PrimitiveType, root, index, "PrimitiveType");
            bootstrapType(model, m3Enumeration, root, index, "Enumeration");
            bootstrapType(model, m3Profile, root, index, "Profile");
            bootstrapMultiplicities(model, m3ConcretePackageableMultiplicity, root, index, ConcretePackageableMultiplicityImpl::new);
            bootstrapMultiplicities(model, m3PackageableInferredMultiplicity, root, index, PackageableInferredMultiplicityImpl::new);

            // Second pass: wire generalizations now that all types exist
            wireGeneralizations(model, m3Class, index);
            wireGeneralizations(model, m3PrimitiveType, index);
            wireGeneralizations(model, m3Enumeration, index);

            // Wire classifierGenericType on types (e.g., Any → Class<Any>)
            wireClassifierGenericType(model, m3Class, index);
            wireClassifierGenericType(model, m3PrimitiveType, index);
            wireClassifierGenericType(model, m3Enumeration, index);

            // Third pass: wire properties to their owner types
            Resource m3Property = model.createResource(M3_NS + "Property");
            bootstrapProperties(model, m3Property, index);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to read " + M3_TTL_PATH, e);
        }
    }

    private static void bootstrapType(
            Model model,
            Resource typeResource,
            Package root,
            MutableMap<String, PackageableElement> index,
            String kind)
    {
        for (ResIterator it = model.listSubjectsWithProperty(RDF.type, typeResource); it.hasNext();)
        {
            Resource res = it.next();
            String name = getName(model, res);
            String packagePath = getPackagePath(model, res);
            if (name == null || packagePath == null)
            {
                continue;
            }

            Package pkg = getOrCreatePackage(root, packagePath, index);
            PackageableElement element = switch (kind)
            {
                case "Class" ->
                {
                    ClassImpl clazz = new ClassImpl()._name(name)._package(pkg);
                    // Set type parameters
                    MutableList<TypeParameter> typeParams = getTypeParameters(model, res);
                    if (typeParams.notEmpty())
                    {
                        clazz._typeParameters(typeParams);
                    }
                    // Set multiplicity parameters
                    MutableList<MultiplicityParameter> mulParams = getMultiplicityParameters(model, res);
                    if (mulParams.notEmpty())
                    {
                        clazz._multiplicityParameters(mulParams);
                    }
                    yield clazz;
                }
                case "PrimitiveType" -> new PrimitiveTypeImpl()._name(name)._package(pkg);
                case "Enumeration" -> new EnumerationImpl()._name(name)._package(pkg);
                case "Profile" -> new ProfileImpl()._name(name)._package(pkg);
                default -> throw new IllegalArgumentException("Unknown kind: " + kind);
            };

            pkg._children().add(element);
            index.put(packagePath + "::" + name, element);
        }
    }

    private static void bootstrapMultiplicities(
            Model model,
            Resource typeResource,
            Package root,
            MutableMap<String, PackageableElement> index,
            Supplier<? extends PackageableMultiplicity> factory)
    {
        for (ResIterator it = model.listSubjectsWithProperty(RDF.type, typeResource); it.hasNext();)
        {
            Resource res = it.next();
            String name = getName(model, res);
            String packagePath = getPackagePath(model, res);
            if (name == null || packagePath == null)
            {
                continue;
            }

            Package pkg = getOrCreatePackage(root, packagePath, index);
            PackageableMultiplicity multiplicity = factory.get();
            multiplicity._name(name);
            multiplicity._package(pkg);

            // Lower bound
            Statement lowerStmt = getM3Statement(model, res, "lowerBound");
            if (lowerStmt != null && lowerStmt.getObject().isResource())
            {
                Statement valueStmt = getM3Statement(model, lowerStmt.getObject().asResource(), "value");
                if (valueStmt != null && valueStmt.getObject().isLiteral())
                {
                    multiplicity._lowerBound(
                            new MultiplicityValueImpl()._value(valueStmt.getLong()));
                }
            }

            // Upper bound
            Statement upperStmt = getM3Statement(model, res, "upperBound");
            if (upperStmt != null && upperStmt.getObject().isResource())
            {
                Statement valueStmt = getM3Statement(model, upperStmt.getObject().asResource(), "value");
                if (valueStmt != null && valueStmt.getObject().isLiteral())
                {
                    multiplicity._upperBound(
                            new MultiplicityValueImpl()._value(valueStmt.getLong()));
                }
            }

            pkg._children().add(multiplicity);
            index.put(packagePath + "::" + name, multiplicity);
        }
    }

    /**
     * Wire generalizations for all instances of the given type.
     * This must be done after all types are bootstrapped so targets can be resolved.
     */
    private static void wireGeneralizations(
            Model model,
            Resource typeResource,
            MutableMap<String, PackageableElement> index)
    {
        for (ResIterator it = model.listSubjectsWithProperty(RDF.type, typeResource); it.hasNext();)
        {
            Resource res = it.next();
            String name = getName(model, res);
            String packagePath = getPackagePath(model, res);
            if (name == null || packagePath == null)
            {
                continue;
            }

            String fullPath = packagePath + "::" + name;
            PackageableElement element = index.get(fullPath);
            if (!(element instanceof Type specificType))
            {
                continue;
            }

            // Extract generalizations
            MutableList<Generalization> generalizations = Lists.mutable.empty();

            for (Statement genStmt : listM3Statements(model, res, "generalizations"))
            {
                if (!genStmt.getObject().isResource())
                {
                    continue;
                }
                Resource genRes = genStmt.getObject().asResource();

                // Get the :general GenericType
                Statement generalStmt = getM3Statement(model, genRes, "general");
                if (generalStmt == null || !generalStmt.getObject().isResource())
                {
                    continue;
                }
                Resource generalGTRes = generalStmt.getObject().asResource();

                // Get :rawType from the GenericType
                Statement rawTypeStmt = getM3Statement(model, generalGTRes, "rawType");
                if (rawTypeStmt == null || !rawTypeStmt.getObject().isResource())
                {
                    continue;
                }

                String generalTypeName = getLocalName(rawTypeStmt.getObject().asResource());
                if (generalTypeName == null)
                {
                    continue;
                }
                Type generalType = findType(generalTypeName, index);
                if (generalType == null)
                {
                    continue;
                }

                // Build the GenericType for the generalization
                ConcreteGenericTypeImpl generalGT = new ConcreteGenericTypeImpl()._rawType(generalType);

                // Extract type arguments
                MutableList<GenericType> typeArgs = Lists.mutable.empty();
                for (Statement typeArgStmt : listM3Statements(model, generalGTRes, "typeArguments"))
                {
                    if (!typeArgStmt.getObject().isResource())
                    {
                        continue;
                    }
                    Resource typeArgRes = typeArgStmt.getObject().asResource();

                    // Check if this type argument is a type parameter reference
                    Statement typeParamStmt = getM3Statement(model, typeArgRes, "typeParameter");
                    if (typeParamStmt != null && typeParamStmt.getObject().isResource())
                    {
                        String paramName = getName(model, typeParamStmt.getObject().asResource());
                        if (paramName != null)
                        {
                            typeArgs.add(new ConcreteGenericTypeImpl()
                                    ._typeParameter(new TypeParameterImpl()._name(paramName)));
                        }
                    }
                    else
                    {
                        // Concrete type argument (has :rawType)
                        Statement argRawTypeStmt = getM3Statement(model, typeArgRes, "rawType");
                        if (argRawTypeStmt != null && argRawTypeStmt.getObject().isResource())
                        {
                            Resource argRawTypeRes = argRawTypeStmt.getObject().asResource();

                            // Check if the rawType is an anonymous FunctionType
                            Statement rdfTypeStmt = argRawTypeRes.getProperty(RDF.type);
                            if (rdfTypeStmt != null && rdfTypeStmt.getObject().isResource()
                                    && "FunctionType".equals(getLocalName(rdfTypeStmt.getObject().asResource())))
                            {
                                typeArgs.add(new ConcreteGenericTypeImpl()
                                        ._rawType(buildFunctionType(model, argRawTypeRes, index)));
                            }
                            else
                            {
                                String argTypeName = getLocalName(argRawTypeRes);
                                if (argTypeName != null)
                                {
                                    Type argType = findType(argTypeName, index);
                                    if (argType != null)
                                    {
                                        typeArgs.add(new ConcreteGenericTypeImpl()._rawType(argType));
                                    }
                                }
                            }
                        }
                    }
                }

                if (typeArgs.notEmpty())
                {
                    generalGT._typeArguments(typeArgs);
                }

                GeneralizationImpl generalization = new GeneralizationImpl()
                        ._general(generalGT)
                        ._specific(specificType);
                generalizations.add(generalization);
            }

            if (generalizations.notEmpty())
            {
                specificType._generalizations(generalizations);
            }
        }
    }

    /**
     * Wire classifierGenericType on all instances of the given type.
     * This must be done after all types are bootstrapped so targets can be resolved.
     */
    private static void wireClassifierGenericType(
            Model model,
            Resource typeResource,
            MutableMap<String, PackageableElement> index)
    {
        for (ResIterator it = model.listSubjectsWithProperty(RDF.type, typeResource); it.hasNext();)
        {
            Resource res = it.next();
            String name = getName(model, res);
            String packagePath = getPackagePath(model, res);
            if (name == null || packagePath == null)
            {
                continue;
            }

            String fullPath = packagePath + "::" + name;
            PackageableElement element = index.get(fullPath);
            if (!(element instanceof Type specificType))
            {
                continue;
            }

            Statement cgtStmt = getM3Statement(model, res, "classifierGenericType");
            if (cgtStmt != null && cgtStmt.getObject().isResource())
            {
                specificType._classifierGenericType(buildGenericType(model, cgtStmt.getObject().asResource(), index));
            }
        }
    }

    /**
     * Wire properties to their owner types.
     * This must be done after all types and generalizations are bootstrapped.
     */
    private static void bootstrapProperties(
            Model model,
            Resource propertyResource,
            MutableMap<String, PackageableElement> index)
    {
        for (ResIterator it = model.listSubjectsWithProperty(RDF.type, propertyResource); it.hasNext();)
        {
            Resource res = it.next();
            String name = getName(model, res);
            if (name == null)
            {
                continue;
            }

            // Find the owner type
            Statement ownerStmt = getM3Statement(model, res, "owner");
            if (ownerStmt == null || !ownerStmt.getObject().isResource())
            {
                continue;
            }
            String ownerName = getLocalName(ownerStmt.getObject().asResource());
            if (ownerName == null)
            {
                continue;
            }
            SimplePropertyOwner owner = findPropertyOwner(ownerName, index);
            if (owner == null)
            {
                continue;
            }

            // Build the property
            PropertyImpl prop = new PropertyImpl()._name(name)._owner(owner);

            // Wire classifierGenericType
            Statement cgtStmt = getM3Statement(model, res, "classifierGenericType");
            if (cgtStmt != null && cgtStmt.getObject().isResource())
            {
                prop._classifierGenericType(buildGenericType(model, cgtStmt.getObject().asResource(), index));
            }

            // Wire genericType
            Statement gtStmt = getM3Statement(model, res, "genericType");
            if (gtStmt != null && gtStmt.getObject().isResource())
            {
                prop._genericType(buildGenericType(model, gtStmt.getObject().asResource(), index));
            }

            // Wire multiplicity
            Statement mulStmt = getM3Statement(model, res, "multiplicity");
            if (mulStmt != null && mulStmt.getObject().isResource())
            {
                Multiplicity mul = lookupMultiplicity(mulStmt.getObject().asResource(), index);
                if (mul != null)
                {
                    prop._multiplicity(mul);
                }
            }

            // Wire stereotypes (e.g., ProtocolInfo.inferred, ProtocolInfo.excluded)
            org.apache.jena.rdf.model.Property stereotypesPred = model.getProperty(M3_NS + "stereotypes");
            MutableList<Stereotype> stereotypes = Lists.mutable.empty();
            for (StmtIterator stIt = model.listStatements(res, stereotypesPred, (RDFNode) null); stIt.hasNext();)
            {
                Statement stStmt = stIt.next();
                if (stStmt.getObject().isResource())
                {
                    String stName = getName(model, stStmt.getObject().asResource());
                    if (stName != null)
                    {
                        stereotypes.add(new StereotypeImpl()._value(stName));
                    }
                }
            }
            if (stereotypes.notEmpty())
            {
                prop._stereotypes(stereotypes);
            }

            // Add to owner's properties list
            owner._properties().add(prop);
        }
    }

    private static SimplePropertyOwner findPropertyOwner(String name, MutableMap<String, PackageableElement> index)
    {
        for (PackageableElement element : index.valuesView())
        {
            if (element instanceof SimplePropertyOwner po && name.equals(element._name()))
            {
                return po;
            }
        }
        return null;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String getName(Model model, Resource res)
    {
        Statement stmt = getM3Statement(model, res, "name");
        return (stmt != null && stmt.getObject().isLiteral())
                ? stmt.getLiteral().getString()
                : null;
    }

    private static String getPackagePath(Model model, Resource res)
    {
        Statement stmt = getM3Statement(model, res, "package");
        if (stmt == null || !stmt.getObject().isResource())
        {
            return null;
        }
        String localName = getLocalName(stmt.getObject().asResource());
        if (localName == null)
        {
            return null;
        }
        // Convert underscore-separated ref to :: path
        // e.g. "meta_pure_metamodel_type" → "meta::pure::metamodel::type"
        return localName.replace("_", "::");
    }

    private static String getLocalName(Resource res)
    {
        String uri = res.getURI();
        if (uri == null)
        {
            return null;
        }
        int hashIndex = uri.lastIndexOf('#');
        return hashIndex >= 0 ? uri.substring(hashIndex + 1) : uri;
    }

    private static Statement getM3Statement(Model model, Resource res, String property)
    {
        return res.getProperty(model.createProperty(M3_NS + property));
    }

    private static MutableList<Statement> listM3Statements(Model model, Resource res, String property)
    {
        MutableList<Statement> result = Lists.mutable.empty();
        var iter = res.listProperties(model.createProperty(M3_NS + property));
        while (iter.hasNext())
        {
            result.add(iter.next());
        }
        return result;
    }

    /**
     * Extract RDF resources from a property that may be a single value or an RDF collection.
     * RDF collections use rdf:first/rdf:rest linked lists created by Turtle's ( ... ) syntax.
     */
    private static MutableList<Resource> extractPropertyResources(Model model, Resource res, String property)
    {
        MutableList<Resource> result = Lists.mutable.empty();
        for (Statement stmt : listM3Statements(model, res, property))
        {
            if (!stmt.getObject().isResource())
            {
                continue;
            }
            Resource obj = stmt.getObject().asResource();
            // Check if this is an RDF list (has rdf:first)
            if (obj.hasProperty(RDF.first))
            {
                // Iterate through the RDF list
                while (obj != null && !obj.equals(RDF.nil))
                {
                    Statement firstStmt = obj.getProperty(RDF.first);
                    if (firstStmt != null && firstStmt.getObject().isResource())
                    {
                        result.add(firstStmt.getObject().asResource());
                    }
                    Statement restStmt = obj.getProperty(RDF.rest);
                    obj = (restStmt != null && restStmt.getObject().isResource())
                            ? restStmt.getObject().asResource()
                            : null;
                }
            }
            else
            {
                result.add(obj);
            }
        }
        return result;
    }

    private static MutableList<TypeParameter> getTypeParameters(Model model, Resource res)
    {
        MutableList<TypeParameter> result = Lists.mutable.empty();
        for (Resource paramRes : extractPropertyResources(model, res, "typeParameters"))
        {
            String paramName = getName(model, paramRes);
            if (paramName != null)
            {
                result.add(new TypeParameterImpl()._name(paramName));
            }
        }
        return result;
    }

    private static MutableList<MultiplicityParameter> getMultiplicityParameters(Model model, Resource res)
    {
        MutableList<MultiplicityParameter> result = Lists.mutable.empty();
        for (Resource paramRes : extractPropertyResources(model, res, "multiplicityParameters"))
        {
            String paramName = getName(model, paramRes);
            if (paramName != null)
            {
                result.add(new MultiplicityParameterImpl()._name(paramName));
            }
        }
        return result;
    }

    /**
     * Build a FunctionType from an anonymous RDF resource (e.g. inline within a generalization's type argument).
     */
    private static FunctionType buildFunctionType(
            Model model, Resource ftRes, MutableMap<String, PackageableElement> index)
    {
        FunctionTypeImpl ft = new FunctionTypeImpl();

        // Parse parameters
        for (Statement paramStmt : listM3Statements(model, ftRes, "parameters"))
        {
            if (!paramStmt.getObject().isResource())
            {
                continue;
            }
            Resource paramRes = paramStmt.getObject().asResource();
            VariableExpressionImpl ve = new VariableExpressionImpl();

            // Name
            String paramName = getName(model, paramRes);
            if (paramName != null)
            {
                ve._name(paramName);
            }

            // GenericType (may be a typeParameter reference)
            Statement gtStmt = getM3Statement(model, paramRes, "genericType");
            if (gtStmt != null && gtStmt.getObject().isResource())
            {
                ve._genericType(buildGenericType(model, gtStmt.getObject().asResource(), index));
            }

            // Multiplicity
            Statement mulStmt = getM3Statement(model, paramRes, "multiplicity");
            if (mulStmt != null && mulStmt.getObject().isResource())
            {
                ve._multiplicity(lookupMultiplicity(mulStmt.getObject().asResource(), index));
            }

            ft._parameters().add(ve);
        }

        // Parse returnType (may be a typeParameter reference)
        Statement returnTypeStmt = getM3Statement(model, ftRes, "returnType");
        if (returnTypeStmt != null && returnTypeStmt.getObject().isResource())
        {
            ft._returnType(buildGenericType(model, returnTypeStmt.getObject().asResource(), index));
        }

        // Parse returnMultiplicity (may be a multiplicityParameter reference)
        Statement returnMulStmt = getM3Statement(model, ftRes, "returnMultiplicity");
        if (returnMulStmt != null && returnMulStmt.getObject().isResource())
        {
            Resource mulRes = returnMulStmt.getObject().asResource();
            Statement mulParamStmt = getM3Statement(model, mulRes, "multiplicityParameter");
            if (mulParamStmt != null && mulParamStmt.getObject().isLiteral())
            {
                // Multiplicity parameter reference: [ :multiplicityParameter "m" ]
                ft._returnMultiplicity(new ConcreteMultiplicityImpl()
                        ._multiplicityParameter(mulParamStmt.getString()));
            }
            else
            {
                ft._returnMultiplicity(lookupMultiplicity(mulRes, index));
            }
        }

        return ft;
    }

    /**
     * Build a GenericType from an RDF resource. Handles typeParameter references,
     * concrete rawType references, typeArguments, and multiplicityArguments.
     */
    private static GenericType buildGenericType(
            Model model, Resource gtRes, MutableMap<String, PackageableElement> index)
    {
        // Check for typeParameter reference
        Statement typeParamStmt = getM3Statement(model, gtRes, "typeParameter");
        if (typeParamStmt != null && typeParamStmt.getObject().isResource())
        {
            String paramName = getName(model, typeParamStmt.getObject().asResource());
            if (paramName != null)
            {
                ConcreteGenericTypeImpl gt = new ConcreteGenericTypeImpl()
                        ._typeParameter(new TypeParameterImpl()._name(paramName));
                return gt;
            }
        }

        ConcreteGenericTypeImpl gt = new ConcreteGenericTypeImpl();

        // Concrete rawType reference
        Statement rawTypeStmt = getM3Statement(model, gtRes, "rawType");
        if (rawTypeStmt != null && rawTypeStmt.getObject().isResource())
        {
            Resource rawTypeRes = rawTypeStmt.getObject().asResource();

            // Check if the rawType is an anonymous FunctionType
            Statement rdfTypeStmt = rawTypeRes.getProperty(RDF.type);
            if (rdfTypeStmt != null && rdfTypeStmt.getObject().isResource()
                    && "FunctionType".equals(getLocalName(rdfTypeStmt.getObject().asResource())))
            {
                gt._rawType(buildFunctionType(model, rawTypeRes, index));
            }
            else
            {
                String typeName = getLocalName(rawTypeRes);
                if (typeName != null)
                {
                    Type type = findType(typeName, index);
                    if (type != null)
                    {
                        gt._rawType(type);
                    }
                }
            }
        }

        // Type arguments
        MutableList<GenericType> typeArgs = Lists.mutable.empty();
        for (Resource typeArgRes : extractPropertyResources(model, gtRes, "typeArguments"))
        {
            typeArgs.add(buildGenericType(model, typeArgRes, index));
        }
        if (typeArgs.notEmpty())
        {
            gt._typeArguments(typeArgs);
        }

        // Multiplicity arguments
        MutableList<Multiplicity> mulArgs = Lists.mutable.empty();
        for (Resource mulArgRes : extractPropertyResources(model, gtRes, "multiplicityArguments"))
        {
            Multiplicity mul = lookupMultiplicity(mulArgRes, index);
            if (mul != null)
            {
                mulArgs.add(mul);
            }
        }
        if (mulArgs.notEmpty())
        {
            gt._multiplicityArguments(mulArgs);
        }

        return gt;
    }

    /**
     * Look up a named multiplicity from the index.
     */
    private static Multiplicity lookupMultiplicity(Resource mulRes, MutableMap<String, PackageableElement> index)
    {
        String mulName = getLocalName(mulRes);
        if (mulName != null)
        {
            for (PackageableElement element : index.valuesView())
            {
                if (element instanceof Multiplicity mul && mulName.equals(element._name()))
                {
                    return mul;
                }
            }
        }
        return null;
    }

    private static Type findType(String typeName, MutableMap<String, PackageableElement> index)
    {
        for (PackageableElement element : index.valuesView())
        {
            if (element instanceof Type type && typeName.equals(element._name()))
            {
                return type;
            }
        }
        return null;
    }

    private static Package getOrCreatePackage(
            Package root,
            String packagePath,
            MutableMap<String, PackageableElement> index)
    {
        Package current = root;
        StringBuilder currentPath = new StringBuilder();
        for (String part : packagePath.split("::"))
        {
            if (currentPath.length() > 0)
            {
                currentPath.append("::");
            }
            currentPath.append(part);

            String pathStr = currentPath.toString();
            PackageableElement existing = index.get(pathStr);
            if (existing instanceof Package existingPkg)
            {
                current = existingPkg;
            }
            else
            {
                PackageImpl newPkg = new PackageImpl()._name(part)._package(current);
                current._children().add(newPkg);
                index.put(pathStr, newPkg);
                current = newPkg;
            }
        }
        return current;
    }
}
