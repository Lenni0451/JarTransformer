package net.lenni0451.jartransformer.utils;

import lombok.experimental.UtilityClass;
import net.lenni0451.commons.asm.ASMUtils;
import net.lenni0451.commons.asm.annotations.AnnotationUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

@UtilityClass
public class RemapUtils {

    /**
     * The path prefix of multi-release jar versioned entries.
     */
    public static final String VERSIONS_PREFIX = "meta-inf/versions/";

    /**
     * The path prefix of service files.
     */
    public static final String SERVICES_PREFIX = "meta-inf/services/";

    /**
     * The file name of the jar manifest.
     */
    public static final String MANIFEST_FILE_NAME = "manifest.mf";

    /**
     * Remap a class name in dot or slash notation using the given remap function.<br>
     * Returns {@code null} if the name was not remapped.
     *
     * @param s     The string to remap
     * @param remap The remap function working on the internal (slash) name
     * @return The remapped string or {@code null} if unchanged
     */
    @Nullable
    public static String remapName(final String s, final Function<String, String> remap) {
        if (s.contains("/")) {
            String mapped = remap.apply(s);
            return s.equals(mapped) ? null : mapped;
        } else {
            String mapped = remap.apply(ASMUtils.slash(s));
            return mapped == null || mapped.equals(ASMUtils.slash(s)) ? null : ASMUtils.dot(mapped);
        }
    }

    /**
     * Remap a class name in dot or slash notation using the given package remapper.<br>
     * Returns {@code null} if the name was not remapped.
     *
     * @param remapper The package remapper to use
     * @param s        The string to remap
     * @return The remapped string or {@code null} if unchanged
     */
    @Nullable
    public static String remap(final PackageRemapper remapper, final String s) {
        return remapName(s, remapper::mapUnchecked);
    }

