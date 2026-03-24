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

package org.finos.legend.pure.m3.specification;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.Lang;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generic TTL multiplicity validator.
 * <p>
 * Reads class and property definitions from m3.ttl, extracts multiplicity constraints,
 * and validates that every instance complies. Automatically adapts to RDF modifications —
 * no property names are hardcoded.
 */
public class RdfMultiplicityValidationTest
{
    private static final String M3_TTL = "specification/m3.ttl";
    private static final String M3_NS = "https://finos.org/legend/pure/m3#";

    /**
     * Information about a property: its name, multiplicity lower bound, owner class, and whether it has a default value.
     */
    private record PropertyInfo(String name, String rdfPredicateName, String ownerClassName, long lowerBound, long upperBound, boolean hasDefaultValue) {}

    /**
     * Test that all named instances in m3.ttl comply with multiplicity constraints
     * defined on their class properties.
     */
    @Test
    public void testMultiplicityCompliance()
    {
        Model model = loadModel(M3_TTL);

        // Step 1: Build metamodel — classes, properties, multiplicities, generalizations
        Map<String, Set<String>> classGeneralizations = buildGeneralizationMap(model);
        Map<String, List<PropertyInfo>> classDeclaredProperties = buildPropertyMap(model);

        // Step 2: For each class, compute ALL properties (declared + inherited)
        Map<String, List<PropertyInfo>> classAllProperties = new HashMap<>();
        for (String className : classDeclaredProperties.keySet())
        {
            classAllProperties.put(className, getAllProperties(className, classDeclaredProperties, classGeneralizations, new HashSet<>()));
        }
        // Also compute for classes that only have inherited properties (no declared ones)
        for (String className : classGeneralizations.keySet())
        {
            if (!classAllProperties.containsKey(className))
            {
                classAllProperties.put(className, getAllProperties(className, classDeclaredProperties, classGeneralizations, new HashSet<>()));
            }
        }

        // Step 3: Find all named instances and validate them
        List<String> violations = new ArrayList<>();

        // Find all class names (resources that are declared as :Class, :PrimitiveType, :Enumeration, etc.)
        Set<String> classNames = new HashSet<>();
        classNames.addAll(classGeneralizations.keySet());
        classNames.addAll(classDeclaredProperties.keySet());

        // Find all concrete type resources (used as rdf:type targets)
        Set<String> concreteTypes = new HashSet<>();
        StmtIterator typeStmts = model.listStatements(null, RDF.type, (RDFNode) null);
        while (typeStmts.hasNext())
        {
            Statement stmt = typeStmts.next();
            if (stmt.getObject().isURIResource())
            {
                concreteTypes.add(localName(stmt.getObject().asResource().getURI()));
            }
        }

        // For each concrete type, find instances and validate
        for (String typeName : concreteTypes)
        {
            Resource typeRes = model.getResource(M3_NS + typeName);
            List<PropertyInfo> requiredProps = getRequiredProperties(typeName, classAllProperties);
            if (requiredProps.isEmpty())
            {
                continue;
            }

            // Find all instances of this type
            ResIterator instances = model.listSubjectsWithProperty(RDF.type, typeRes);
            while (instances.hasNext())
            {
                Resource instance = instances.next();
                String instanceName = getInstanceName(model, instance);

                for (PropertyInfo prop : requiredProps)
                {
                    Property m3Prop = model.getProperty(M3_NS + prop.rdfPredicateName());
                    StmtIterator propStmts = instance.listProperties(m3Prop);
                    int count = 0;
                    while (propStmts.hasNext())
                    {
                        propStmts.next();
                        count++;
                    }

                    if (count < prop.lowerBound())
                    {
                        violations.add(String.format(
                                "%s (a %s): missing required property '%s' (declared on %s, multiplicity lower bound=%d)",
                                instanceName, typeName, prop.name(), prop.ownerClassName(), prop.lowerBound()));
                    }
                }
            }
        }

        if (!violations.isEmpty())
        {
            StringBuilder sb = new StringBuilder("Multiplicity violations found (" + violations.size() + "):\n");
            for (String v : violations)
            {
                sb.append("  - ").append(v).append("\n");
            }
            fail(sb.toString());
        }
    }

