package net.lenni0451.jartransformer.utils;

import lombok.experimental.UtilityClass;
import net.lenni0451.commons.asm.ASMUtils;
import net.lenni0451.commons.asm.annotations.AnnotationUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

@UtilityClass
public class RemapUtils {

    /**
     * The path prefix of multi-release jar versioned entries.
     */
    public static final String VERSIONS_PREFIX = "meta-inf/versions/";

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
     * Remap the name of a service file and the service implementations inside.<br>
     * Does nothing if the file is not inside {@code META-INF/services/}.
     *
     * @param log        The logger to use
     * @param fileSystem The jar file system
     * @param path       The path of the service file
     * @param pathString The normalized path of the file (without leading slash)
     * @param slash      If the original path had a leading slash
     * @param remap      The remap function returning the new name or {@code null} if unchanged
     * @throws IOException If an error occurs while remapping the service file
     */
    public static void remapServiceFile(final Logger log, final FileSystem fileSystem, final Path path, final String pathString, final boolean slash, final Function<String, String> remap) throws IOException {
        if (!pathString.toLowerCase(Locale.ROOT).startsWith("meta-inf/services/")) return;

        String serviceName = pathString.substring("META-INF/services/".length());
        String remappedServiceName = remap.apply(serviceName);
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
        if (remappedServiceName != null && !serviceName.equals(remappedServiceName)) {
            Path newPath = fileSystem.getPath((slash ? "/" : "") + "META-INF/services/" + remappedServiceName);
            Files.move(path, newPath);
            log.info("Remapped service name: {} -> {}", serviceName, remappedServiceName);
        }
    }

    /**
     * Remap the attribute values of a manifest file.<br>
     * Does nothing if the file is not a manifest file.<br>
     * Unchanged entries are written back byte-identical to keep the original order and formatting.
     *
     * @param log   The logger to use
     * @param path  The path of the manifest file
     * @param remap The remap function returning the new name or {@code null} if unchanged
     * @throws IOException If an error occurs while remapping the manifest
     */
    public static void remapManifestFile(final Logger log, final Path path, final Function<String, String> remap) throws IOException {
        if (!path.getFileName().toString().equalsIgnoreCase("manifest.mf")) return;

        String content = Files.readString(path, StandardCharsets.UTF_8);
        String eol = content.contains("\r\n") ? "\r\n" : "\n";

        List<String> entries = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int pos = 0;
        while (pos < content.length()) {
            int nl = content.indexOf('\n', pos);
            String line = nl == -1 ? content.substring(pos) : content.substring(pos, nl + 1);
            pos = nl == -1 ? content.length() : nl + 1;
            if (!stripEol(line).startsWith(" ") && !current.isEmpty()) {
                entries.add(current.toString());
                current.setLength(0);
            }
            current.append(line);
        }
        if (!current.isEmpty()) entries.add(current.toString());

        boolean modified = false;
        StringBuilder out = new StringBuilder(content.length());
        for (String entry : entries) {
            String rewritten = remapManifestEntry(entry, remap, eol);
            modified |= !rewritten.equals(entry);
            out.append(rewritten);
        }
        if (modified) {
            Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
            log.info("Remapped manifest: {}", path);
        }
    }

    private static String remapManifestEntry(final String entry, final Function<String, String> remap, final String eol) {
        int firstBreak = entry.indexOf('\n');
        String first = firstBreak == -1 ? stripEol(entry) : stripEol(entry.substring(0, firstBreak + 1));
        int sep = first.indexOf(": ");
        if (sep == -1) return entry;

        String key = first.substring(0, sep);
        StringBuilder value = new StringBuilder(first.substring(sep + 2));
        int pos = firstBreak == -1 ? entry.length() : firstBreak + 1;
        while (pos < entry.length()) {
            int nl = entry.indexOf('\n', pos);
            String line = nl == -1 ? entry.substring(pos) : entry.substring(pos, nl + 1);
            pos = nl == -1 ? entry.length() : nl + 1;
            String bare = stripEol(line);
            if (!bare.startsWith(" ")) break;
            value.append(bare.substring(1));
        }

        String valueStr = value.toString();
        String remapped = remap.apply(valueStr);
        if (remapped == null || remapped.equals(valueStr)) return entry;
        return wrapManifestEntry(key, remapped, eol);
    }

    private static String wrapManifestEntry(final String key, final String value, final String eol) {
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(key.length() + 2 + valueBytes.length).append(key).append(": ");
        int first = Math.min(valueBytes.length, Math.max(0, 72 - out.length()));
        out.append(new String(valueBytes, 0, first, StandardCharsets.UTF_8)).append(eol);
        for (int pos = first; pos < valueBytes.length; pos += 71) {
            int len = Math.min(valueBytes.length - pos, 71);
            out.append(' ').append(new String(valueBytes, pos, len, StandardCharsets.UTF_8)).append(eol);
        }
        return out.toString();
    }

    private static String stripEol(final String line) {
        int len = line.length();
        if (len > 0 && line.charAt(len - 1) == '\n') len--;
        if (len > 0 && line.charAt(len - 1) == '\r') len--;
        return line.substring(0, len);
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
