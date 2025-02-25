/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.tools.plugin.extractor.annotations;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.library.SortedClassLibraryBuilder;
import com.thoughtworks.qdox.model.DocletTag;
import com.thoughtworks.qdox.model.JavaAnnotatedElement;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaField;
import com.thoughtworks.qdox.model.JavaMember;
import com.thoughtworks.qdox.model.JavaMethod;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.plugin.descriptor.InvalidParameterException;
import org.apache.maven.plugin.descriptor.InvalidPluginDescriptorException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.descriptor.Requirement;
import org.apache.maven.project.MavenProject;
import org.apache.maven.tools.plugin.ExtendedMojoDescriptor;
import org.apache.maven.tools.plugin.PluginToolsRequest;
import org.apache.maven.tools.plugin.extractor.ExtractionException;
import org.apache.maven.tools.plugin.extractor.GroupKey;
import org.apache.maven.tools.plugin.extractor.MojoDescriptorExtractor;
import org.apache.maven.tools.plugin.extractor.annotations.converter.ConverterContext;
import org.apache.maven.tools.plugin.extractor.annotations.converter.JavaClassConverterContext;
import org.apache.maven.tools.plugin.extractor.annotations.converter.JavadocBlockTagsToXhtmlConverter;
import org.apache.maven.tools.plugin.extractor.annotations.converter.JavadocInlineTagsToXhtmlConverter;
import org.apache.maven.tools.plugin.extractor.annotations.datamodel.ComponentAnnotationContent;
import org.apache.maven.tools.plugin.extractor.annotations.datamodel.ExecuteAnnotationContent;
import org.apache.maven.tools.plugin.extractor.annotations.datamodel.MojoAnnotationContent;
import org.apache.maven.tools.plugin.extractor.annotations.datamodel.ParameterAnnotationContent;
import org.apache.maven.tools.plugin.extractor.annotations.scanner.MojoAnnotatedClass;
import org.apache.maven.tools.plugin.extractor.annotations.scanner.MojoAnnotationsScanner;
import org.apache.maven.tools.plugin.extractor.annotations.scanner.MojoAnnotationsScannerRequest;
import org.apache.maven.tools.plugin.javadoc.JavadocLinkGenerator;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaMojoDescriptorExtractor, a MojoDescriptor extractor to read descriptors from java classes with annotations.
 * Notice that source files are also parsed to get description, since and deprecation information.
 *
 * @author Olivier Lamy
 * @since 3.0
 */
@Named(JavaAnnotationsMojoDescriptorExtractor.NAME)
@Singleton
public class JavaAnnotationsMojoDescriptorExtractor implements MojoDescriptorExtractor {
    private static final Logger LOGGER = LoggerFactory.getLogger(JavaAnnotationsMojoDescriptorExtractor.class);
    public static final String NAME = "java-annotations";

    private static final GroupKey GROUP_KEY = new GroupKey(GroupKey.JAVA_GROUP, 100);

    /**
     *
     * @see <a href="https://docs.oracle.com/javase/specs/jvms/se19/html/jvms-4.html#jvms-4.1">JVMS 4.1</a>
     */
    private static final Map<Integer, String> CLASS_VERSION_TO_JAVA_STRING;

