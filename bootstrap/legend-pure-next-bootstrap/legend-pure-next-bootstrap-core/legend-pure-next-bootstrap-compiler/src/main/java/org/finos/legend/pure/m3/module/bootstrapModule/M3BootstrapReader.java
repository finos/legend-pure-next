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
import meta.pure.metamodel.PackageableElement;
import meta.pure.metamodel.SimplePropertyOwner;
import meta.pure.metamodel.extension.ProfileImpl;
import meta.pure.metamodel.extension.Stereotype;
import meta.pure.metamodel.extension.StereotypeImpl;
import meta.pure.metamodel.function.LambdaFunctionImpl;
import meta.pure.metamodel.function.property.PropertyImpl;
import meta.pure.metamodel.multiplicity.InferredPackageableMultiplicityImpl;
import meta.pure.metamodel.multiplicity.Multiplicity;
import meta.pure.metamodel.multiplicity.MultiplicityParameter;
import meta.pure.metamodel.multiplicity.MultiplicityValueImpl;
import meta.pure.metamodel.multiplicity.PackageableMultiplicity;
import meta.pure.metamodel.multiplicity.UserDefinedMultiplicityParameterImpl;
import meta.pure.metamodel.multiplicity.UserDefinedPackageableMultiplicityImpl;
import meta.pure.metamodel.relationship.Generalization;
import meta.pure.metamodel.relationship.GeneralizationImpl;
import meta.pure.metamodel.type.ClassImpl;
import meta.pure.metamodel.type.EnumImpl;
import meta.pure.metamodel.type.Enumeration;
import meta.pure.metamodel.type.EnumerationImpl;
import meta.pure.metamodel.type.FunctionType;
import meta.pure.metamodel.type.FunctionTypeImpl;
import meta.pure.metamodel.type.PrimitiveTypeImpl;
import meta.pure.metamodel.type.Type;
import meta.pure.metamodel.type.generics.GenericType;
import meta.pure.metamodel.type.generics.TypeParameter;
import meta.pure.metamodel.type.generics.TypeParameterImpl;
import meta.pure.metamodel.type.generics.UserDefinedGenericType;
import meta.pure.metamodel.type.generics.UserDefinedGenericTypeImpl;
import meta.pure.metamodel.type.generics.UserDefinedPackageableGenericTypeImpl;
import meta.pure.metamodel.valuespecification.AtomicValueImpl;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;


/**
 * Reads {@code m3.ttl} from the filesystem and bootstraps all M3 types
 * (classes, primitive types, enumerations, and profiles) into the
 * package tree and element index.
 *
 * <p>Uses Apache Jena for proper TTL/RDF parsing.</p>
 */
public class M3BootstrapReader
{
    private static final String M3_NS = "https://finos.org/legend/pure/m3#";

    private M3BootstrapReader()
    {
    }

