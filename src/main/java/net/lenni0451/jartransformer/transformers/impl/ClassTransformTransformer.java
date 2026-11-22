package net.lenni0451.jartransformer.transformers.impl;

import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.additionalclassprovider.FileSystemClassProvider;
import net.lenni0451.classtransform.annotations.CReplaceCallback;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.transformer.IBytecodeTransformer;
import net.lenni0451.classtransform.transformer.IPostTransformer;
import net.lenni0451.classtransform.transformer.IRawTransformer;
import net.lenni0451.classtransform.utils.tree.BasicClassProvider;
import net.lenni0451.commons.asm.ASMUtils;
import net.lenni0451.commons.asm.annotations.AnnotationUtils;
import net.lenni0451.commons.asm.io.ClassIO;
import net.lenni0451.jartransformer.transformers.SpecializedTransformer;
import net.lenni0451.jartransformer.transformers.Transformer;
import net.lenni0451.jartransformer.transformers.base.DependencyTransformer;
import net.lenni0451.jartransformer.transformers.base.JarTransformer;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.compile.JavaCompile;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static net.lenni0451.commons.asm.ASMUtils.dot;

public abstract class ClassTransformTransformer extends Transformer implements SpecializedTransformer<ClassTransformTransformer> {

    private final List<IBytecodeTransformer> bytecodeTransformers = new ArrayList<>();
    private final Map<String, List<IRawTransformer>> rawTransformers = new LinkedHashMap<>();
    private final List<IPostTransformer> postTransformers = new ArrayList<>();

    @Inject
    public ClassTransformTransformer(final String name) {
        super(name);
        this.getIncluded().convention("**");
    }

    @Input
    public abstract Property<String> getIncluded();

    @Optional
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClasspath();

    /**
     * The classpath containing hook implementations loaded via {@link ServiceLoader}.<br>
     * The {@code transformerHooks} configuration is automatically added to this collection.
     */
    @Optional
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getHookClasspath();

    @Optional
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getCompiledClassesDir();

    /**
     * Add an {@link IBytecodeTransformer} which is applied to the raw bytecode of all classes.
     *
     * @param transformer The transformer to add
     */
    public void bytecodeTransformer(final IBytecodeTransformer transformer) {
        this.bytecodeTransformers.add(transformer);
    }

    /**
     * Add an {@link IRawTransformer} which is applied to the class node of a single class.
     *
     * @param className   The name of the class to transform
     * @param transformer The transformer to add
     */
    public void rawTransformer(final String className, final IRawTransformer transformer) {
        this.rawTransformers.computeIfAbsent(className, n -> new ArrayList<>()).add(transformer);
    }

    /**
     * Add an {@link IPostTransformer} which is applied to the bytecode after all other transformers.
     *
     * @param transformer The transformer to add
     */
    public void postTransformer(final IPostTransformer transformer) {
        this.postTransformers.add(transformer);
    }

    @Override
    public boolean isCacheable() {
        //Inline transformers can't be fingerprinted
        return this.bytecodeTransformers.isEmpty() && this.rawTransformers.isEmpty() && this.postTransformers.isEmpty();
    }