    static {
        CLASS_VERSION_TO_JAVA_STRING = new HashMap<>();
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V1_1, "1.1");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V1_2, "1.2");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V1_3, "1.3");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V1_4, "1.4");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V1_5, "1.5");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V1_6, "1.6");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V1_7, "1.7");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V1_8, "1.8");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V9, "9");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V10, "10");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V11, "11");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V12, "12");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V13, "13");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V14, "14");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V15, "15");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V16, "16");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V17, "17");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V18, "18");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V19, "19");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V20, "20");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V21, "21");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V22, "22");
        CLASS_VERSION_TO_JAVA_STRING.put(Opcodes.V23, "23");
    }

    @Inject
    MojoAnnotationsScanner mojoAnnotationsScanner;

    @Inject
    private RepositorySystem repositorySystem;

    @Inject
    private ArchiverManager archiverManager;

    @Inject
    private JavadocInlineTagsToXhtmlConverter javadocInlineTagsToHtmlConverter;

    @Inject
    private JavadocBlockTagsToXhtmlConverter javadocBlockTagsToHtmlConverter;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isDeprecated() {
        return false; // this is the "current way" to write Java Mojos
    }

    @Override
    public GroupKey getGroupKey() {
        return GROUP_KEY;
    }

    /**
     * Compares class file format versions.
     * @see <a href="https://docs.oracle.com/javase/specs/jvms/se19/html/jvms-4.html#jvms-4.1">JVMS 4.1</a>
     *
     */
    @SuppressWarnings("checkstyle:magicnumber")
    static final class ClassVersionComparator implements Comparator<Integer> {
        @Override
        public int compare(Integer classVersion1, Integer classVersion2) {
            // first compare major version (
            int result = Integer.compare(classVersion1 & 0x00FF, classVersion2 & 0x00FF);
            if (result == 0) {
                // compare minor version if major is equal
                result = Integer.compare(classVersion1, classVersion2);
            }
            return result;
        }
    }

    @Override
    public List<MojoDescriptor> execute(PluginToolsRequest request)
            throws ExtractionException, InvalidPluginDescriptorException {
        Map<String, MojoAnnotatedClass> mojoAnnotatedClasses = scanAnnotations(request);

        Optional<Integer> maxClassVersion = mojoAnnotatedClasses.values().stream()
                .map(MojoAnnotatedClass::getClassVersion)
                .max(new ClassVersionComparator());
        if (maxClassVersion.isPresent()) {
            String requiredJavaVersion = CLASS_VERSION_TO_JAVA_STRING.get(maxClassVersion.get());
            if (StringUtils.isBlank(request.getRequiredJavaVersion())
                    || new ComparableVersion(request.getRequiredJavaVersion())
                                    .compareTo(new ComparableVersion(requiredJavaVersion))
                            < 0) {
                request.setRequiredJavaVersion(requiredJavaVersion);
            }
        }
        JavaProjectBuilder builder = scanJavadoc(request, mojoAnnotatedClasses.values());
        Map<String, JavaClass> javaClassesMap = discoverClasses(builder);

        final JavadocLinkGenerator linkGenerator;
        if (request.getInternalJavadocBaseUrl() != null
                || (request.getExternalJavadocBaseUrls() != null
                        && !request.getExternalJavadocBaseUrls().isEmpty())) {
            linkGenerator = new JavadocLinkGenerator(
                    request.getInternalJavadocBaseUrl(),
                    request.getInternalJavadocVersion(),
                    request.getExternalJavadocBaseUrls(),
                    request.getSettings());
        } else {
            linkGenerator = null;
        }

        populateDataFromJavadoc(builder, mojoAnnotatedClasses, javaClassesMap, linkGenerator);

        return toMojoDescriptors(mojoAnnotatedClasses, request.getPluginDescriptor());
    }

    private Map<String, MojoAnnotatedClass> scanAnnotations(PluginToolsRequest request) throws ExtractionException {
        MojoAnnotationsScannerRequest mojoAnnotationsScannerRequest = new MojoAnnotationsScannerRequest();

        File output = new File(request.getProject().getBuild().getOutputDirectory());
        mojoAnnotationsScannerRequest.setClassesDirectories(Arrays.asList(output));

        mojoAnnotationsScannerRequest.setDependencies(request.getDependencies());

        mojoAnnotationsScannerRequest.setProject(request.getProject());

        Map<String, MojoAnnotatedClass> result = mojoAnnotationsScanner.scan(mojoAnnotationsScannerRequest);
        request.setUsedMavenApiVersion(mojoAnnotationsScannerRequest.getMavenApiVersion());
        return result;
    }

    private JavaProjectBuilder scanJavadoc(
            PluginToolsRequest request, Collection<MojoAnnotatedClass> mojoAnnotatedClasses)
            throws ExtractionException {
        // found artifact from reactors to scan sources
        // we currently only scan sources from reactors
        List<MavenProject> mavenProjects = new ArrayList<>();

        // if we need to scan sources from external artifacts
        Set<Artifact> externalArtifacts = new HashSet<>();

        JavaProjectBuilder builder = new JavaProjectBuilder(new SortedClassLibraryBuilder());
        builder.setEncoding(request.getEncoding());
        extendJavaProjectBuilder(builder, request.getProject());

        for (MojoAnnotatedClass mojoAnnotatedClass : mojoAnnotatedClasses) {
            if (Objects.equals(
                    mojoAnnotatedClass.getArtifact().getArtifactId(),
                    request.getProject().getArtifact().getArtifactId())) {
                continue;
            }

            if (!isMojoAnnnotatedClassCandidate(mojoAnnotatedClass)) {
                // we don't scan sources for classes without mojo annotations
                continue;
            }

            MavenProject mavenProject =
                    getFromProjectReferences(mojoAnnotatedClass.getArtifact(), request.getProject());

            if (mavenProject != null) {
                mavenProjects.add(mavenProject);
            } else {
                externalArtifacts.add(mojoAnnotatedClass.getArtifact());
            }
        }

        // try to get artifact with sources classifier, extract somewhere then scan for @since, @deprecated
        for (Artifact artifact : externalArtifacts) {
            // parameter for test-sources too ?? olamy I need that for it test only
            if (StringUtils.equalsIgnoreCase("tests", artifact.getClassifier())) {
                extendJavaProjectBuilderWithSourcesJar(builder, artifact, request, "test-sources");
            } else {
                extendJavaProjectBuilderWithSourcesJar(builder, artifact, request, "sources");
            }
        }

        for (MavenProject mavenProject : mavenProjects) {
            extendJavaProjectBuilder(builder, mavenProject);
        }

        return builder;
    }

    private boolean isMojoAnnnotatedClassCandidate(MojoAnnotatedClass mojoAnnotatedClass) {
        return mojoAnnotatedClass != null && mojoAnnotatedClass.hasAnnotations();
    }

    /**
     * from sources scan to get @since and @deprecated and description of classes and fields.
     */
    protected void populateDataFromJavadoc(
            JavaProjectBuilder javaProjectBuilder,
            Map<String, MojoAnnotatedClass> mojoAnnotatedClasses,
            Map<String, JavaClass> javaClassesMap,
            JavadocLinkGenerator linkGenerator) {

        for (Map.Entry<String, MojoAnnotatedClass> entry : mojoAnnotatedClasses.entrySet()) {
            JavaClass javaClass = javaClassesMap.get(entry.getKey());
            if (javaClass == null) {
                continue;
            }
            // populate class-level content
            MojoAnnotationContent mojoAnnotationContent = entry.getValue().getMojo();
            if (mojoAnnotationContent != null) {
                JavaClassConverterContext context = new JavaClassConverterContext(
                        javaClass, javaProjectBuilder, mojoAnnotatedClasses, linkGenerator, javaClass.getLineNumber());
                mojoAnnotationContent.setDescription(getDescriptionFromElement(javaClass, context));

                DocletTag since = findInClassHierarchy(javaClass, "since");
                if (since != null) {
                    mojoAnnotationContent.setSince(getRawValueFromTaglet(since, context));
                }

                DocletTag deprecated = findInClassHierarchy(javaClass, "deprecated");
                if (deprecated != null) {
                    mojoAnnotationContent.setDeprecated(getRawValueFromTaglet(deprecated, context));
                }
            }

            Map<String, JavaAnnotatedElement> fieldsMap = extractFieldsAnnotations(javaClass, javaClassesMap);
            Map<String, JavaAnnotatedElement> methodsMap = extractMethodsAnnotations(javaClass, javaClassesMap);

            // populate parameters
            Map<String, ParameterAnnotationContent> parameters =
                    getParametersParentHierarchy(entry.getValue(), mojoAnnotatedClasses);
            parameters = new TreeMap<>(parameters);
            for (Map.Entry<String, ParameterAnnotationContent> parameter : parameters.entrySet()) {
                JavaAnnotatedElement element;
                if (parameter.getValue().isAnnotationOnMethod()) {
                    element = methodsMap.get(parameter.getKey());
                } else {
                    element = fieldsMap.get(parameter.getKey());
                }

                if (element == null) {
                    continue;
                }

                JavaClassConverterContext context = new JavaClassConverterContext(
                        javaClass, ((JavaMember) element).getDeclaringClass(),
                        javaProjectBuilder, mojoAnnotatedClasses,
                        linkGenerator, element.getLineNumber());
                ParameterAnnotationContent parameterAnnotationContent = parameter.getValue();
                parameterAnnotationContent.setDescription(getDescriptionFromElement(element, context));

                DocletTag deprecated = element.getTagByName("deprecated");
                if (deprecated != null) {
                    parameterAnnotationContent.setDeprecated(getRawValueFromTaglet(deprecated, context));
                }

                DocletTag since = element.getTagByName("since");
                if (since != null) {
                    parameterAnnotationContent.setSince(getRawValueFromTaglet(since, context));
                }
            }

            // populate components
            Map<String, ComponentAnnotationContent> components =
                    entry.getValue().getComponents();
            for (Map.Entry<String, ComponentAnnotationContent> component : components.entrySet()) {
                JavaAnnotatedElement element = fieldsMap.get(component.getKey());
                if (element == null) {
                    continue;
                }

                JavaClassConverterContext context = new JavaClassConverterContext(
                        javaClass, ((JavaMember) element).getDeclaringClass(),
                        javaProjectBuilder, mojoAnnotatedClasses,
                        linkGenerator, javaClass.getLineNumber());
                ComponentAnnotationContent componentAnnotationContent = component.getValue();
                componentAnnotationContent.setDescription(getDescriptionFromElement(element, context));

                DocletTag deprecated = element.getTagByName("deprecated");
                if (deprecated != null) {
                    componentAnnotationContent.setDeprecated(getRawValueFromTaglet(deprecated, context));
                }

                DocletTag since = element.getTagByName("since");
                if (since != null) {
                    componentAnnotationContent.setSince(getRawValueFromTaglet(since, context));
                }
            }
        }
    }

    /**
     * Returns the XHTML description from the given element.
     * This may refer to either goal, parameter or component.
     * @param element the element for which to generate the description
     * @param context the context with which to call the converter
     * @return the generated description
     */
    String getDescriptionFromElement(JavaAnnotatedElement element, JavaClassConverterContext context) {

        String comment = element.getComment();
        if (comment == null) {
            return null;
        }
        StringBuilder description = new StringBuilder(javadocInlineTagsToHtmlConverter.convert(comment, context));
        for (DocletTag docletTag : element.getTags()) {
            // also consider see block tags
            if ("see".equals(docletTag.getName())) {
                description.append(javadocBlockTagsToHtmlConverter.convert(docletTag, context));
            }
        }
        return description.toString();
    }

    String getRawValueFromTaglet(DocletTag docletTag, ConverterContext context) {
        // just resolve inline tags and convert to XHTML
        return javadocInlineTagsToHtmlConverter.convert(docletTag.getValue(), context);
    }

    /**
     * @param javaClass not null
     * @param tagName   not null
     * @return docletTag instance
     */
    private DocletTag findInClassHierarchy(JavaClass javaClass, String tagName) {
        try {
            DocletTag tag = javaClass.getTagByName(tagName);

            if (tag == null) {
                JavaClass superClass = javaClass.getSuperJavaClass();

                if (superClass != null) {
                    tag = findInClassHierarchy(superClass, tagName);
                }
            }

            return tag;
        } catch (NoClassDefFoundError e) {
            if (e.getMessage().replace('/', '.').contains(MojoAnnotationsScanner.V4_API_PLUGIN_PACKAGE)) {
                return null;
            }
            String str;
            try {
                str = javaClass.getFullyQualifiedName();
            } catch (Throwable t) {
                str = javaClass.getValue();
            }
            LOGGER.warn("Failed extracting tag '" + tagName + "' from class " + str);
            throw (NoClassDefFoundError) new NoClassDefFoundError(e.getMessage()).initCause(e);
        }
    }

    /**
     * extract fields that are either parameters or components.
     *
     * @param javaClass not null
     * @return map with Mojo parameters names as keys
     */
    private Map<String, JavaAnnotatedElement> extractFieldsAnnotations(
            JavaClass javaClass, Map<String, JavaClass> javaClassesMap) {
        try {
            Map<String, JavaAnnotatedElement> rawParams = new TreeMap<>();

            // we have to add the parent fields first, so that they will be overwritten by the local fields if
            // that actually happens...
            JavaClass superClass = javaClass.getSuperJavaClass();

            if (superClass != null) {
                if (!superClass.getFields().isEmpty()) {
                    rawParams = extractFieldsAnnotations(superClass, javaClassesMap);
                }
                // maybe sources comes from scan of sources artifact
                superClass = javaClassesMap.get(superClass.getFullyQualifiedName());
                if (superClass != null && !superClass.getFields().isEmpty()) {
                    rawParams = extractFieldsAnnotations(superClass, javaClassesMap);
                }
            } else {

                rawParams = new TreeMap<>();
            }

            for (JavaField field : javaClass.getFields()) {
                rawParams.put(field.getName(), field);
            }

            return rawParams;
        } catch (NoClassDefFoundError e) {
            LOGGER.warn("Failed extracting parameters from " + javaClass);
            throw e;
        }
    }

    /**
     * extract methods that are parameters.
     *
     * @param javaClass not null
     * @return map with Mojo parameters names as keys
     */
    private Map<String, JavaAnnotatedElement> extractMethodsAnnotations(
            JavaClass javaClass, Map<String, JavaClass> javaClassesMap) {
        try {
            Map<String, JavaAnnotatedElement> rawParams = new TreeMap<>();

            // we have to add the parent methods first, so that they will be overwritten by the local methods if
            // that actually happens...
            JavaClass superClass = javaClass.getSuperJavaClass();

            if (superClass != null) {
                if (!superClass.getMethods().isEmpty()) {
                    rawParams = extractMethodsAnnotations(superClass, javaClassesMap);
                }
                // maybe sources comes from scan of sources artifact
                superClass = javaClassesMap.get(superClass.getFullyQualifiedName());
                if (superClass != null && !superClass.getMethods().isEmpty()) {
                    rawParams = extractMethodsAnnotations(superClass, javaClassesMap);
                }
            } else {

                rawParams = new TreeMap<>();
            }

            for (JavaMethod method : javaClass.getMethods()) {
                if (isPublicSetterMethod(method)) {
                    rawParams.put(
                            StringUtils.lowercaseFirstLetter(method.getName().substring(3)), method);
                }
            }

            return rawParams;
        } catch (NoClassDefFoundError e) {
            if (e.getMessage().replace('/', '.').contains(MojoAnnotationsScanner.V4_API_PLUGIN_PACKAGE)) {
                return new TreeMap<>();
            }
            String str;
            try {
                str = javaClass.getFullyQualifiedName();
            } catch (Throwable t) {
                str = javaClass.getValue();
            }
            LOGGER.warn("Failed extracting methods from " + str);
            throw (NoClassDefFoundError) new NoClassDefFoundError(e.getMessage()).initCause(e);
        }
    }

    private boolean isPublicSetterMethod(JavaMethod method) {
        return method.isPublic()
                && !method.isStatic()
                && method.getName().length() > 3
                && (method.getName().startsWith("add") || method.getName().startsWith("set"))
                && "void".equals(method.getReturnType().getValue())
                && method.getParameters().size() == 1;
    }

    protected Map<String, JavaClass> discoverClasses(JavaProjectBuilder builder) {
        Collection<JavaClass> javaClasses = builder.getClasses();

        if (javaClasses == null || javaClasses.size() < 1) {
            return Collections.emptyMap();
        }

        Map<String, JavaClass> javaClassMap = new HashMap<>(javaClasses.size());

        for (JavaClass javaClass : javaClasses) {
            javaClassMap.put(javaClass.getFullyQualifiedName(), javaClass);
        }

        return javaClassMap;
    }

    protected void extendJavaProjectBuilderWithSourcesJar(
            JavaProjectBuilder builder, Artifact artifact, PluginToolsRequest request, String classifier)
            throws ExtractionException {
        try {
            org.eclipse.aether.artifact.Artifact sourcesArtifact = new DefaultArtifact(
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    classifier,
                    artifact.getArtifactHandler().getExtension(),
                    artifact.getVersion());

            ArtifactRequest resolveRequest =
                    new ArtifactRequest(sourcesArtifact, request.getProject().getRemoteProjectRepositories(), null);
            try {
                ArtifactResult result = repositorySystem.resolveArtifact(request.getRepoSession(), resolveRequest);
                sourcesArtifact = result.getArtifact();
            } catch (ArtifactResolutionException e) {
                String message = "Unable to get sources artifact for " + artifact.getId()
                        + ". Some javadoc tags (@since, @deprecated and comments) won't be used";
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.warn(message, e);
                } else {
                    LOGGER.warn(message);
                }
                return;
            }

            if (sourcesArtifact.getFile() == null || !sourcesArtifact.getFile().exists()) {
                // could not get artifact sources
                return;
            }

            if (sourcesArtifact.getFile().isFile()) {
                // extract sources to target/maven-plugin-plugin-sources/${groupId}/${artifact}/sources
                File extractDirectory = new File(
                        request.getProject().getBuild().getDirectory(),
                        "maven-plugin-plugin-sources/" + sourcesArtifact.getGroupId() + "/"
                                + sourcesArtifact.getArtifactId() + "/" + sourcesArtifact.getVersion()
                                + "/" + sourcesArtifact.getClassifier());
                extractDirectory.mkdirs();

                UnArchiver unArchiver = archiverManager.getUnArchiver("jar");
                unArchiver.setSourceFile(sourcesArtifact.getFile());
                unArchiver.setDestDirectory(extractDirectory);
                unArchiver.extract();

                extendJavaProjectBuilder(builder, Arrays.asList(extractDirectory), request.getDependencies());
            } else if (sourcesArtifact.getFile().isDirectory()) {
                extendJavaProjectBuilder(builder, Arrays.asList(sourcesArtifact.getFile()), request.getDependencies());
            }
        } catch (ArchiverException | NoSuchArchiverException e) {
            throw new ExtractionException(e.getMessage(), e);
        }
    }

    private void extendJavaProjectBuilder(JavaProjectBuilder builder, final MavenProject project) {
        List<File> sources = new ArrayList<>();

        for (String source : project.getCompileSourceRoots()) {
            sources.add(new File(source));
        }

        // TODO be more dynamic
        File generatedPlugin = new File(project.getBasedir(), "target/generated-sources/plugin");
        if (!project.getCompileSourceRoots().contains(generatedPlugin.getAbsolutePath()) && generatedPlugin.exists()) {
            sources.add(generatedPlugin);
        }
        extendJavaProjectBuilder(builder, sources, project.getArtifacts());
    }

    private void extendJavaProjectBuilder(
            JavaProjectBuilder builder, List<File> sourceDirectories, Set<Artifact> artifacts) {

        // Build isolated Classloader with only the artifacts of the project (none of this plugin)
        List<URL> urls = new ArrayList<>(artifacts.size());
        for (Artifact artifact : artifacts) {
            try {
                urls.add(artifact.getFile().toURI().toURL());
            } catch (MalformedURLException e) {
                // noop
            }
        }
        builder.addClassLoader(new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getSystemClassLoader()));

        for (File source : sourceDirectories) {
            builder.addSourceTree(source);
        }
    }

    private List<MojoDescriptor> toMojoDescriptors(
            Map<String, MojoAnnotatedClass> mojoAnnotatedClasses, PluginDescriptor pluginDescriptor)
            throws InvalidPluginDescriptorException {
        List<MojoDescriptor> mojoDescriptors = new ArrayList<>(mojoAnnotatedClasses.size());
        for (MojoAnnotatedClass mojoAnnotatedClass : mojoAnnotatedClasses.values()) {
            // no mojo so skip it
            if (mojoAnnotatedClass.getMojo() == null) {
                continue;
            }

            ExtendedMojoDescriptor mojoDescriptor = new ExtendedMojoDescriptor(true);

            // mojoDescriptor.setRole( mojoAnnotatedClass.getClassName() );
            // mojoDescriptor.setRoleHint( "default" );
            mojoDescriptor.setImplementation(mojoAnnotatedClass.getClassName());
            mojoDescriptor.setLanguage("java");

            mojoDescriptor.setV4Api(mojoAnnotatedClass.isV4Api());

            MojoAnnotationContent mojo = mojoAnnotatedClass.getMojo();

            mojoDescriptor.setDescription(mojo.getDescription());
            mojoDescriptor.setSince(mojo.getSince());
            mojo.setDeprecated(mojo.getDeprecated());

            mojoDescriptor.setProjectRequired(mojo.requiresProject());

            mojoDescriptor.setRequiresReports(mojo.requiresReports());

            mojoDescriptor.setComponentConfigurator(mojo.configurator());

            mojoDescriptor.setInheritedByDefault(mojo.inheritByDefault());

            mojoDescriptor.setInstantiationStrategy(mojo.instantiationStrategy().id());

            mojoDescriptor.setAggregator(mojo.aggregator());
            mojoDescriptor.setDependencyResolutionRequired(
                    mojo.requiresDependencyResolution().id());
            mojoDescriptor.setDependencyCollectionRequired(
                    mojo.requiresDependencyCollection().id());

            mojoDescriptor.setDirectInvocationOnly(mojo.requiresDirectInvocation());
            mojoDescriptor.setDeprecated(mojo.getDeprecated());
            mojoDescriptor.setThreadSafe(mojo.threadSafe());

            MojoAnnotatedClass mojoAnnotatedClassWithExecute =
                    findClassWithExecuteAnnotationInParentHierarchy(mojoAnnotatedClass, mojoAnnotatedClasses);
            if (mojoAnnotatedClassWithExecute != null && mojoAnnotatedClassWithExecute.getExecute() != null) {
                ExecuteAnnotationContent execute = mojoAnnotatedClassWithExecute.getExecute();
                mojoDescriptor.setExecuteGoal(execute.goal());
                mojoDescriptor.setExecuteLifecycle(execute.lifecycle());
                if (execute.phase() != null) {
                    mojoDescriptor.setExecutePhase(execute.phase().id());
                    if (StringUtils.isNotEmpty(execute.customPhase())) {
                        throw new InvalidPluginDescriptorException(
                                "@Execute annotation must only use either 'phase' "
                                        + "or 'customPhase' but not both. Both are used though on "
                                        + mojoAnnotatedClassWithExecute.getClassName(),
                                null);
                    }
                } else if (StringUtils.isNotEmpty(execute.customPhase())) {
                    mojoDescriptor.setExecutePhase(execute.customPhase());
                }
            }

            mojoDescriptor.setExecutionStrategy(mojo.executionStrategy());
            // ???
            // mojoDescriptor.alwaysExecute(mojo.a)

            mojoDescriptor.setGoal(mojo.name());
            mojoDescriptor.setOnlineRequired(mojo.requiresOnline());

            mojoDescriptor.setPhase(mojo.defaultPhase().id());

            // Parameter annotations
            Map<String, ParameterAnnotationContent> parameters =
                    getParametersParentHierarchy(mojoAnnotatedClass, mojoAnnotatedClasses);

            for (ParameterAnnotationContent parameterAnnotationContent : new TreeSet<>(parameters.values())) {
                org.apache.maven.plugin.descriptor.Parameter parameter =
                        new org.apache.maven.plugin.descriptor.Parameter();
                String name = StringUtils.isEmpty(parameterAnnotationContent.name())
                        ? parameterAnnotationContent.getFieldName()
                        : parameterAnnotationContent.name();
                parameter.setName(name);
                parameter.setAlias(parameterAnnotationContent.alias());
                parameter.setDefaultValue(parameterAnnotationContent.defaultValue());
                parameter.setDeprecated(parameterAnnotationContent.getDeprecated());
                parameter.setDescription(parameterAnnotationContent.getDescription());
                parameter.setEditable(!parameterAnnotationContent.readonly());
                String property = parameterAnnotationContent.property();
                if (StringUtils.contains(property, '$')
                        || StringUtils.contains(property, '{')
                        || StringUtils.contains(property, '}')) {
                    throw new InvalidParameterException(
                            "Invalid property for parameter '" + parameter.getName() + "', "
                                    + "forbidden characters ${}: " + property,
                            null);
                }
                parameter.setExpression((property == null || property.isEmpty()) ? "" : "${" + property + "}");
                StringBuilder type = new StringBuilder(parameterAnnotationContent.getClassName());
                if (!parameterAnnotationContent.getTypeParameters().isEmpty()) {
                    type.append(parameterAnnotationContent.getTypeParameters().stream()
                            .collect(Collectors.joining(", ", "<", ">")));
                }
                parameter.setType(type.toString());
                parameter.setSince(parameterAnnotationContent.getSince());
                parameter.setRequired(parameterAnnotationContent.required());

                mojoDescriptor.addParameter(parameter);
            }

            // Component annotations
            Map<String, ComponentAnnotationContent> components =
                    getComponentsParentHierarchy(mojoAnnotatedClass, mojoAnnotatedClasses);

            for (ComponentAnnotationContent componentAnnotationContent : new TreeSet<>(components.values())) {
                org.apache.maven.plugin.descriptor.Parameter parameter =
                        new org.apache.maven.plugin.descriptor.Parameter();
                parameter.setName(componentAnnotationContent.getFieldName());

                parameter.setRequirement(new Requirement(
                        componentAnnotationContent.getRoleClassName(), componentAnnotationContent.hint()));
                parameter.setDeprecated(componentAnnotationContent.getDeprecated());
                parameter.setSince(componentAnnotationContent.getSince());

                // same behaviour as JavaJavadocMojoDescriptorExtractor
                parameter.setEditable(false);

                mojoDescriptor.addParameter(parameter);
            }

            mojoDescriptor.setPluginDescriptor(pluginDescriptor);

            mojoDescriptors.add(mojoDescriptor);
        }
        return mojoDescriptors;
    }

    protected MojoAnnotatedClass findClassWithExecuteAnnotationInParentHierarchy(
            MojoAnnotatedClass mojoAnnotatedClass, Map<String, MojoAnnotatedClass> mojoAnnotatedClasses) {
        if (mojoAnnotatedClass.getExecute() != null) {
            return mojoAnnotatedClass;
        }
        String parentClassName = mojoAnnotatedClass.getParentClassName();
        if (parentClassName == null || parentClassName.isEmpty()) {
            return null;
        }
        MojoAnnotatedClass parent = mojoAnnotatedClasses.get(parentClassName);
        if (parent == null) {
            return null;
        }
        return findClassWithExecuteAnnotationInParentHierarchy(parent, mojoAnnotatedClasses);
    }

    protected Map<String, ParameterAnnotationContent> getParametersParentHierarchy(
            MojoAnnotatedClass mojoAnnotatedClass, Map<String, MojoAnnotatedClass> mojoAnnotatedClasses) {
        List<ParameterAnnotationContent> parameterAnnotationContents = new ArrayList<>();

        parameterAnnotationContents =
                getParametersParent(mojoAnnotatedClass, parameterAnnotationContents, mojoAnnotatedClasses);

        // move to parent first to build the Map
        Collections.reverse(parameterAnnotationContents);

        Map<String, ParameterAnnotationContent> map = new HashMap<>(parameterAnnotationContents.size());

        for (ParameterAnnotationContent parameterAnnotationContent : parameterAnnotationContents) {
            map.put(parameterAnnotationContent.getFieldName(), parameterAnnotationContent);
        }
        return map;
    }

    protected List<ParameterAnnotationContent> getParametersParent(
            MojoAnnotatedClass mojoAnnotatedClass,
            List<ParameterAnnotationContent> parameterAnnotationContents,
            Map<String, MojoAnnotatedClass> mojoAnnotatedClasses) {
        parameterAnnotationContents.addAll(mojoAnnotatedClass.getParameters().values());
        String parentClassName = mojoAnnotatedClass.getParentClassName();
        if (parentClassName != null) {
            MojoAnnotatedClass parent = mojoAnnotatedClasses.get(parentClassName);
            if (parent != null) {
                return getParametersParent(parent, parameterAnnotationContents, mojoAnnotatedClasses);
            }
        }
        return parameterAnnotationContents;
    }

    protected Map<String, ComponentAnnotationContent> getComponentsParentHierarchy(
            MojoAnnotatedClass mojoAnnotatedClass, Map<String, MojoAnnotatedClass> mojoAnnotatedClasses) {
        List<ComponentAnnotationContent> componentAnnotationContents = new ArrayList<>();

        componentAnnotationContents =
                getComponentParent(mojoAnnotatedClass, componentAnnotationContents, mojoAnnotatedClasses);

        // move to parent first to build the Map
        Collections.reverse(componentAnnotationContents);

        Map<String, ComponentAnnotationContent> map = new HashMap<>(componentAnnotationContents.size());

        for (ComponentAnnotationContent componentAnnotationContent : componentAnnotationContents) {
            map.put(componentAnnotationContent.getFieldName(), componentAnnotationContent);
        }
        return map;
    }

    protected List<ComponentAnnotationContent> getComponentParent(
            MojoAnnotatedClass mojoAnnotatedClass,
            List<ComponentAnnotationContent> componentAnnotationContents,
            Map<String, MojoAnnotatedClass> mojoAnnotatedClasses) {
        componentAnnotationContents.addAll(mojoAnnotatedClass.getComponents().values());
        String parentClassName = mojoAnnotatedClass.getParentClassName();
        if (parentClassName != null) {
            MojoAnnotatedClass parent = mojoAnnotatedClasses.get(parentClassName);
            if (parent != null) {
                return getComponentParent(parent, componentAnnotationContents, mojoAnnotatedClasses);
            }
        }
        return componentAnnotationContents;
    }

    protected MavenProject getFromProjectReferences(Artifact artifact, MavenProject project) {
        if (project.getProjectReferences() == null
                || project.getProjectReferences().isEmpty()) {
            return null;
        }
        Collection<MavenProject> mavenProjects = project.getProjectReferences().values();
        for (MavenProject mavenProject : mavenProjects) {
            if (Objects.equals(mavenProject.getId(), artifact.getId())) {
                return mavenProject;
            }
        }
        return null;
    }
}
