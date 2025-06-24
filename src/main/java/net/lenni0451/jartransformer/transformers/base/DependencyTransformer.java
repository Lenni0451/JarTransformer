package net.lenni0451.jartransformer.transformers.base;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

import javax.inject.Inject;

public abstract class DependencyTransformer extends BaseTransformer {

    @Inject
    public DependencyTransformer(final String name, final ObjectFactory objectFactory) {
        super(objectFactory, name);
    }

    @Input
    public abstract Property<Configuration> getConfiguration();

}
