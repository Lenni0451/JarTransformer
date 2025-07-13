package net.lenni0451.jartransformer.transformers.base;

import net.lenni0451.jartransformer.transformers.Transformer;
import net.lenni0451.jartransformer.transformers.impl.*;
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
        this.add("repackageTransformer", RepackageTransformer.class, action);
    }

    public void exclude(final Action<? super ExcludeTransformer> action) {
        this.add("excludeTransformer", ExcludeTransformer.class, action);
    }

    public void access(final Action<? super AccessTransformer> action) {
        this.add("accessTransformer", AccessTransformer.class, action);
    }

    public void stringReplace(final Action<? super StringReplaceTransformer> action) {
        this.add("stringReplaceTransformer", StringReplaceTransformer.class, action);
    }

    public void classTransform(final Action<? super ClassTransformTransformer> action) {
        this.add("classTransformTransformer", ClassTransformTransformer.class, action);
    }

    private <T extends Transformer> void add(final String name, final Class<T> type, final Action<? super T> action) {
        List<Transformer> transformers = new ArrayList<>(this.getTransformers().get());
        T transformer = this.objectFactory.newInstance(type, name + transformers.size());
        transformers.add(transformer);
        this.getTransformers().set(transformers);
        action.execute(transformer);
    }

}
