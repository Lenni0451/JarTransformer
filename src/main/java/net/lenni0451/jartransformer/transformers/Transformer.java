package net.lenni0451.jartransformer.transformers;

import org.gradle.api.provider.Property;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.HashMap;
import java.util.List;

public abstract class Transformer {

    public static void applyAll(final Logger log, final File file, final List<Transformer> transformers) throws Throwable {
        try (FileSystem fileSystem = FileSystems.newFileSystem(file.toPath(), new HashMap<>())) {
            for (Transformer transformer : transformers) {
                transformer.transform(log, fileSystem);
            }
        }
    }


    public Transformer(final String name) {
        this.getName().set(name);
    }

    public abstract Property<String> getName();

    public abstract void transform(final Logger log, final FileSystem fileSystem) throws Throwable;

}
