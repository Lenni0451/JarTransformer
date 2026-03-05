package net.lenni0451.jartransformer.transformers.impl;

import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.additionalclassprovider.FileSystemClassProvider;
import net.lenni0451.classtransform.annotations.CReplaceCallback;
import net.lenni0451.classtransform.utils.tree.BasicClassProvider;
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
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static net.lenni0451.commons.asm.ASMUtils.dot;

public abstract class ClassTransformTransformer extends Transformer implements SpecializedTransformer<ClassTransformTransformer> {

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

    @Optional
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getCompiledClassesDir();

    @Override
    public boolean isCacheable() {
        return true;
    }

    @Override
    public void transform(Logger log, FileSystem fileSystem) throws Throwable {
        if (!this.getCompiledClassesDir().isPresent()) return;
        File compiledClassesDir = this.getCompiledClassesDir().get().getAsFile();
        if (!compiledClassesDir.isDirectory()) return;

        TransformerManager transformerManager = new TransformerManager(new FileSystemClassProvider(fileSystem, new BasicClassProvider()));
        this.iterateFiles(compiledClassesDir.toPath(), path -> {
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
        SourceSet sourceSet = this.registerClassTransformSourceSet(project);
        if (sourceSet == null || sourceSet.getAllJava().isEmpty()) return;

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

        Provider<Directory> outputDir = project.getTasks().named(sourceSet.getCompileJavaTaskName(), JavaCompile.class)
                .flatMap(JavaCompile::getDestinationDirectory);
        for (ClassTransformTransformer transformer : transformers) {
            transformer.getCompiledClassesDir().set(outputDir);
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
