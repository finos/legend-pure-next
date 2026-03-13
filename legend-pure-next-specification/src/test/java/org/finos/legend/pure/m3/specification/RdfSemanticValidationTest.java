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
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generic RDF semantic validation tests.
 *
 * <p>
 * This test validates RDF/TTL files for common semantic issues:
 * <ul>
 * <li>Duplicate predicate annotations on the same subject</li>
 * <li>References to undefined resources</li>
 * <li>Dangling object references</li>
 * </ul>
 */
public class RdfSemanticValidationTest {
    private static final String M3_TTL = "specification/m3.ttl";

    @Test
    public void testM3TtlNoDuplicatePredicates() {
        Model model = loadModel(M3_TTL);
        List<String> errors = checkDuplicatePredicates(model);

        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("Duplicate predicate errors:\n");
            for (String error : errors) {
                sb.append("  - ").append(error).append("\n");
            }
            Assertions.fail(sb.toString());
        }
    }

    @Test
    public void testM3TtlNoUndefinedReferences() {
        Model model = loadModel(M3_TTL);
        List<String> errors = checkUndefinedReferences(model);

        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("Undefined reference errors:\n");
            for (String error : errors) {
                sb.append("  - ").append(error).append("\n");
            }
            Assertions.fail(sb.toString());
        }
    }

    /**
     * Check for duplicate predicates on the same subject.
     * Some predicates (like rdf:type) are allowed to have multiple values,
     * but custom predicates should typically be unique.
     *
     * @param model the RDF model to validate
     * @return list of validation error messages
     */
    private List<String> checkDuplicatePredicates(Model model) {
        List<String> errors = new ArrayList<>();

        // Track predicate counts per subject
        Map<String, Map<String, Integer>> subjectPredicateCounts = new HashMap<>();

        StmtIterator stmtIt = model.listStatements();
        while (stmtIt.hasNext()) {
            Statement stmt = stmtIt.next();
            String subjectUri = stmt.getSubject().getURI();
            if (subjectUri == null) {
                continue; // Skip blank nodes
            }
            String predicateUri = stmt.getPredicate().getURI();

            subjectPredicateCounts
                    .computeIfAbsent(subjectUri, k -> new HashMap<>())
                    .merge(predicateUri, 1, Integer::sum);
        }

        // Check for duplicates (excluding common multi-valued predicates)
        Set<String> allowedMultiValuePredicates = new HashSet<>();
        allowedMultiValuePredicates.add(
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        allowedMultiValuePredicates.add(
                "https://finos.org/legend/pure/m3#generalizations");
        allowedMultiValuePredicates.add(
                "https://finos.org/legend/pure/m3#stereotypes");

        for (Map.Entry<String, Map<String, Integer>> subjectEntry : subjectPredicateCounts.entrySet()) {
            String subjectUri = subjectEntry.getKey();
            for (Map.Entry<String, Integer> predEntry : subjectEntry.getValue().entrySet()) {
                String predicateUri = predEntry.getKey();
                int count = predEntry.getValue();

                if (count > 1 && !allowedMultiValuePredicates.contains(predicateUri)) {
                    errors.add("Subject '" + getLocalName(subjectUri)
                            + "' has duplicate predicate '" + getLocalName(predicateUri)
                            + "' (" + count + " occurrences)");
                }
            }
        }

        return errors;
    }

    /**
     * Check for references to undefined resources.
     * Any URI-based object reference should have a corresponding subject
     * definition.
     *
     * @param model the RDF model to validate
     * @return list of validation error messages
     */
    private List<String> checkUndefinedReferences(Model model) {
        List<String> errors = new ArrayList<>();
        String m3Ns = "https://finos.org/legend/pure/m3#";

        // Collect all defined subjects
        Set<String> definedSubjects = new HashSet<>();
        StmtIterator stmtIt = model.listStatements();
        while (stmtIt.hasNext()) {
            Statement stmt = stmtIt.next();
            String subjectUri = stmt.getSubject().getURI();
            if (subjectUri != null) {
                definedSubjects.add(subjectUri);
            }
        }

        // Check object references
        stmtIt = model.listStatements();
        while (stmtIt.hasNext()) {
            Statement stmt = stmtIt.next();
            RDFNode object = stmt.getObject();

            if (object.isURIResource()) {
                Resource objRes = object.asResource();
                String objUri = objRes.getURI();

                // Only check M3 namespace references
                if (objUri != null && objUri.startsWith(m3Ns)) {
                    // Skip rdf:type predicate
                    Property predicate = stmt.getPredicate();
                    if (!predicate.getURI().equals(
                            "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")) {
                        if (!definedSubjects.contains(objUri)) {
                            errors.add("Subject '" + getLocalName(stmt.getSubject().getURI())
                                    + "' references undefined resource: "
                                    + getLocalName(objUri));
                        }
                    }
                }
            }
        }

        return errors;
    }

    private Model loadModel(String resourceName) {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName);
        if (is == null) {
            throw new RuntimeException("Resource not found: " + resourceName);
        }
        Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, is, org.apache.jena.riot.Lang.TURTLE);
        return model;
    }

    private String getLocalName(String uri) {
        if (uri == null) {
            return null;
        }
        if (uri.contains("#")) {
            return uri.substring(uri.lastIndexOf('#') + 1);
        }
        if (uri.contains("/")) {
            return uri.substring(uri.lastIndexOf('/') + 1);
        }
        return uri;
    }
}
