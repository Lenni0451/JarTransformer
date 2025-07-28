package net.lenni0451.jartransformer.extensions;

import net.lenni0451.jartransformer.transformers.base.DependencyTransformer;
import net.lenni0451.jartransformer.transformers.base.JarTransformer;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.provider.ListProperty;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

public abstract class JarTransformerExtension {

    private final Project project;

    @Inject
    public JarTransformerExtension(final Project project) {
        this.project = project;
    }

    public abstract ListProperty<DependencyTransformer> getDependencyTransformers();

    public abstract ListProperty<JarTransformer> getJarTransformers();

    public void dependency(final Action<? super DependencyTransformer> action) {
        List<DependencyTransformer> dependencyTransformers = new ArrayList<>(this.getDependencyTransformers().get());
        DependencyTransformer dependencyTransformer = this.project.getObjects().newInstance(DependencyTransformer.class, "dependencyTransformer" + dependencyTransformers.size(), this.project.getObjects());
        dependencyTransformers.add(dependencyTransformer);
        this.getDependencyTransformers().set(dependencyTransformers);
        action.execute(dependencyTransformer);
    }

    public void jar(final Action<? super JarTransformer> action) {
        List<JarTransformer> jarTransformers = new ArrayList<>(this.getJarTransformers().get());
        JarTransformer jarTransformer = this.project.getObjects().newInstance(JarTransformer.class, "jarTransformer" + jarTransformers.size(), this.project.getObjects(), this.project.getLayout().getBuildDirectory().dir("libs").get());
        jarTransformers.add(jarTransformer);
        this.getJarTransformers().set(jarTransformers);
        action.execute(jarTransformer);
    }

}
