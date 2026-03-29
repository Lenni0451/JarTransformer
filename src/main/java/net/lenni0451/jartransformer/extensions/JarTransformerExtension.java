package net.lenni0451.jartransformer.extensions;

import net.lenni0451.jartransformer.transformers.base.DependencyTransformer;
import net.lenni0451.jartransformer.transformers.base.JarTransformer;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;

import javax.inject.Inject;

public abstract class JarTransformerExtension {

    private final Project project;
    private final NamedDomainObjectContainer<DependencyTransformer> dependencyTransformers;
    private final NamedDomainObjectContainer<JarTransformer> jarTransformers;

    @Inject
    public JarTransformerExtension(final Project project) {
        this.project = project;
        this.dependencyTransformers = project.getObjects().domainObjectContainer(DependencyTransformer.class, name -> project.getObjects().newInstance(DependencyTransformer.class, name, project.getObjects()));
        this.jarTransformers = project.getObjects().domainObjectContainer(JarTransformer.class, name -> project.getObjects().newInstance(JarTransformer.class, name, project.getObjects(), project.getLayout().getBuildDirectory().dir("libs").get()));
    }

    public NamedDomainObjectContainer<DependencyTransformer> getDependencyTransformers() {
        return this.dependencyTransformers;
    }

    public NamedDomainObjectContainer<JarTransformer> getJarTransformers() {
        return this.jarTransformers;
    }

    public void dependency(final Action<? super DependencyTransformer> action) {
        DependencyTransformer dependencyTransformer = this.project.getObjects().newInstance(DependencyTransformer.class, "dependencyTransformer" + this.dependencyTransformers.size(), this.project.getObjects());
        action.execute(dependencyTransformer);
        this.dependencyTransformers.add(dependencyTransformer);
    }

    public void jar(final Action<? super JarTransformer> action) {
        JarTransformer jarTransformer = this.project.getObjects().newInstance(JarTransformer.class, "jarTransformer" + this.jarTransformers.size(), this.project.getObjects(), this.project.getLayout().getBuildDirectory().dir("libs").get());
        action.execute(jarTransformer);
        this.jarTransformers.add(jarTransformer);
    }

}