    /**
     * Test that all instances only use properties that are defined on their class or superclasses.
     * Catches issues like using :name on a Stereotype when Stereotype doesn't have a name property.
     */
    @Test
    public void testUnknownProperties()
    {
        Model model = loadModel(M3_TTL);

        // Step 1: Build the metamodel
        Map<String, Set<String>> classGeneralizations = buildGeneralizationMap(model);

        // Step 2: Build map of class → valid RDF property predicates (resource URIs)
        // A property resource :foo owned by class :Bar means instances of Bar can use :foo as a predicate
        Map<String, Set<String>> classDeclaredPredicates = buildPredicateMap(model);

        // Step 3: Compute valid predicates per class (own + inherited)
        Map<String, Set<String>> classAllPredicates = new HashMap<>();
        Set<String> allClassNames = new HashSet<>();
        allClassNames.addAll(classGeneralizations.keySet());
        allClassNames.addAll(classDeclaredPredicates.keySet());
        for (String className : allClassNames)
        {
            classAllPredicates.put(className, getAllPredicates(className, classDeclaredPredicates, classGeneralizations, new HashSet<>()));
        }

        // Step 4: Structural predicates that are always valid (not class-specific properties)
        Set<String> structuralPredicates = new HashSet<>();
        structuralPredicates.add(RDF.type.getURI());

        // Step 5: Validate instances
        List<String> violations = new ArrayList<>();

        Set<String> concreteTypes = new HashSet<>();
        StmtIterator typeStmts = model.listStatements(null, RDF.type, (RDFNode) null);
        while (typeStmts.hasNext())
        {
            Statement stmt = typeStmts.next();
            if (stmt.getObject().isURIResource())
            {
                concreteTypes.add(localName(stmt.getObject().asResource().getURI()));
            }
        }

        for (String typeName : concreteTypes)
        {
            Resource typeRes = model.getResource(M3_NS + typeName);
            Set<String> validPredicates = classAllPredicates.getOrDefault(typeName, Set.of());

            ResIterator instances = model.listSubjectsWithProperty(RDF.type, typeRes);
            while (instances.hasNext())
            {
                Resource instance = instances.next();
                String instanceName = getInstanceName(model, instance);

                StmtIterator stmts = instance.listProperties();
                while (stmts.hasNext())
                {
                    Statement stmt = stmts.next();
                    String predicateUri = stmt.getPredicate().getURI();

                    // Skip non-M3 predicates and structural predicates
                    if (!predicateUri.startsWith(M3_NS) || structuralPredicates.contains(predicateUri))
                    {
                        continue;
                    }

                    String predicateName = localName(predicateUri);
                    if (!validPredicates.contains(predicateName))
                    {
                        violations.add(String.format(
                                "%s (a %s): uses unknown property '%s' (not defined on %s or its superclasses)",
                                instanceName, typeName, predicateName, typeName));
                    }
                }
            }
        }

        if (!violations.isEmpty())
        {
            StringBuilder sb = new StringBuilder("Unknown property violations found (" + violations.size() + "):\n");
            for (String v : violations)
            {
                sb.append("  - ").append(v).append("\n");
            }
            fail(sb.toString());
        }
    }

    /**
     * Build a map from class name → direct superclass names from generalizations.
     */
    private Map<String, Set<String>> buildGeneralizationMap(Model model)
    {
        Map<String, Set<String>> result = new HashMap<>();
        Property generalProp = model.getProperty(M3_NS + "general");
        Property typeProp = model.getProperty(M3_NS + "type");
        Property generalizationsProp = model.getProperty(M3_NS + "generalizations");

        // Find all classes (resources that have generalizations OR are declared as Class/PrimitiveType)
        Resource classType = model.getResource(M3_NS + "Class");
        Resource primitiveType = model.getResource(M3_NS + "PrimitiveType");
        Resource enumerationType = model.getResource(M3_NS + "Enumeration");

        Set<Resource> typeResources = new HashSet<>();
        typeResources.add(classType);
        typeResources.add(primitiveType);
        typeResources.add(enumerationType);

        // Collect all resources that are declared as some type
        StmtIterator allTypeStmts = model.listStatements(null, RDF.type, (RDFNode) null);
        while (allTypeStmts.hasNext())
        {
            Statement stmt = allTypeStmts.next();
            if (stmt.getSubject().isURIResource())
            {
                String subjectName = localName(stmt.getSubject().getURI());
                result.computeIfAbsent(subjectName, k -> new HashSet<>());
            }
        }

        // Walk all named resources that have :generalizations
        for (String className : new HashSet<>(result.keySet()))
        {
            Resource classRes = model.getResource(M3_NS + className);
            StmtIterator genStmts = classRes.listProperties(generalizationsProp);
            while (genStmts.hasNext())
            {
                Statement genStmt = genStmts.next();
                if (!genStmt.getObject().isResource())
                {
                    continue;
                }
                Resource genBlank = genStmt.getObject().asResource();

                // Get :general from the generalization blank node
                Statement generalStmt = genBlank.getProperty(generalProp);
                if (generalStmt == null || !generalStmt.getObject().isResource())
                {
                    continue;
                }
                Resource generalGT = generalStmt.getObject().asResource();

                // Get :type from the GenericType
                Statement typeStmt = generalGT.getProperty(typeProp);
                if (typeStmt == null || !typeStmt.getObject().isURIResource())
                {
                    continue;
                }
                String superName = localName(typeStmt.getObject().asResource().getURI());
                result.computeIfAbsent(className, k -> new HashSet<>()).add(superName);
            }
        }

        return result;
    }