    @Override
    public void transform(Logger log, FileSystem fileSystem) throws Throwable {
        File compiledClassesDir = this.getCompiledClassesDir().map(Directory::getAsFile).getOrNull();
        boolean hasTransformerClasses = compiledClassesDir != null && compiledClassesDir.isDirectory();
        if (compiledClassesDir != null && !hasTransformerClasses) {
            log.warn("Compiled classes directory {} for transformer {} does not exist or is not a directory", compiledClassesDir, this.getName());
        }
        boolean hasInlineHooks = !this.bytecodeTransformers.isEmpty() || !this.rawTransformers.isEmpty() || !this.postTransformers.isEmpty();
        if (!hasTransformerClasses && !hasInlineHooks && this.getHookClasspath().isEmpty()) {
            log.info("Nothing to transform for transformer {}, skipping transformation", this.getName());
            return;
        }

        TransformerManager transformerManager = new TransformerManager(new FileSystemClassProvider(fileSystem, new BasicClassProvider()));
        this.registerInlineHooks(transformerManager);
        try (URLClassLoader hookLoader = this.createHookLoader(log)) {
            if (hookLoader != null) this.loadServiceHooks(log, transformerManager, hookLoader);
            if (hasTransformerClasses) this.iterateFiles(compiledClassesDir.toPath(), path -> {
                if (!path.getFileName().toString().endsWith(".class")) return;

                ClassNode classNode = ClassIO.fromBytes(Files.readAllBytes(path));
                String className = dot(classNode.name);
                if (!this.isIncluded(className)) return;
                try {
                    if (classNode.visibleAnnotations == null) classNode.visibleAnnotations = new ArrayList<>();
                    classNode.visibleAnnotations.add(new AnnotationNode(Type.getDescriptor(CReplaceCallback.class)));

                    transformerManager.addTransformer(classNode);
                } catch (Throwable t) {
                    //Only transformer classes are allowed in the compiled classes directory
                    log.error("Failed to add transformer class: {}", className, t);
                    throw t;
                }
            });
            this.iterateFiles(fileSystem, path -> {
                if (!path.getFileName().toString().endsWith(".class")) return;

                byte[] classBytes = Files.readAllBytes(path);
                ClassNode classNode = ClassIO.fromBytes(classBytes);
                byte[] transformedBytes = transformerManager.transform(dot(classNode.name), classBytes);
                if (transformedBytes != null) {
                    Files.write(path, transformedBytes);
                    log.debug("Transformed class: {}", dot(classNode.name));
                }
            });
        }
    }

    private void registerInlineHooks(final TransformerManager transformerManager) {
        for (IBytecodeTransformer transformer : this.bytecodeTransformers) {
            transformerManager.addBytecodeTransformer(transformer);
        }
        for (Map.Entry<String, List<IRawTransformer>> entry : this.rawTransformers.entrySet()) {
            for (IRawTransformer transformer : entry.getValue()) {
                transformerManager.addRawTransformer(entry.getKey(), transformer);
            }
        }
        for (IPostTransformer transformer : this.postTransformers) {
            transformerManager.addPostTransformConsumer(transformer);
        }
    }