    /**
     * Remap all strings inside the given class node.<br>
     * This includes strings in annotations, field constants, {@code ldc} instructions and string concatenations.
     *
     * @param node  The class node to remap the strings of
     * @param remap The string remap function
     * @return If any string was remapped
     */
    public static boolean remapStrings(final ClassNode node, final Function<String, String> remap) {
        boolean[] changed = {false};
        AnnotationUtils.forEach(node, annotation -> changed[0] |= remapAnnotationStrings(annotation, remap));
        for (FieldNode field : node.fields) {
            AnnotationUtils.forEach(field, annotation -> changed[0] |= remapAnnotationStrings(annotation, remap));
            if (field.value instanceof String s) {
                String newValue = remap.apply(s);
                if (newValue != null && !newValue.equals(s)) {
                    field.value = newValue;
                    changed[0] = true;
                }
            }
        }
        for (MethodNode method : node.methods) {
            AnnotationUtils.forEach(method, annotation -> changed[0] |= remapAnnotationStrings(annotation, remap));
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                    String newValue = remap.apply(s);
                    if (newValue != null && !newValue.equals(s)) {
                        ldc.cst = newValue;
                        changed[0] = true;
                    }
                } else if (insn instanceof InvokeDynamicInsnNode idin) {
                    if (idin.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory") && idin.bsm.getName().equals("makeConcatWithConstants")) {
                        for (int i = 0; i < idin.bsmArgs.length; i++) {
                            if (idin.bsmArgs[i] instanceof String s) {
                                String newValue = remap.apply(s);
                                if (newValue != null && !newValue.equals(s)) {
                                    idin.bsmArgs[i] = newValue;
                                    changed[0] = true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return changed[0];
    }

    private static boolean remapAnnotationStrings(final AnnotationNode annotation, final Function<String, String> remap) {
        if (annotation.values == null) return false;
        boolean modified = false;
        for (int i = 1; i < annotation.values.size(); i += 2) {
            Object value = annotation.values.get(i);
            if (value instanceof String s) {
                String newValue = remap.apply(s);
                if (newValue != null && !newValue.equals(s)) {
                    annotation.values.set(i, newValue);
                    modified = true;
                }
            } else if (value instanceof AnnotationNode other) {
                modified |= remapAnnotationStrings(other, remap);
            } else if (value instanceof List<?> list) {
                List<Object> newList = new ArrayList<>(list);
                boolean listModified = false;
                for (int l = 0; l < newList.size(); l++) {
                    Object listValue = newList.get(l);
                    if (listValue instanceof String s) {
                        String newValue = remap.apply(s);
                        if (newValue != null && !newValue.equals(s)) {
                            newList.set(l, newValue);
                            listModified = true;
                        }
                    } else if (listValue instanceof AnnotationNode other) {
                        listModified |= remapAnnotationStrings(other, remap);
                    }
                }
                if (listModified) {
                    annotation.values.set(i, newList);
                    modified = true;
                }
            }
        }
        return modified;
    }

    /**
     * Remap the service implementations inside a service file.<br>
     * Does nothing if the file is not inside {@code META-INF/services/}.<br>
     * The file itself is not renamed; service names are relocated through the path remap
     * (see {@link #servicePathRemap}).
     *
     * @param log        The logger to use
     * @param path       The path of the service file
     * @param pathString The normalized path of the file (without leading slash)
     * @param remap      The remap function returning the new name or {@code null} if unchanged
     * @throws IOException If an error occurs while remapping the service file
     */
    public static void remapServiceFile(final Logger log, final Path path, final String pathString, final Function<String, String> remap) throws IOException {
        if (!pathString.toLowerCase(Locale.ROOT).startsWith(SERVICES_PREFIX)) return;

        String[] serviceImpls = Files.readAllLines(path, StandardCharsets.UTF_8).toArray(new String[0]);
        String[] remappedServiceImpls = new String[serviceImpls.length];
        boolean modified = false;
        for (int i = 0; i < serviceImpls.length; i++) {
            String serviceImpl = serviceImpls[i];
            String remappedServiceImpl = remap.apply(serviceImpl);
            if (serviceImpl.startsWith("#") || remappedServiceImpl == null) {
                remappedServiceImpls[i] = serviceImpl;
            } else {
                remappedServiceImpls[i] = remappedServiceImpl;
                modified |= !serviceImpl.equals(remappedServiceImpl);
            }
        }
        if (modified) {
            Files.writeString(path, String.join("\n", remappedServiceImpls) + "\n");
            log.info("Remapped service implementations: {} -> {}", String.join(", ", serviceImpls), String.join(", ", remappedServiceImpls));
        }
    }

    /**
     * Wrap a path remap function to also remap the names of service files.<br>
     * Paths inside {@code META-INF/services/} are treated as dot notation class names and remapped
     * with the name remap function, all other paths are delegated to the path remap function.
     *
     * @param pathRemap The path remap function for regular paths
     * @param nameRemap The remap function for the service names
     * @return The combined path remap function
     */
    public static Function<String, String> servicePathRemap(final Function<String, String> pathRemap, final Function<String, String> nameRemap) {
        return path -> {
            if (path.toLowerCase(Locale.ROOT).startsWith(SERVICES_PREFIX)) {
                String name = path.substring(SERVICES_PREFIX.length());
                String remapped = nameRemap.apply(name);
                return remapped == null ? null : "META-INF/services/" + remapped;
            }
            return pathRemap.apply(path);
        };
    }

    /**
     * Remap the attribute values of a manifest file.<br>
     * Does nothing if the file is not a manifest file.
     *
     * @param log   The logger to use
     * @param path  The path of the manifest file
     * @param remap The remap function returning the new name or {@code null} if unchanged
     * @throws IOException If an error occurs while remapping the manifest
     */
    public static void remapManifestFile(final Logger log, final Path path, final Function<String, String> remap) throws IOException {
        if (!path.getFileName().toString().equalsIgnoreCase(MANIFEST_FILE_NAME)) return;

        Manifest manifest;
        try (InputStream is = Files.newInputStream(path)) {
            manifest = new Manifest(is);
        }
        List<Attributes> allAttributes = new ArrayList<>();
        allAttributes.add(manifest.getMainAttributes());
        allAttributes.addAll(manifest.getEntries().values());

        boolean modified = false;
        for (Attributes attributes : allAttributes) {
            for (Map.Entry<Object, Object> entry : attributes.entrySet()) {
                String value = entry.getValue().toString();
                String remappedValue = remap.apply(value);
                if (remappedValue != null && !value.equals(remappedValue)) {
                    entry.setValue(remappedValue);
                    modified = true;
                }
            }
        }
        if (modified) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            manifest.write(baos);
            Files.write(path, baos.toByteArray());
            log.info("Remapped manifest: {}", path);
        }
    }

    /**
     * Remap the plugin class names in a log4j plugin cache file.<br>
     * Does nothing if the file is not a log4j plugin cache file.
     *
     * @param log        The logger to use
     * @param path       The path of the plugin cache file
     * @param pathString The normalized path of the file (without leading slash)
     * @param remap      The remap function returning the new name or {@code null} if unchanged
     * @throws IOException If an error occurs while remapping the log4j plugins
     */
    public static void remapLog4jPluginsFile(final Logger log, final Path path, final String pathString, final Function<String, String> remap) throws IOException {
        if (!pathString.toLowerCase(Locale.ROOT).equals("meta-inf/org/apache/logging/log4j/core/config/plugins/log4j2plugins.dat")) return;

        Log4JPluginCache pluginCache = Log4JPluginCache.deserialize(Files.readAllBytes(path));
        boolean modified = false;
        for (String category : pluginCache.getCategories()) {
            Map<String, Log4JPluginCache.PluginEntry> plugins = pluginCache.getCategory(category);
            for (Log4JPluginCache.PluginEntry plugin : plugins.values()) {
                String className = plugin.getClassName();
                String remappedName = remap.apply(className);
                if (remappedName != null && !className.equals(remappedName)) {
                    plugin.setClassName(remappedName);
                    modified = true;
                    log.debug("Remapped Log4J plugin class: {} -> {}", className, remappedName);
                }
            }
        }
        if (modified) {
            Files.write(path, pluginCache.serialize());
            log.info("Remapped Log4J plugins: {}", path);
        }
    }

    /**
     * Move a file to its remapped path inside the jar file system.<br>
     * For multi-release jar entries below {@code META-INF/versions/} the remap function is only
     * applied to the part after the version directory and the version prefix is kept.<br>
     * Fails with an {@link IllegalStateException} if the target path already exists.
     *
     * @param log        The logger to use
     * @param fileSystem The jar file system
     * @param path       The path of the file to move
     * @param pathString The normalized path of the file (without leading slash)
     * @param slash      If the original path had a leading slash
     * @param pathRemap  The remap function working on the internal (slash) path
     * @throws IOException If an error occurs while moving the file
     */
    public static void moveRemappedFile(final Logger log, final FileSystem fileSystem, final Path path, final String pathString, final boolean slash, final Function<String, String> pathRemap) throws IOException {
        String target;
        if (pathString.toLowerCase(Locale.ROOT).startsWith(VERSIONS_PREFIX)) {
            int versionEnd = pathString.indexOf('/', VERSIONS_PREFIX.length()) + 1;
            if (versionEnd <= 0) return;
            String remappedSuffix = pathRemap.apply(pathString.substring(versionEnd));
            target = remappedSuffix == null ? null : pathString.substring(0, versionEnd) + remappedSuffix;
        } else {
            target = pathRemap.apply(pathString);
        }
        if (target == null || target.equals(pathString)) return;

        Path newPath = fileSystem.getPath((slash ? "/" : "") + target);
        if (Files.exists(newPath)) {
            throw new IllegalStateException("Remap collision: " + path + " cannot be moved to existing path " + newPath);
        }
        Files.createDirectories(newPath.getParent());
        Files.move(path, newPath);
        log.debug("Remapped file: {} -> {}", path, newPath);
    }

    /**
     * Check if a file should be processed based on the whitelist and blacklist.<br>
     * An entry in the blacklist takes precedence over the whitelist.
     *
     * @param pathString The normalized path of the file (without leading slash)
     * @param whitelist  The path prefixes to process (empty = all)
     * @param blacklist  The path prefixes to not process
     * @return If the file should be processed
     */
    public static boolean shouldProcess(final String pathString, final Set<String> whitelist, final Set<String> blacklist) {
        if (whitelist.isEmpty() && blacklist.isEmpty()) return true;
        for (String s : blacklist) {
            if (matchesPrefix(pathString, s)) {
                return false; //If any blacklist entry matches, don't process
            }
        }
        if (!whitelist.isEmpty()) {
            for (String s : whitelist) {
                if (matchesPrefix(pathString, s)) {
                    return true;
                }
            }
            return false; //If no whitelist entry matches, don't process
        }
        return true; //If the whitelist is empty, and it's not blacklisted, process the file
    }

    private static boolean matchesPrefix(final String path, final String prefix) {
        if (!path.startsWith(prefix)) return false;
        return prefix.endsWith("/") || path.length() == prefix.length() || path.charAt(prefix.length()) == '/';
    }

    /**
     * Remove all empty directories in the jar file system.
     *
     * @param log        The logger to use
     * @param fileSystem The jar file system
     * @throws IOException If an error occurs while removing the directories
     */
    public static void removeEmptyDirectories(final Logger log, final FileSystem fileSystem) throws IOException {
        final Path root = fileSystem.getPath("/");

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult postVisitDirectory(
                    final @NotNull Path dir,
                    final @Nullable IOException exc
            ) throws IOException {
                if (exc != null) throw exc;
                if (dir.equals(root)) return FileVisitResult.CONTINUE;

                if (isEmpty(dir)) {
                    Files.delete(dir);
                    log.debug("Removed empty directory: {}", dir);
                }

                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isEmpty(final Path dir) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            return !entries.iterator().hasNext();
        }
    }

}
