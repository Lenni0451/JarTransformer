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
                try {
                    transformer.transform(log, fileSystem);
                } catch (Throwable t) {
                    log.error("Failed to apply transformer: {}", transformer.getName().get(), t);
                    throw t; // Re-throw the exception to stop the transformation process
                }
            }
        }
    }


    public Transformer(final String name) {
        this.getName().set(name);
    }

    public abstract Property<String> getName();

    public abstract void transform(final Logger log, final FileSystem fileSystem) throws Throwable;

}