    /**
     * Build a map from class name → declared properties (with multiplicity info).
     */
    private Map<String, List<PropertyInfo>> buildPropertyMap(Model model)
    {
        Map<String, List<PropertyInfo>> result = new HashMap<>();
        Resource propertyType = model.getResource(M3_NS + "Property");
        Property ownerProp = model.getProperty(M3_NS + "owner");
        Property multiplicityProp = model.getProperty(M3_NS + "multiplicity");
        Property nameProp = model.getProperty(M3_NS + "abstractProperty_name");
        Property lowerBoundProp = model.getProperty(M3_NS + "lowerBound");
        Property upperBoundProp = model.getProperty(M3_NS + "upperBound");
        Property valueProp = model.getProperty(M3_NS + "value");
        Property defaultValueProp = model.getProperty(M3_NS + "prop_defaultValue");

        // Find all Property instances
        ResIterator propInstances = model.listSubjectsWithProperty(RDF.type, propertyType);
        while (propInstances.hasNext())
        {
            Resource prop = propInstances.next();

            // Get property name
            Statement nameStmt = prop.getProperty(nameProp);
            if (nameStmt == null)
            {
                continue;
            }
            String propName = nameStmt.getString();

            // Get the RDF resource local name (the actual predicate used on instances)
            String rdfPredicateName = prop.isURIResource() ? localName(prop.getURI()) : null;
            if (rdfPredicateName == null)
            {
                continue;
            }

            // Get owner class
            Statement ownerStmt = prop.getProperty(ownerProp);
            if (ownerStmt == null || !ownerStmt.getObject().isURIResource())
            {
                continue;
            }
            String ownerClassName = localName(ownerStmt.getObject().asResource().getURI());

            // Get multiplicity
            Statement mulStmt = prop.getProperty(multiplicityProp);
            if (mulStmt == null || !mulStmt.getObject().isURIResource())
            {
                continue;
            }
            Resource mulRes = mulStmt.getObject().asResource();

            // Read lower and upper bounds from the multiplicity instance
            long lower = readBound(model, mulRes, lowerBoundProp, valueProp, 0);
            long upper = readBound(model, mulRes, upperBoundProp, valueProp, -1);

            // Check if property has a defaultValue
            boolean hasDefault = prop.getProperty(defaultValueProp) != null;

            result.computeIfAbsent(ownerClassName, k -> new ArrayList<>())
                    .add(new PropertyInfo(propName, rdfPredicateName, ownerClassName, lower, upper, hasDefault));
        }

        return result;
    }

    /**
     * Build a map from class name → set of valid RDF predicate names (property resource local names).
     * In RDF, the predicate URI on an instance must match a property resource URI.
     * For example, if :foo is a Property with :owner :Bar, then instances of Bar can use :foo as a predicate.
     */
    private Map<String, Set<String>> buildPredicateMap(Model model)
    {
        Map<String, Set<String>> result = new HashMap<>();
        Resource propertyType = model.getResource(M3_NS + "Property");
        Property ownerProp = model.getProperty(M3_NS + "owner");

        ResIterator propInstances = model.listSubjectsWithProperty(RDF.type, propertyType);
        while (propInstances.hasNext())
        {
            Resource prop = propInstances.next();

            // The valid RDF predicate is the property resource's own URI
            String predicateName = prop.isURIResource() ? localName(prop.getURI()) : null;
            if (predicateName == null)
            {
                continue;
            }

            // Get owner class
            Statement ownerStmt = prop.getProperty(ownerProp);
            if (ownerStmt == null || !ownerStmt.getObject().isURIResource())
            {
                continue;
            }
            String ownerClassName = localName(ownerStmt.getObject().asResource().getURI());

            result.computeIfAbsent(ownerClassName, k -> new HashSet<>()).add(predicateName);
        }

        return result;
    }

