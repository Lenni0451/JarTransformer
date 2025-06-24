package net.lenni0451.jartransformer.transformers;

import org.slf4j.Logger;

import java.io.File;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.HashMap;
import java.util.List;

public interface Transformer {

    static void applyAll(final Logger log, final File file, final List<Transformer> transformers) throws Throwable {
        try (FileSystem fileSystem = FileSystems.newFileSystem(file.toPath(), new HashMap<>())) {
            for (Transformer transformer : transformers) {
                transformer.transform(log, fileSystem);
            }
        }
    }

    void transform(final Logger log, final FileSystem fileSystem) throws Throwable;

}
