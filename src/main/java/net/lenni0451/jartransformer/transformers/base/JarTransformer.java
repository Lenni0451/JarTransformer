package net.lenni0451.jartransformer.transformers.base;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;

import javax.inject.Inject;

public abstract class JarTransformer extends BaseTransformer {

    @Inject
    public JarTransformer(final String name, final ObjectFactory objectFactory) {
        super(objectFactory, name);
    }

    @InputFile
    public abstract RegularFileProperty getInputFile();

    @Optional
    @InputFile
    public abstract RegularFileProperty getOutputFile();

}
