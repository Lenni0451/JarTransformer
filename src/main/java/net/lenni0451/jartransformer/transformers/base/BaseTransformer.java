package net.lenni0451.jartransformer.transformers.base;

import net.lenni0451.jartransformer.transformers.Transformer;
import net.lenni0451.jartransformer.transformers.impl.RepackageTransformer;
import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.slf4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public abstract class BaseTransformer {

    private final ObjectFactory objectFactory;

    public BaseTransformer(final ObjectFactory objectFactory, final String name) {
        this.objectFactory = objectFactory;
        this.getName().set(name);
    }

    public abstract Property<String> getName();

    public abstract ListProperty<Transformer> getTransformers();

    public void apply(final Logger log, final File file) throws Throwable {
        Transformer.applyAll(log, file, this.getTransformers().get());
    }

    public void repackage(final Action<? super RepackageTransformer> action) {
        List<Transformer> transformers = new ArrayList<>(this.getTransformers().get());
        RepackageTransformer repackageTransformer = this.objectFactory.newInstance(RepackageTransformer.class, "repackageTransformer" + transformers.size());
        transformers.add(repackageTransformer);
        this.getTransformers().set(transformers);
        action.execute(repackageTransformer);
    }

}
