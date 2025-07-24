package net.lenni0451.jartransformer;

import net.lenni0451.jartransformer.extensions.JarMergerExtension;
import net.lenni0451.jartransformer.extensions.JarTransformerExtension;
import net.lenni0451.jartransformer.tasks.JarMergerTask;
import net.lenni0451.jartransformer.tasks.JarTransformTask;
import net.lenni0451.jartransformer.transformers.SpecializedTransformer;
import net.lenni0451.jartransformer.transformers.Transformer;
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
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JarTransformerPlugin implements Plugin<Project> {

    @Override
    public void apply(Project target) {
        JarMergerExtension jarMergerExtension = target.getExtensions().create("jarMerger", JarMergerExtension.class);
        target.afterEvaluate(project -> {
            if (jarMergerExtension.getFileName().isPresent()) {
                TaskProvider<JarMergerTask> jarMergerTask = project.getTasks().register("jarMerger", JarMergerTask.class);
                jarMergerTask.configure(task -> {
                    task.getProjectJar().set(project.getTasks().named("jar", Jar.class).flatMap(Jar::getArchiveFile));
                    task.getInputFiles().from(jarMergerExtension.getConfiguration().get().getIncoming().getArtifacts().getArtifactFiles());
                    task.getOutputJar().set(jarMergerExtension.getDestinationDirectory().file(jarMergerExtension.getFileName()));
                    task.getDuplicatesStrategy().set(jarMergerExtension.getDuplicatesStrategy());
                    task.getExcludes().set(jarMergerExtension.getExcludes());
                    task.getMergeServices().set(jarMergerExtension.getMergeServices());
                    task.getMergeLog4jPlugins().set(jarMergerExtension.getMergeLog4jPlugins());
                });
                project.getTasks().named("assemble").configure(task -> task.dependsOn(jarMergerTask));
            }
        });

        JarTransformerExtension jarTransformerExtension = target.getExtensions().create("jarTransformer", JarTransformerExtension.class);
        target.afterEvaluate(project -> {
            Map<Class<?>, SpecializedTransformerList<?>> specializedTransformers = new HashMap<>();

            this.applyDependencyTransformers(project, jarTransformerExtension, specializedTransformers);
            this.applyJarTransformers(project, jarTransformerExtension, specializedTransformers);

            for (SpecializedTransformerList<?> specializedTransformerList : specializedTransformers.values()) {
                specializedTransformerList.invoke(project);
            }
        });
    }

    private void applyDependencyTransformers(final Project project, final JarTransformerExtension extension, final Map<Class<?>, SpecializedTransformerList<?>> specializedTransformers) {
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

            this.checkSpecializedTransformers(dependencyTransformer.getTransformers().get(), specializedTransformers);
        }
    }

    private void applyJarTransformers(final Project project, final JarTransformerExtension extension, final Map<Class<?>, SpecializedTransformerList<?>> specializedTransformers) {
        Task buildTask = project.getTasks().findByName("build");
        for (JarTransformer jarTransformer : extension.getJarTransformers().get()) {
            JarTransformTask task = project.getTasks().register("jarTransform-" + jarTransformer.getInputFile().get().getAsFile().getName(), JarTransformTask.class, thiz -> {
                thiz.getJarTransformer().set(jarTransformer);
            }).get();
            if (buildTask != null) buildTask.finalizedBy(task);

            this.checkSpecializedTransformers(jarTransformer.getTransformers().get(), specializedTransformers);
        }
    }

    private void checkSpecializedTransformers(final List<Transformer> transformers, final Map<Class<?>, SpecializedTransformerList<?>> specializedTransformers) {
        for (Transformer transformer : transformers) {
            if (!(transformer instanceof SpecializedTransformer<?> specializedTransformer)) continue;
            SpecializedTransformerList<?> list = specializedTransformers.computeIfAbsent(specializedTransformer.getClass(), k -> new SpecializedTransformerList(k, new ArrayList<>()));
            list.add(specializedTransformer);
        }
    }


    private record SpecializedTransformerList<T extends SpecializedTransformer<T>>(Class<T> type, List<T> transformers) {
        public void add(final SpecializedTransformer<?> transformer) {
            if (!this.type.isInstance(transformer)) {
                throw new IllegalArgumentException("Transformer " + transformer.getClass().getSimpleName() + " is not an instance of " + this.type.getName());
            }
            this.transformers.add(this.type.cast(transformer));
        }

        public void invoke(final Project project) {
            this.transformers.get(0).applySpecialized(project, this.transformers);
        }
    }

}
