package net.lenni0451.jartransformer.transformers.impl;

import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.additionalclassprovider.FileSystemClassProvider;
import net.lenni0451.classtransform.annotations.CReplaceCallback;
import net.lenni0451.classtransform.utils.tree.BasicClassProvider;
import net.lenni0451.commons.asm.io.ClassIO;
import net.lenni0451.jartransformer.transformers.SpecializedTransformer;
import net.lenni0451.jartransformer.transformers.Transformer;
import net.lenni0451.jartransformer.utils.SourceCompiler;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
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

    //Filled out by the JarTransformerPlugin
    //Not a directory/file property because gradle decides to crash when using those
    @Input
    public abstract Property<String> getCompiledClassesDir();

    @Override
    public void transform(Logger log, FileSystem fileSystem) throws Throwable {
        TransformerManager transformerManager = new TransformerManager(new FileSystemClassProvider(fileSystem, new BasicClassProvider()));
        this.iterateFiles(new File(this.getCompiledClassesDir().get()).toPath(), path -> {
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
    public void applySpecialized(Project project, List<ClassTransformTransformer> transformers) {
        SourceSetInfo sourceSet = this.registerClassTransformSourceSet(project);
        File compiledClasses = SourceCompiler.compileSourceSet(project, sourceSet.sourceFiles, sourceSet.dependencies);
        for (ClassTransformTransformer transformer : transformers) {
            transformer.getCompiledClassesDir().set(compiledClasses.getAbsolutePath());
        }
    }

    private SourceSetInfo registerClassTransformSourceSet(final Project project) {
        JavaPluginExtension extension = project.getExtensions().getByType(JavaPluginExtension.class);
        SourceSetContainer sourceSets = extension.getSourceSets();
        SourceSet transformer = sourceSets.create("transformers", ss -> {
            ss.getJava().srcDir("src/transformers/java");
            ss.getResources().srcDir("src/transformers/resources");
        });
        Configuration configuration = project.getConfigurations().getByName(transformer.getImplementationConfigurationName());
        project.getDependencies().add(configuration.getName(), "net.lenni0451.classtransform:core:${classtransform_version}");
        configuration.setCanBeResolved(true);
        return new SourceSetInfo(transformer.getAllJava(), configuration);
    }


    private record SourceSetInfo(FileCollection sourceFiles, Configuration dependencies) {
    }

}
