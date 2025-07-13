package net.lenni0451.jartransformer;

import net.lenni0451.jartransformer.extensions.JarTransformerExtension;
import net.lenni0451.jartransformer.tasks.JarTransformTask;
import net.lenni0451.jartransformer.transformers.Transformer;
import net.lenni0451.jartransformer.transformers.base.DependencyTransformer;
import net.lenni0451.jartransformer.transformers.base.JarTransformer;
import net.lenni0451.jartransformer.transformers.impl.ClassTransformTransformer;
import net.lenni0451.jartransformer.transforms.DependencyTransformAction;
import net.lenni0451.jartransformer.utils.SourceCompiler;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class JarTransformerPlugin implements Plugin<Project> {

    @Override
    public void apply(Project target) {
        JarTransformerExtension extension = target.getExtensions().create("jarTransformer", JarTransformerExtension.class);
        target.afterEvaluate(project -> {
            Set<ClassTransformTransformer> classTransformTransformers = new HashSet<>();
            for (DependencyTransformer dependencyTransformer : extension.getDependencyTransformers().get()) {
                Configuration configuration = dependencyTransformer.getConfiguration().get();
                String jarType = "dependencyTransform-" + configuration.getName();
                for (Dependency dependency : configuration.getAllDependencies()) {
                    if (!(dependency instanceof ModuleDependency moduleDependency)) continue;
                    moduleDependency.attributes(attributeContainer -> attributeContainer.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, jarType));
                }
                project.getDependencies().registerTransform(DependencyTransformAction.class, transform -> {
                    transform.getParameters().getTransformers().set(dependencyTransformer.getTransformers());
                    transform.getFrom().attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE);
                    transform.getTo().attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, jarType);
                });
                for (Transformer transformer : dependencyTransformer.getTransformers().get()) {
                    if (transformer instanceof ClassTransformTransformer t) {
                        classTransformTransformers.add(t);
                    }
                }
            }
            Task buildTask = project.getTasks().findByName("build");
            for (JarTransformer jarTransformer : extension.getJarTransformers().get()) {
                JarTransformTask task = project.getTasks().register("jarTransform-" + jarTransformer.getInputFile().get().getAsFile().getName(), JarTransformTask.class, thiz -> {
                    thiz.getJarTransformer().set(jarTransformer);
                }).get();
                if (buildTask != null) buildTask.finalizedBy(task);
                for (Transformer transformer : jarTransformer.getTransformers().get()) {
                    if (transformer instanceof ClassTransformTransformer t) {
                        classTransformTransformers.add(t);
                    }
                }
            }
            if (!classTransformTransformers.isEmpty()) {
                this.registerClassTransformTransformers(project, classTransformTransformers);
            }
        });
    }

    private void registerClassTransformTransformers(final Project project, final Set<ClassTransformTransformer> classTransformTransformers) {
        SourceSetInfo sourceSet = this.registerClassTransformSourceSet(project);
        File compiledClasses = SourceCompiler.compileSourceSet(project, sourceSet.sourceFiles, sourceSet.dependencies);
        for (ClassTransformTransformer transformer : classTransformTransformers) {
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