    /**
     * Read m3.ttl from the given filesystem path and bootstrap all M3
     * types into the given root package and index.
     */
    public static void bootstrap(Path m3TtlPath, Package root, MutableMap<String, PackageableElement> index)
    {
        // Root must be in the index so that it is serialized to PDB.
        // Without this, PDB-loaded packages cannot resolve their parent
        // chain back through Root, breaking path computation.
        index.put("::", root);
        if (!Files.exists(m3TtlPath))
        {
            throw new RuntimeException("m3.ttl not found at " + m3TtlPath);
        }
        try (InputStream is = Files.newInputStream(m3TtlPath))
        {
            Model model = ModelFactory.createDefaultModel();
            RDFDataMgr.read(model, is, Lang.TURTLE);

            // Build helper resources
            Resource m3Class = model.createResource(M3_NS + "Class");
            Resource m3PrimitiveType = model.createResource(M3_NS + "PrimitiveType");
            Resource m3Enumeration = model.createResource(M3_NS + "Enumeration");
            Resource m3Profile = model.createResource(M3_NS + "Profile");
            Resource m3UserDefinedPackageableMultiplicity = model.createResource(M3_NS + "UserDefinedPackageableMultiplicity");
            Resource m3InferredPackageableMultiplicity = model.createResource(M3_NS + "InferredPackageableMultiplicity");

            // First pass: create all elements
            bootstrapType(model, m3Class, root, index, "Class");
            bootstrapType(model, m3PrimitiveType, root, index, "PrimitiveType");
            bootstrapType(model, m3Enumeration, root, index, "Enumeration");
            bootstrapType(model, m3Profile, root, index, "Profile");
            // UDPGT named anchors must be in the index before any buildGenericType
            // call: multiplicity values reference :GenericType_MultiplicityValue,
            // and resolving that to the indexed PE keeps the writer's classifier
            // chain as PointerRefs instead of duplicate self-classified inline UDGTs.
            Resource m3UserDefinedPackageableGenericType = model.createResource(M3_NS + "UserDefinedPackageableGenericType");
            bootstrapPackageableGenericTypes(model, m3UserDefinedPackageableGenericType, root, index);
            bootstrapMultiplicities(model, m3UserDefinedPackageableMultiplicity, root, index, UserDefinedPackageableMultiplicityImpl::new);
            bootstrapMultiplicities(model, m3InferredPackageableMultiplicity, root, index, InferredPackageableMultiplicityImpl::new);

            // Second pass: wire parameters and generalizations now that all types exist
            wireTypeParameters(model, m3Class, index);
            wireGeneralizations(model, m3Class, index);
            wireGeneralizations(model, m3PrimitiveType, index);
            wireGeneralizations(model, m3Enumeration, index);

            // Wire classifierGenericType on types and packages
            wireClassifierGenericType(model, m3Class, index);
            wireClassifierGenericType(model, m3PrimitiveType, index);
            wireClassifierGenericType(model, m3Enumeration, index);
            wireClassifierGenericType(model, m3Profile, index);
            Resource m3Package = model.createResource(M3_NS + "Package");
            wireClassifierGenericType(model, m3Package, index);
            // Wire classifierGenericType on multiplicity instances
            wireClassifierGenericType(model, m3UserDefinedPackageableMultiplicity, index);
            wireClassifierGenericType(model, m3InferredPackageableMultiplicity, index);
            wireClassifierGenericType(model, m3UserDefinedPackageableGenericType, index);

            // Wire stereotypes into their parent profiles
            Resource m3Stereotype = model.createResource(M3_NS + "Stereotype");
            wireStereotypesToProfiles(model, m3Stereotype, index);
            wireClassifierGenericType(model, m3Stereotype, index);

            // Wire tags into their parent profiles (assuming Tag also exists)
            Resource m3Tag = model.createResource(M3_NS + "Tag");
            wireClassifierGenericType(model, m3Tag, index);

            // Wire stereotypes onto matching elements
            wireStereotypesToElements(model, index);

            // Third pass: wire enum values as properties on their Enumeration
            // Must run before property wiring so AggregationKind values are available
            bootstrapEnumValues(model, m3Enumeration, index);

            // Fourth pass: wire properties to their owner types
            Resource m3Property = model.createResource(M3_NS + "Property");
            bootstrapProperties(model, m3Property, index);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to read " + m3TtlPath, e);
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
                case "Class" -> new ClassImpl()._name(name)._package(pkg);
                case "PrimitiveType" -> new PrimitiveTypeImpl()._name(name)._package(pkg);
                case "Enumeration" -> new EnumerationImpl()._name(name)._package(pkg);
                case "Profile" -> new ProfileImpl()._name(name)._package(pkg);
                default -> throw new IllegalArgumentException("Unknown kind: " + kind);
            };

            pkg._children().add(element);
            index.put(packagePath + "::" + name, element);
        }
    }

    private static void wireTypeParameters(Model model, Resource typeResource, MutableMap<String, PackageableElement> index)
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
            PackageableElement element = index.get(packagePath + "::" + name);
            if (element instanceof meta.pure.metamodel.type.Class clazz)
            {
                MutableList<TypeParameter> typeParams = getTypeParameters(model, res, clazz, index);
                if (typeParams.notEmpty())
                {
                    clazz._typeParameters(typeParams);
                }
                MutableList<MultiplicityParameter> mulParams = getMultiplicityParameters(model, res, clazz, index);
                if (mulParams.notEmpty())
                {
                    clazz._multiplicityParameters(mulParams);
                }
            }
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
                Resource lowerRes = lowerStmt.getObject().asResource();
                Statement valueStmt = getM3Statement(model, lowerRes, "value");
                String valueStr = valueStmt != null ? getLiteralString(model, valueStmt) : null;
                if (valueStr != null)
                {
                    MultiplicityValueImpl lowerBound = new MultiplicityValueImpl()._value(Long.parseLong(valueStr));
                    Statement cgtStmt = getM3Statement(model, lowerRes, "classifierGenericType");
                    if (cgtStmt != null && cgtStmt.getObject().isResource())
                    {
                        lowerBound._classifierGenericType(buildGenericType(model, cgtStmt.getObject().asResource(), index));
                    }
                    multiplicity._lowerBound(lowerBound);
                }
            }

            // Upper bound
            Statement upperStmt = getM3Statement(model, res, "upperBound");
            if (upperStmt != null && upperStmt.getObject().isResource())
            {
                Resource upperRes = upperStmt.getObject().asResource();
                Statement valueStmt = getM3Statement(model, upperRes, "value");
                String valueStr = valueStmt != null ? getLiteralString(model, valueStmt) : null;
                if (valueStr != null)
                {
                    MultiplicityValueImpl upperBound = new MultiplicityValueImpl()._value(Long.parseLong(valueStr));
                    Statement cgtStmt = getM3Statement(model, upperRes, "classifierGenericType");
                    if (cgtStmt != null && cgtStmt.getObject().isResource())
                    {
                        upperBound._classifierGenericType(buildGenericType(model, cgtStmt.getObject().asResource(), index));
                    }
                    multiplicity._upperBound(upperBound);
                }
            }

            pkg._children().add(multiplicity);
            index.put(packagePath + "::" + name, multiplicity);
        }
    }

    /**
     * Bootstrap {@code UserDefinedPackageableGenericType} instances from the TTL.
     * These are pre-built GenericType wrappers for M3 types, registered at paths
     * like {@code meta::pure::metamodel::type::generics::optimization::GenericType_SourceInformation}.
     */
    private static void bootstrapPackageableGenericTypes(
            Model model,
            Resource typeResource,
            Package root,
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

            Package pkg = getOrCreatePackage(root, packagePath, index);
            UserDefinedPackageableGenericTypeImpl pgt = new UserDefinedPackageableGenericTypeImpl();
            pgt._name(name);
            pgt._package(pkg);

            // Wire the :type reference to the actual M3 type
            Statement typeStmt = getM3Statement(model, res, "type");
            if (typeStmt != null && typeStmt.getObject().isResource())
            {
                Type type = findType(model, typeStmt.getObject().asResource(), index);
                if (type != null)
                {
                    pgt._type(type);
                }
            }

            pkg._children().add(pgt);
            index.put(packagePath + "::" + name, pgt);
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

                // Build the GenericType for the generalization from TTL
                GenericType generalGT = buildGenericType(model, generalGTRes, index);

                GeneralizationImpl generalization = new GeneralizationImpl()
                        ._general(generalGT)
                        ._specific(specificType);

                Statement cgtStmt = getM3Statement(model, genRes, "classifierGenericType");
                if (cgtStmt != null && cgtStmt.getObject().isResource())
                {
                    generalization._classifierGenericType(buildGenericType(model, cgtStmt.getObject().asResource(), index));
                }

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
            if (name == null)
            {
                continue;
            }

            String fullPath = packagePath != null ? packagePath + "::" + name : name;
            PackageableElement element = index.get(fullPath);
            if (element == null)
            {
                continue;
            }

            Statement cgtStmt = getM3Statement(model, res, "classifierGenericType");
            if (cgtStmt != null && cgtStmt.getObject().isResource())
            {
                meta.pure.metamodel.type.generics.GenericTypeValue cgt = buildGenericType(model, cgtStmt.getObject().asResource(), index);
                element._classifierGenericType(cgt);
            }
        }
    }

    /**
     * Wire Stereotype instances into their parent Profile's {@code _p_stereotypes()} list.
     * This is needed so the PDB writer can compute stereotype paths and the PDB reader
     * can resolve them via {@code profile._p_stereotypes().detect(...)}.
     */
    private static void wireStereotypesToProfiles(
            Model model,
            Resource stereotypeResource,
            MutableMap<String, PackageableElement> index)
    {
        for (ResIterator it = model.listSubjectsWithProperty(RDF.type, stereotypeResource); it.hasNext();)
        {
            Resource stRes = it.next();
            String stName = getName(model, stRes);
            if (stName == null)
            {
                continue;
            }

            Statement profStmt = getM3Statement(model, stRes, "profile");
            if (profStmt == null || !profStmt.getObject().isResource())
            {
                continue;
            }
            Resource profRes = profStmt.getObject().asResource();
            String profName = getName(model, profRes);
            if (profName == null)
            {
                continue;
            }

            // Find the Profile in the index
            for (PackageableElement el : index.valuesView())
            {
                if (el instanceof meta.pure.metamodel.extension.Profile profile && profName.equals(el._name()))
                {
                    StereotypeImpl st = new StereotypeImpl()._value(stName)._profile(profile);
                    Statement cgtStmt = getM3Statement(model, stRes, "classifierGenericType");
                    if (cgtStmt != null && cgtStmt.getObject().isResource())
                    {
                        st._classifierGenericType(buildGenericType(model, cgtStmt.getObject().asResource(), index));
                    }
                    profile._p_stereotypes().add(st);
                    break;
                }
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

            // Wire aggregation
            Statement aggStmt = getM3Statement(model, res, "aggregation");
            if (aggStmt != null && aggStmt.getObject().isResource())
            {
                String aggName = getName(model, aggStmt.getObject().asResource()); // typically "None", "Shared", "Composite"
                if (aggName == null)
                {
                    // Fallback to extract from IRI if name property is missing on enum value
                    String uri = aggStmt.getObject().asResource().getURI();
                    aggName = uri.substring(uri.lastIndexOf('_') + 1);
                }
                meta.pure.metamodel.type.Enumeration aggEnum = (meta.pure.metamodel.type.Enumeration) index.get("meta::pure::metamodel::function::property::AggregationKind");
                prop._aggregation((meta.pure.metamodel.function.property.AggregationKind) org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Enumeration.resolveEnumValue(aggEnum, aggName));
            }
            else
            {
                meta.pure.metamodel.type.Enumeration aggEnum = (meta.pure.metamodel.type.Enumeration) index.get("meta::pure::metamodel::function::property::AggregationKind");
                prop._aggregation((meta.pure.metamodel.function.property.AggregationKind) org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Enumeration.resolveEnumValue(aggEnum, "None"));
            }

            // Wire stereotypes (e.g., ProtocolInfo.inferred, ProtocolInfo.excluded)
            org.apache.jena.rdf.model.Property stereotypesPred = model.getProperty(M3_NS + "stereotypes");
            MutableList<Stereotype> stereotypes = Lists.mutable.empty();
            for (StmtIterator stIt = model.listStatements(res, stereotypesPred, (RDFNode) null); stIt.hasNext();)
            {
                Statement stStmt = stIt.next();
                if (stStmt.getObject().isResource())
                {
                    Stereotype st = findStereotype(model, stStmt.getObject().asResource(), index);
                    if (st != null)
                    {
                        stereotypes.add(st);
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

    private static Stereotype findStereotype(Model model, Resource stRes, MutableMap<String, PackageableElement> index)
    {
        String stName = getName(model, stRes);
        if (stName != null)
        {
            Statement profStmt = getM3Statement(model, stRes, "profile");
            if (profStmt != null && profStmt.getObject().isResource())
            {
                Resource profRes = profStmt.getObject().asResource();
                String profName = getName(model, profRes);
                if (profName != null)
                {
                    for (PackageableElement el : index.valuesView())
                    {
                        if (el instanceof meta.pure.metamodel.extension.Profile p && profName.equals(el._name()))
                        {
                            return p._p_stereotypes().detect(s -> stName.equals(s._value()));
                        }
                    }
                }
            }
        }
        return null;
    }

    private static void wireStereotypesToElements(Model model, MutableMap<String, PackageableElement> index)
    {
        org.apache.jena.rdf.model.Property stereotypesPred = model.getProperty(M3_NS + "stereotypes");
        for (StmtIterator stIt = model.listStatements(null, stereotypesPred, (RDFNode) null); stIt.hasNext();)
        {
            Statement stStmt = stIt.next();
            Resource subject = stStmt.getSubject();
            if (!subject.isResource())
            {
                continue;
            }

            String name = getName(model, subject);
            String packagePath = getPackagePath(model, subject);
            if (name == null)
            {
                continue;
            }

            String fullPath = packagePath != null ? packagePath + "::" + name : name;
            PackageableElement element = index.get(fullPath);
            if (element instanceof meta.pure.metamodel.extension.ElementWithStereotypes ews)
            {
                if (stStmt.getObject().isResource())
                {
                    Stereotype st = findStereotype(model, stStmt.getObject().asResource(), index);
                    if (st != null)
                    {
                        MutableList<Stereotype> stereotypes = Lists.mutable.withAll(ews._stereotypes());
                        stereotypes.add(st);
                        ews._stereotypes(stereotypes);
                    }
                }
            }
        }
    }

    /**
     * Bootstrap enum values as Properties on their Enumeration.
     * For each Enumeration found in the index, find all RDF subjects whose
     * rdf:type is that Enumeration and create a Property on the Enumeration
     * for each enum value, mirroring what EnumerationHandler.secondPass does.
     */
    private static void bootstrapEnumValues(
            Model model,
            Resource enumerationTypeResource,
            MutableMap<String, PackageableElement> index)
    {
        // Find all Enumeration instances in the index
        for (ResIterator it = model.listSubjectsWithProperty(RDF.type, enumerationTypeResource); it.hasNext();)
        {
            Resource enumRes = it.next();
            String enumName = getName(model, enumRes);
            String enumPkg = getPackagePath(model, enumRes);
            if (enumName == null || enumPkg == null)
            {
                continue;
            }

            String enumFullPath = enumPkg + "::" + enumName;
            PackageableElement enumElement = index.get(enumFullPath);
            if (!(enumElement instanceof Enumeration enumeration))
            {
                continue;
            }

            // GenericType for this enumeration's values (e.g., GenericType pointing to GenericTypeOperationType)
            UserDefinedGenericType enumGT = new UserDefinedGenericTypeImpl()
                    ._type((Type) enumeration);
            enumGT._classifierGenericType(buildUdgtAnchor(index));

            // GenericType for Enumeration<E> parameterized with this enum type
            Type enumerationType = (Type) index.get("meta::pure::metamodel::type::Enumeration");
            UserDefinedGenericType enumerationOfE = new UserDefinedGenericTypeImpl()
                    ._type(enumerationType)
                    ._typeArguments(Lists.mutable.with(enumGT));
            enumerationOfE._classifierGenericType(buildUdgtAnchor(index));

            Type propertyType = (Type) index.get("meta::pure::metamodel::function::property::Property");
            Multiplicity pureOne = (Multiplicity) index.get("meta::pure::metamodel::multiplicity::PureOne");

            // Find all instances of this Enumeration in the RDF (e.g., :GenericTypeOperationType_Union a :GenericTypeOperationType)
            for (ResIterator valIt = model.listSubjectsWithProperty(RDF.type, enumRes); valIt.hasNext();)
            {
                Resource valRes = valIt.next();
                String valName = getName(model, valRes);
                if (valName == null)
                {
                    continue;
                }

                // Create a typed Enum instance (e.g., AggregationKindImpl instead of EnumImpl)
                EnumImpl enumInstance = createTypedEnumInstance(enumPkg, enumName);
                enumInstance._name(valName);
                Statement cgtStmt = getM3Statement(model, valRes, "classifierGenericType");
                if (cgtStmt != null && cgtStmt.getObject().isResource())
                {
                    enumInstance._classifierGenericType(buildGenericType(model, cgtStmt.getObject().asResource(), index));
                }

                // Build classifierGenericTypes for the wrapper objects
                Type lambdaType = (Type) index.get("meta::pure::metamodel::function::LambdaFunction");
                UserDefinedGenericType lambdaCGT = new UserDefinedGenericTypeImpl()
                        ._type(lambdaType)
                        ._typeArguments(Lists.mutable.with(enumGT));
                lambdaCGT._classifierGenericType(buildUdgtAnchor(index));

                Type atomicValueType = (Type) index.get("meta::pure::metamodel::valuespecification::AtomicValue");
                UserDefinedGenericType avCGT = new UserDefinedGenericTypeImpl()
                        ._type(atomicValueType);
                avCGT._classifierGenericType(buildUdgtAnchor(index));

                // Create a lambda whose body is the AtomicValue wrapping the Enum
                LambdaFunctionImpl defaultValueLambda = new LambdaFunctionImpl();
                defaultValueLambda._classifierGenericType(lambdaCGT);
                defaultValueLambda._expressionSequence(Lists.mutable.with(
                        new AtomicValueImpl()
                                ._classifierGenericType(avCGT)
                                ._value(enumInstance)
                                ._genericType(enumGT)
                                ._multiplicity(pureOne)
                ));

                // Create the Property: Property<Enumeration<E>, E | 1>
                UserDefinedGenericType propCGT = new UserDefinedGenericTypeImpl()
                        ._type(propertyType)
                        ._typeArguments(Lists.mutable.with(enumerationOfE, enumGT))
                        ._multiplicityArguments(Lists.mutable.with(pureOne));
                propCGT._classifierGenericType(buildUdgtAnchor(index));

                PropertyImpl prop = new PropertyImpl()
                        ._name(valName)
                        ._owner(enumeration)
                        ._genericType(enumGT)
                        ._multiplicity(pureOne)
                        ._classifierGenericType(propCGT)
                        ._defaultValue(defaultValueLambda);

                enumeration._properties().add(prop);
            }
        }

        // Post-pass: set aggregation on all enum value properties now that AggregationKind values exist
        meta.pure.metamodel.type.Enumeration aggKindEnum = (meta.pure.metamodel.type.Enumeration) index.get("meta::pure::metamodel::function::property::AggregationKind");
        if (aggKindEnum != null)
        {
            meta.pure.metamodel.function.property.AggregationKind aggNone = (meta.pure.metamodel.function.property.AggregationKind) org.finos.legend.pure.m3.pureLanguage.pureLanguageCompiler.helper._Enumeration.resolveEnumValue(aggKindEnum, "None");
            for (PackageableElement elem : index.valuesView())
            {
                if (elem instanceof Enumeration enumeration && enumeration._properties() != null)
                {
                    for (meta.pure.metamodel.function.property.Property prop : enumeration._properties())
                    {
                        if (prop._aggregation() == null)
                        {
                            prop._aggregation(aggNone);
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String getName(Model model, Resource res)
    {
        // Check both :name (used by Classes, Packages, etc.) and :abstractProperty_name (used by Properties)
        for (String predicate : new String[]{"name", "typeParameter_name", "MultiplicityParameter_name", "abstractProperty_name", "stereotype_name", "tag_value", "enumValue", "VariableExpression_name"})
        {
            Statement stmt = getM3Statement(model, res, predicate);
            if (stmt != null)
            {
                String val = getLiteralString(model, stmt);
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
    private static String getLiteralString(Model model, Statement stmt)
    {
        if (stmt.getObject().isLiteral())
        {
            return stmt.getLiteral().getString();
        }
        else if (stmt.getObject().isResource())
        {
            // Typed primitive node: [ :classifierGenericType :GenericType_X ; :data "value" ]
            Statement dataStmt = getM3Statement(model, stmt.getObject().asResource(), "data");
            if (dataStmt != null && dataStmt.getObject().isLiteral())
            {
                return dataStmt.getLiteral().getString();
            }
        }
        return null;
    }

    private static String getPackagePath(Model model, Resource res)
    {
        Statement stmt = getM3Statement(model, res, "package");
        if (stmt == null || !stmt.getObject().isResource())
        {
            return null;
        }
        String localName = getLocalName(stmt.getObject().asResource());
        if (localName == null || "Root".equals(localName))
        {
            // Root is the top-level package; treat as no parent
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

    private static MutableList<TypeParameter> getTypeParameters(Model model, Resource res,
            meta.pure.metamodel.type.generics.TypeAndMultiplicityParametersOwner owner, MutableMap<String, PackageableElement> index)
    {
        MutableList<TypeParameter> result = Lists.mutable.empty();
        for (Resource paramRes : extractPropertyResources(model, res, "TypeAndMultiplicityParametersOwner_typeParameters"))
        {
            String paramName = getName(model, paramRes);
            if (paramName != null)
            {
                TypeParameterImpl tp = new TypeParameterImpl()._name(paramName)._contravariant(false)._owner(owner);
                Statement cgtStmt = getM3Statement(model, paramRes, "classifierGenericType");
                if (cgtStmt != null && cgtStmt.getObject().isResource())
                {
                    tp._classifierGenericType(buildGenericType(model, cgtStmt.getObject().asResource(), index));
                }

                Statement contravariantStmt = getM3Statement(model, paramRes, "contravariant");
                if (contravariantStmt != null)
                {
                    String contraVal = getLiteralString(model, contravariantStmt);
                    tp._contravariant("true".equals(contraVal));
                }
                else
                {
                    tp._contravariant(false);
                }

                result.add(tp);
            }
        }
        return result;
    }

    private static MutableList<MultiplicityParameter> getMultiplicityParameters(Model model, Resource res,
            meta.pure.metamodel.type.generics.TypeAndMultiplicityParametersOwner owner, MutableMap<String, PackageableElement> index)
    {
        MutableList<MultiplicityParameter> result = Lists.mutable.empty();
        for (Resource paramRes : extractPropertyResources(model, res, "TypeAndMultiplicityParametersOwner_multiplicityParameters"))
        {
            String paramName = getName(model, paramRes);
            if (paramName != null)
            {
                UserDefinedMultiplicityParameterImpl mp = new UserDefinedMultiplicityParameterImpl()._name(paramName)._owner(owner);
                Statement cgtStmt = getM3Statement(model, paramRes, "classifierGenericType");
                if (cgtStmt != null && cgtStmt.getObject().isResource())
                {
                    mp._classifierGenericType(buildGenericType(model, cgtStmt.getObject().asResource(), index));
                }
                result.add(mp);
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
            Statement gtStmt = getM3Statement(model, paramRes, "ValueSpecification_genericType");
            if (gtStmt != null && gtStmt.getObject().isResource())
            {
                ve._genericType(buildGenericType(model, gtStmt.getObject().asResource(), index));
            }

            // Multiplicity
            Statement mulStmt = getM3Statement(model, paramRes, "ValueSpecification_multiplicity");
            if (mulStmt != null && mulStmt.getObject().isResource())
            {
                ve._multiplicity(lookupMultiplicity(mulStmt.getObject().asResource(), index));
            }

            // classifierGenericType
            Statement veCgtStmt = getM3Statement(model, paramRes, "classifierGenericType");
            if (veCgtStmt != null && veCgtStmt.getObject().isResource())
            {
                meta.pure.metamodel.type.generics.GenericTypeValue cgt = buildGenericType(model, veCgtStmt.getObject().asResource(), index);
                ve._classifierGenericType(cgt);
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
            Statement mulParamStmt = getM3Statement(model, mulRes, "MultiplicityParameter_name");
            String mulParamName = mulParamStmt != null ? getLiteralString(model, mulParamStmt) : null;
            if (mulParamName != null)
            {
                // Multiplicity parameter reference: [ :MultiplicityParameter_name [ :classifierGenericType ... ; :data "m" ] ]
                meta.pure.metamodel.multiplicity.UserDefinedMultiplicityParameterImpl mp = new meta.pure.metamodel.multiplicity.UserDefinedMultiplicityParameterImpl()._name(mulParamName);
                Statement cgtStmt = getM3Statement(model, mulRes, "classifierGenericType");
                if (cgtStmt != null && cgtStmt.getObject().isResource())
                {
                    mp._classifierGenericType(buildGenericType(model, cgtStmt.getObject().asResource(), index));
                }
                ft._returnMultiplicity(mp);
            }
            else
            {
                ft._returnMultiplicity(lookupMultiplicity(mulRes, index));
            }
        }

        Statement cgtStmt = getM3Statement(model, ftRes, "classifierGenericType");
        if (cgtStmt != null && cgtStmt.getObject().isResource())
        {
            ft._classifierGenericType(buildGenericType(model, cgtStmt.getObject().asResource(), index));
        }

        return ft;
    }

    /**
     * Build a GenericType from an RDF resource. Handles typeParameter references,
     * concrete rawType references, typeArguments, and multiplicityArguments.
     *
     * <p>If the resource refers to a named PackageableElement that's already in
     * the index (e.g., a UDPGT named anchor like
     * {@code :GenericType_UserDefinedGenericType}), the indexed instance is
     * returned directly. Otherwise a fresh inline UDGT is built. Without this
     * lookup, references to named anchors materialize as duplicate self-classified
     * UDGT impls — which then get serialized inline rather than as PointerRefs.
     */
    private static meta.pure.metamodel.type.generics.GenericTypeValue buildGenericType(
            Model model, Resource gtRes, MutableMap<String, PackageableElement> index)
    {
        String name = getName(model, gtRes);
        String packagePath = getPackagePath(model, gtRes);
        if (name != null && packagePath != null)
        {
            PackageableElement existing = index.get(packagePath + "::" + name);
            if (existing instanceof meta.pure.metamodel.type.generics.GenericTypeValue existingGt)
            {
                return existingGt;
            }
        }

        UserDefinedGenericTypeImpl gt = new UserDefinedGenericTypeImpl();

        // Concrete rawType reference
        Statement rawTypeStmt = getM3Statement(model, gtRes, "type");
        if (rawTypeStmt != null && rawTypeStmt.getObject().isResource())
        {
            Resource rawTypeRes = rawTypeStmt.getObject().asResource();

            // Check if the rawType is an anonymous FunctionType or a TypeParameter
            Statement rdfTypeStmt = rawTypeRes.getProperty(RDF.type);
            if (rdfTypeStmt != null && rdfTypeStmt.getObject().isResource())
            {
                String rdfTypeName = getLocalName(rdfTypeStmt.getObject().asResource());
                if ("FunctionType".equals(rdfTypeName))
                {
                    gt._type(buildFunctionType(model, rawTypeRes, index));
                }
                else if ("TypeParameter".equals(rdfTypeName))
                {
                    String paramName = null;
                    Statement tpNameStmt = getM3Statement(model, rawTypeRes, "typeParameter_name");
                    if (tpNameStmt != null)
                    {
                        paramName = getLiteralString(model, tpNameStmt);
                    }
                    if (paramName == null)
                    {
                        paramName = getName(model, rawTypeRes);
                    }
                    
                    if (paramName != null)
                    {
                        TypeParameterImpl tp = new TypeParameterImpl()._name(paramName);
                        Statement cgtStmt = getM3Statement(model, rawTypeRes, "classifierGenericType");
                        if (cgtStmt != null && cgtStmt.getObject().isResource())
                        {
                            tp._classifierGenericType(buildGenericType(model, cgtStmt.getObject().asResource(), index));
                        }

                        Statement contravariantStmt = getM3Statement(model, rawTypeRes, "contravariant");
                        if (contravariantStmt != null)
                        {
                            String contraVal = getLiteralString(model, contravariantStmt);
                            tp._contravariant("true".equals(contraVal));
                        }
                        else
                        {
                            tp._contravariant(false);
                        }

                        gt._type(tp);
                    }
                }
                else
                {
                    Type type = findType(model, rawTypeRes, index);
                    if (type != null)
                    {
                        gt._type(type);
                    }
                }
            }
            else
            {
                Type type = findType(model, rawTypeRes, index);
                if (type != null)
                {
                    gt._type(type);
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
            // Check for multiplicityParameter reference (e.g., [ :multiplicityParameter "m" ])
            Statement mulParamStmt = getM3Statement(model, mulArgRes, "MultiplicityParameter_name");
            String mulArgParamName = mulParamStmt != null ? getLiteralString(model, mulParamStmt) : null;
            if (mulArgParamName != null)
            {
                UserDefinedMultiplicityParameterImpl mp = new UserDefinedMultiplicityParameterImpl()._name(mulArgParamName);
                Statement cgtStmt = getM3Statement(model, mulArgRes, "classifierGenericType");
                if (cgtStmt != null && cgtStmt.getObject().isResource())
                {
                    mp._classifierGenericType(buildGenericType(model, cgtStmt.getObject().asResource(), index));
                }
                mulArgs.add(mp);
            }
            else
            {
                Multiplicity mul = lookupMultiplicity(mulArgRes, index);
                if (mul != null)
                {
                    mulArgs.add(mul);
                }
            }
        }
        if (mulArgs.notEmpty())
        {
            gt._multiplicityArguments(mulArgs);
        }

        // classifierGenericType: REQUIRED. Every UDGT in the TTL must specify
        // its classifier explicitly. Self-references are written by stating
        // `:classifierGenericType :GenericType_self_resource`. We don't infer
        // self-ref from absence — that hid bugs where elements emerged with
        // a self-classified UDGT they shouldn't have.
        Statement cgtStmt = getM3Statement(model, gtRes, "classifierGenericType");
        if (cgtStmt == null || !cgtStmt.getObject().isResource())
        {
            throw new IllegalStateException(
                    "GenericType " + gtRes.getURI() + " is missing a classifierGenericType in the TTL. "
                            + "Every UserDefinedGenericType must declare one explicitly — self-references "
                            + "must be written, not inferred from absence.");
        }
        Resource cgtRes = cgtStmt.getObject().asResource();
        if (cgtRes.equals(gtRes))
        {
            // Explicit self-reference: only valid for the canonical
            // UDGT meta-class anchor (`:GenericType_UserDefinedGenericType`).
            gt._classifierGenericType(gt);
        }
        else
        {
            gt._classifierGenericType(buildGenericType(model, cgtRes, index));
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

    private static Type findType(Model model, Resource typeRes, MutableMap<String, PackageableElement> index)
    {
        String pkg = getPackagePath(model, typeRes);
        String name = getName(model, typeRes);
        if (pkg != null && name != null)
        {
            String fullPath = pkg + "::" + name;
            PackageableElement element = index.get(fullPath);
            if (element instanceof Type)
            {
                return (Type) element;
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

    /**
     * Build a fresh canonical UDGT anchor: a self-classified UDGT whose
     * {@code _type} is the {@code UserDefinedGenericType} meta-class. This is
     * the only shape in which self-classification is valid; using it as the
     * classifier of any other UDGT gives that UDGT a proper {@code _cgt._type
     * = UserDefinedGenericType} reading.
     */
    private static UserDefinedGenericType buildUdgtAnchor(MutableMap<String, PackageableElement> index)
    {
        UserDefinedGenericTypeImpl anchor = new UserDefinedGenericTypeImpl();
        anchor._type((Type) index.get("meta::pure::metamodel::type::generics::UserDefinedGenericType"));
        anchor._classifierGenericType(anchor);
        return anchor;
    }

    /**
     * Create a typed EnumImpl subclass instance (e.g., AggregationKindImpl)
     * so that the enum value implements the specific interface (e.g., AggregationKind).
     * Falls back to plain EnumImpl if the typed class is not found.
     */
    private static EnumImpl createTypedEnumInstance(String purePkg, String enumName)
    {
        // Convert Pure package path to Java package (matching RdfJavaGenerator.toJavaPackage)
        String javaPackage = purePkg.replace("::", ".");
        String implClassName = javaPackage + "." + enumName + "Impl";
        try
        {
            Class<?> implClass = Class.forName(implClassName);
            return (EnumImpl) implClass.getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            // Fallback to plain EnumImpl
            return new EnumImpl();
        }
    }
}
