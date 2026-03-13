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

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.SortedMaps;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.sorted.MutableSortedMap;
import org.finos.legend.pure.specification.generation.model.ClassInfo;
import org.finos.legend.pure.specification.generation.model.EnumInfo;
import org.finos.legend.pure.specification.generation.model.M3MetamodelReader;
import org.finos.legend.pure.specification.generation.model.M3Model;
import org.finos.legend.pure.specification.generation.model.ProfileInfo;
import org.finos.legend.pure.specification.generation.model.PropertyInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class RdfPureGenerator
{
    public static void main(String[] args) throws Exception
    {
        if (args.length != 2)
        {
            System.err.println("Usage: RdfPureGenerator <inputFile.ttl> <outputFile.pure>");
            System.exit(1);
        }
        String inputFile = args[0];
        String sourceName = Path.of(inputFile).getFileName().toString();
        M3Model model = new M3MetamodelReader(inputFile).read();
        new RdfPureGenerator(model, sourceName).generate(Path.of(args[1]));
    }

    private final M3Model m3Model;
    private final String sourceName;

    public RdfPureGenerator(M3Model m3Model)
    {
        this(m3Model, "m3.ttl");
    }

    public RdfPureGenerator(M3Model m3Model, String sourceName)
    {
        this.m3Model = m3Model;
        this.sourceName = sourceName;
    }

    public void generate(Path outputPath) throws IOException
    {
        String content = generatePureContent();
        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, content.getBytes(StandardCharsets.UTF_8));
    }

    private String generatePureContent()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("// AUTO-GENERATED from ").append(sourceName).append(" - DO NOT EDIT\n\n");

        if (!m3Model.profileInfoMap().isEmpty())
        {
            sb.append("// --- Profiles ---\n\n");
            m3Model.profileInfoMap().valuesView().forEach(pi ->
            {
                generateProfile(sb, pi);
                sb.append("\n");
            });
        }

        MutableSortedMap<String, MutableList<ClassInfo>> classesByPackage = SortedMaps.mutable.empty();
        m3Model.classInfoMap().valuesView().forEach(ci ->
        {
            String pkg = ci.packagePath != null ? ci.packagePath : "";
            classesByPackage.getIfAbsentPut(pkg, Lists.mutable::empty).add(ci);
        });

        MutableSortedMap<String, MutableList<EnumInfo>> enumsByPackage = SortedMaps.mutable.empty();
        m3Model.enumInfoMap().valuesView().forEach(ei ->
        {
            String pkg = ei.packagePath != null ? ei.packagePath : "";
            enumsByPackage.getIfAbsentPut(pkg, Lists.mutable::empty).add(ei);
        });

        MutableSortedMap<String, Object> allPackages = SortedMaps.mutable.empty();
        allPackages.putAll(classesByPackage);
        enumsByPackage.keysView().forEach(pkg -> allPackages.putIfAbsent(pkg, null));

        boolean[] first = {true};
        allPackages.keysView().forEach(pkg ->
        {
            if (!first[0])
            {
                sb.append("\n");
            }
            first[0] = false;

            if (!pkg.isEmpty())
            {
                sb.append("// --- ").append(pkg).append(" ---\n\n");
            }

            MutableList<EnumInfo> enums = enumsByPackage.getIfAbsentValue(pkg, Lists.mutable.empty());
            enums.forEach(ei ->
            {
                generateEnum(sb, ei);
                sb.append("\n");
            });

            MutableList<ClassInfo> classes = classesByPackage.getIfAbsentValue(pkg, Lists.mutable.empty());
            classes.forEach(ci ->
            {
                generateClass(sb, ci);
                sb.append("\n");
            });
        });

        return sb.toString();
    }

    private void generateProfile(StringBuilder sb, ProfileInfo profileInfo)
    {
        String fqn = buildFqn(profileInfo.packagePath, profileInfo.name);
        sb.append("Profile ").append(fqn).append("\n{\n");
        if (!profileInfo.stereotypes.isEmpty())
        {
            sb.append("  stereotypes: [");
            sb.append(profileInfo.stereotypes.makeString(", "));
            sb.append("];\n");
        }
        if (!profileInfo.tags.isEmpty())
        {
            sb.append("  tags: [");
            sb.append(profileInfo.tags.makeString(", "));
            sb.append("];\n");
        }
        sb.append("}\n");
    }

    private void generateClass(StringBuilder sb, ClassInfo classInfo)
    {
        String fqn = buildFqn(classInfo.packagePath, classInfo.name);
        if (!classInfo.stereotypes.isEmpty())
        {
            classInfo.stereotypes.forEach(st -> sb.append("<<").append(st).append(">>"));
            sb.append("\n");
        }
        if (!classInfo.taggedValues.isEmpty())
        {
            classInfo.taggedValues.forEach(tv ->
                    sb.append("{").append(tv.tag).append(" = '").append(tv.value).append("'}"));
            sb.append("\n");
        }
        sb.append("Class ").append(fqn);
        if (!classInfo.typeParameters.isEmpty() || !classInfo.multiplicityParameters.isEmpty())
        {
            sb.append("<");
            sb.append(classInfo.typeParameters.makeString(", "));
            if (!classInfo.multiplicityParameters.isEmpty())
            {
                sb.append("|");
                sb.append(classInfo.multiplicityParameters.makeString(", "));
            }
            sb.append(">");
        }
        MutableList<String> nonAnyGeneralizations = classInfo.fullGeneralizations.reject("Any"::equals);
        if (!nonAnyGeneralizations.isEmpty())
        {
            sb.append(" extends ");
            sb.append(nonAnyGeneralizations.makeString(", "));
        }
        sb.append("\n{\n");
        MutableList<PropertyInfo> props = m3Model.propertiesByOwner().getIfAbsentValue(classInfo.name, Lists.mutable.empty());
        props.forEach(prop ->
        {
            sb.append("  ");
            if (!prop.stereotypes.isEmpty())
            {
                prop.stereotypes.forEach(st -> sb.append("<<").append(st).append(">>"));
                sb.append(" ");
            }
            if (!prop.taggedValues.isEmpty())
            {
                prop.taggedValues.forEach(tv ->
                        sb.append("{").append(tv.tag).append(" = '").append(tv.value).append("'}"));
                sb.append(" ");
            }
            sb.append(prop.name).append(": ");
            sb.append(prop.fullTypeName);
            sb.append(mapMultiplicity(prop.multiplicity));
            sb.append(";\n");
        });
        sb.append("}\n");
    }

    private void generateEnum(StringBuilder sb, EnumInfo enumInfo)
    {
        String fqn = buildFqn(enumInfo.packagePath, enumInfo.name);
        sb.append("Enum ").append(fqn).append("\n{\n");
        for (int i = 0; i < enumInfo.values.size(); i++)
        {
            sb.append("  ").append(enumInfo.values.get(i));
            sb.append(i < enumInfo.values.size() - 1 ? "," : "");
            sb.append("\n");
        }
        sb.append("}\n");
    }

    private String buildFqn(String packagePath, String name)
    {
        if (packagePath == null || packagePath.isEmpty())
        {
            return name;
        }
        return packagePath + "::" + name;
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