    private void loadServiceHooks(final Logger log, final TransformerManager transformerManager, final URLClassLoader hookLoader) throws IOException {
        for (IBytecodeTransformer transformer : ServiceLoader.load(IBytecodeTransformer.class, hookLoader)) {
            transformerManager.addBytecodeTransformer(transformer);
            log.info("Registered bytecode transformer: {}", transformer.getClass().getName());
        }
        for (IPostTransformer transformer : ServiceLoader.load(IPostTransformer.class, hookLoader)) {
            transformerManager.addPostTransformConsumer(transformer);
            log.info("Registered post transformer: {}", transformer.getClass().getName());
        }
        for (IRawTransformer transformer : ServiceLoader.load(IRawTransformer.class, hookLoader)) {
            Set<String> targets = this.findRawTransformerTargets(transformer);
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("Raw transformer '" + transformer.getClass().getName() + "' requires a @CTransformer annotation declaring its target class(es)");
            }
            for (String target : targets) {
                transformerManager.addRawTransformer(target, transformer);
            }
            log.info("Registered raw transformer: {} -> {}", transformer.getClass().getName(), targets);
        }
    }

    private URLClassLoader createHookLoader(final Logger log) {
        if (this.getHookClasspath().isEmpty()) return null;
        URL[] urls = this.getHookClasspath().getFiles().stream().filter(file -> {
            if (file.exists()) return true;
            log.warn("Hook classpath entry does not exist, skipping: {}", file);
            return false;
        }).map(file -> {
            try {
                return file.toURI().toURL();
            } catch (MalformedURLException e) {
                throw new UncheckedIOException(e);
            }
        }).toArray(URL[]::new);
        if (urls.length == 0) return null;
        return new URLClassLoader(urls, ClassTransformTransformer.class.getClassLoader());
    }

    @SuppressWarnings("unchecked")
    private Set<String> findRawTransformerTargets(final IRawTransformer transformer) throws IOException {
        String classPath = "/" + ASMUtils.slash(transformer.getClass().getName()) + ".class";
        try (InputStream is = transformer.getClass().getResourceAsStream(classPath)) {
            if (is == null) return Set.of();

            ClassNode node = ClassIO.fromBytes(is.readAllBytes());
            Set<String> targets = new HashSet<>();
            java.util.Optional<AnnotationNode> opt = AnnotationUtils.findAnnotation(node, CTransformer.class);
            if (opt.isPresent()) {
                List<Object> values = opt.get().values;
                for (int i = 0; i < values.size(); i += 2) {
                    if ("name".equals(values.get(i))) {
                        targets.addAll((List<String>) values.get(i + 1));
                    } else if ("value".equals(values.get(i))) {
                        for (Type type : (List<Type>) values.get(i + 1)) {
                            targets.add(type.getClassName());
                        }
                    }
                }
            }
            return targets;
        }
    }

    private boolean isIncluded(final String className) {
        String included = this.getIncluded().get();
        if (included.endsWith("**")) {
            return className.startsWith(included.substring(0, included.length() - 2));
        } else if (included.endsWith("*")) {
            String pkg = included.substring(0, included.length() - 1);
            if (!pkg.isEmpty() && !pkg.endsWith(".")) pkg += ".";
            return className.startsWith(pkg) && !className.substring(pkg.length()).contains(".");
        } else {
            return className.equals(included);
        }
    }

    @Override
    public void applySpecialized(Project project, List<ClassTransformTransformer> transformers, List<Object> contexts) {
        Configuration transformerHooks = project.getConfigurations().getByName("transformerHooksClasspath");
        for (ClassTransformTransformer transformer : transformers) {
            transformer.getHookClasspath().from(transformerHooks);
        }

        SourceSet sourceSet = this.registerClassTransformSourceSet(project);
        if (sourceSet == null || sourceSet.getAllJava().isEmpty()) return;

        Provider<Directory> outputDir = project.getTasks().named(sourceSet.getCompileJavaTaskName(), JavaCompile.class)
                .flatMap(JavaCompile::getDestinationDirectory);
        for (ClassTransformTransformer transformer : transformers) {
            transformer.getCompiledClassesDir().set(outputDir);
        }

        Configuration implementation = project.getConfigurations().getByName(sourceSet.getImplementationConfigurationName());
        for (Object context : contexts) {
            if (context instanceof JarTransformer jarTransformer) {
                project.getDependencies().add(implementation.getName(), project.files(jarTransformer.getInputFile()));
            } else if (context instanceof DependencyTransformer dependencyTransformer) {
                implementation.extendsFrom(dependencyTransformer.getConfiguration().get());
            }
        }
        for (ClassTransformTransformer transformer : transformers) {
            project.getDependencies().add(implementation.getName(), transformer.getClasspath());
        }
    }

    private SourceSet registerClassTransformSourceSet(final Project project) {
        JavaPluginExtension extension = project.getExtensions().findByType(JavaPluginExtension.class);
        if (extension == null) return null;
        SourceSetContainer sourceSets = extension.getSourceSets();
        SourceSet transformer = sourceSets.maybeCreate("transformers");
        transformer.getJava().srcDir("src/transformers/java");
        transformer.getResources().srcDir("src/transformers/resources");

        Configuration implementation = project.getConfigurations().getByName(transformer.getImplementationConfigurationName());
        project.getDependencies().add(implementation.getName(), "net.lenni0451.classtransform:core:${classtransform_version}");
        return transformer;
    }

}
