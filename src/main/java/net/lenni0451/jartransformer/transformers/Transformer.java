package net.lenni0451.jartransformer.transformers;

import net.lenni0451.jartransformer.utils.FileSystemUtils;
import net.lenni0451.jartransformer.utils.ThrowingConsumer;
import org.gradle.api.provider.Property;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public abstract class Transformer {

    public static void applyAll(final Logger log, final File file, final List<Transformer> transformers) throws Throwable {
        try (FileSystem fileSystem = FileSystemUtils.openRead(file)) {
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

    protected final void iterateFiles(final FileSystem fileSystem, final ThrowingConsumer<Path> consumer) throws IOException {
        this.iterateFiles(fileSystem.getPath("/"), consumer);
    }

    protected final void iterateFiles(final Path rootPath, final ThrowingConsumer<Path> consumer) throws IOException {
        try (Stream<Path> paths = Files.walk(rootPath)) {
            paths
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            consumer.accept(path);
                        } catch (Throwable t) {
                            throw new IllegalStateException("Failed to process file: " + path, t);
                        }
                    });
        }
    }

}
