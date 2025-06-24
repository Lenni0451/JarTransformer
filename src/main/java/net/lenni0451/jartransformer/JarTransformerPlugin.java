package net.lenni0451.jartransformer;

import net.lenni0451.jartransformer.extensions.JarTransformerExtension;
import net.lenni0451.jartransformer.tasks.JarTransformTask;
import net.lenni0451.jartransformer.transformers.base.DependencyTransformer;
import net.lenni0451.jartransformer.transformers.base.JarTransformer;
import net.lenni0451.jartransformer.transforms.DependencyTransformAction;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;

public class JarTransformerPlugin implements Plugin<Project> {

    @Override
    public void apply(Project target) {
        JarTransformerExtension extension = target.getExtensions().create("jarTransformer", JarTransformerExtension.class);
        target.afterEvaluate(project -> {
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
            }
            Task buildTask = project.getTasks().findByName("build");
            for (JarTransformer jarTransformer : extension.getJarTransformers().get()) {
                JarTransformTask task = project.getTasks().register("jarTransform-" + jarTransformer.getInputFile().get().getAsFile().getName(), JarTransformTask.class, thiz -> {
                    thiz.getJarTransformer().set(jarTransformer);
                }).get();
                if (buildTask != null) buildTask.finalizedBy(task);
            }
        });
    }

}