    /**
     * Recursively collect all valid predicates for a class (own + inherited).
     */
    private Set<String> getAllPredicates(
            String className,
            Map<String, Set<String>> declaredPredicates,
            Map<String, Set<String>> generalizations,
            Set<String> visited)
    {
        if (visited.contains(className))
        {
            return Set.of();
        }
        visited.add(className);

        Set<String> allPredicates = new HashSet<>();

        Set<String> own = declaredPredicates.get(className);
        if (own != null)
        {
            allPredicates.addAll(own);
        }

        Set<String> supers = generalizations.get(className);
        if (supers != null)
        {
            for (String superName : supers)
            {
                allPredicates.addAll(getAllPredicates(superName, declaredPredicates, generalizations, visited));
            }
        }

        return allPredicates;
    }

    /**
     * Read a bound value (lower or upper) from a multiplicity instance.
     */
    private long readBound(Model model, Resource mulRes, Property boundProp, Property valueProp, long defaultValue)
    {
        Statement boundStmt = mulRes.getProperty(boundProp);
        if (boundStmt == null || !boundStmt.getObject().isResource())
        {
            return defaultValue;
        }
        Resource boundBlank = boundStmt.getObject().asResource();
        Statement valueStmt = boundBlank.getProperty(valueProp);
        if (valueStmt == null || !valueStmt.getObject().isLiteral())
        {
            return defaultValue;
        }
        return valueStmt.getLong();
    }

    /**
     * Recursively collect all properties for a class (declared + inherited).
     */
    private List<PropertyInfo> getAllProperties(
            String className,
            Map<String, List<PropertyInfo>> declaredProps,
            Map<String, Set<String>> generalizations,
            Set<String> visited)
    {
        if (visited.contains(className))
        {
            return List.of();
        }
        visited.add(className);

        List<PropertyInfo> allProps = new ArrayList<>();

        // Own declared properties
        List<PropertyInfo> own = declaredProps.get(className);
        if (own != null)
        {
            allProps.addAll(own);
        }

        // Inherited properties
        Set<String> supers = generalizations.get(className);
        if (supers != null)
        {
            for (String superName : supers)
            {
                allProps.addAll(getAllProperties(superName, declaredProps, generalizations, visited));
            }
        }

        return allProps;
    }

    /**
     * Get required properties (lower bound >= 1) for a given type.
     */
    private List<PropertyInfo> getRequiredProperties(String typeName, Map<String, List<PropertyInfo>> allProperties)
    {
        List<PropertyInfo> props = allProperties.get(typeName);
        if (props == null)
        {
            return List.of();
        }
        return props.stream().filter(p -> p.lowerBound() >= 1 && !p.hasDefaultValue()).toList();
    }

    /**
     * Get a human-readable name for an instance (URI local name or blank node description).
     */
    private String getInstanceName(Model model, Resource instance)
    {
        if (instance.isURIResource())
        {
            return localName(instance.getURI());
        }
        // For blank nodes, try to get the :name property
        Property nameProp = model.getProperty(M3_NS + "name");
        Statement nameStmt = instance.getProperty(nameProp);
        if (nameStmt != null && nameStmt.getObject().isLiteral())
        {
            return "[blank:" + nameStmt.getString() + "]";
        }
        return "[blank:" + instance.getId() + "]";
    }

    private Model loadModel(String resourceName)
    {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName);
        if (is == null)
        {
            throw new RuntimeException("Resource not found: " + resourceName);
        }
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, is, Lang.TURTLE);
        return model;
    }

    private String localName(String uri)
    {
        if (uri == null)
        {
            return null;
        }
        if (uri.contains("#"))
        {
            return uri.substring(uri.lastIndexOf('#') + 1);
        }
        if (uri.contains("/"))
        {
            return uri.substring(uri.lastIndexOf('/') + 1);
        }
        return uri;
    }
}
