package net.lenni0451.jartransformer.utils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.Collections;
import java.util.Map;

public class FileSystemUtils {

    public static FileSystem openRead(final File file) throws IOException {
        return FileSystems.newFileSystem(file.toPath(), Collections.emptyMap());
    }

    public static FileSystem openCreating(final File file) throws IOException, URISyntaxException {
        return FileSystems.newFileSystem(new URI("jar:" + file.toURI()), Map.of("create", "true"));
    }

}
