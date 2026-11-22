package net.lenni0451.jartransformer.transformers.impl;

import net.lenni0451.commons.asm.info.ClassInfoProvider;
import net.lenni0451.commons.asm.io.ClassIO;
import net.lenni0451.commons.asm.mappings.Mappings;
import net.lenni0451.commons.asm.mappings.MappingsFiller;
import net.lenni0451.commons.asm.mappings.Remapper;
import net.lenni0451.commons.asm.mappings.loader.MappingsLoader;
import net.lenni0451.commons.asm.mappings.loader.formats.ProguardMappingsLoader;
import net.lenni0451.commons.asm.mappings.loader.formats.RetroguardMappingsLoader;
import net.lenni0451.commons.asm.mappings.loader.formats.SrgMappingsLoader;
import net.lenni0451.commons.asm.mappings.loader.formats.TinyV1MappingsLoader;
import net.lenni0451.commons.asm.mappings.loader.formats.TinyV2MappingsLoader;
import net.lenni0451.commons.asm.mappings.loader.formats.TsrgV1MappingsLoader;
import net.lenni0451.commons.asm.provider.ClassProvider;
import net.lenni0451.commons.asm.provider.DelegatingClassProvider;
import net.lenni0451.commons.asm.provider.LoaderClassProvider;
import net.lenni0451.commons.asm.provider.io.FileSystemClassProvider;
import net.lenni0451.jartransformer.transformers.Transformer;
import net.lenni0451.jartransformer.utils.RemapUtils;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public abstract class RemapTransformer extends Transformer {

    @Inject
    public RemapTransformer(final String name) {
        super(name);
        this.getFormat().convention("auto");
        this.getFromNamespace().convention("official");
        this.getToNamespace().convention("named");
        this.getReverse().convention(false);
        this.getFillSuperMembers().convention(true);
        this.getWhitelist().convention(Set.of());
        this.getBlacklist().convention(Set.of());
        this.getMoveFiles().convention(true);
        this.getRemapStrings().convention(false);
        this.getRemapServices().convention(true);
        this.getRemapManifest().convention(true);
        this.getRemapLog4jPlugins().convention(true);
        this.getRemoveEmptyDirs().convention(false);
    }

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getMappingFile();

    @Input
    public abstract Property<String> getFormat();

    @Input
    public abstract Property<String> getFromNamespace();

    @Input
    public abstract Property<String> getToNamespace();

    @Input
    public abstract Property<Boolean> getReverse();

    @Input
    public abstract Property<Boolean> getFillSuperMembers();

    @Input
    public abstract SetProperty<String> getWhitelist();

    @Input
    public abstract SetProperty<String> getBlacklist();

    @Input
    public abstract Property<Boolean> getMoveFiles();

    @Input
    public abstract Property<Boolean> getRemapStrings();

    @Input
    public abstract Property<Boolean> getRemapServices();

    @Input
    public abstract Property<Boolean> getRemapManifest();

    @Input
    public abstract Property<Boolean> getRemapLog4jPlugins();

    @Input
    public abstract Property<Boolean> getRemoveEmptyDirs();

    @Override
    public void transform(Logger log, FileSystem fileSystem) throws Throwable {
        Mappings mappings = this.loadMappings();
        if (this.getReverse().get()) mappings = mappings.reverse();
        if (mappings.isEmpty()) {
            log.warn("Loaded mappings are empty, nothing to remap");
            return;
        }
        if (!this.getMoveFiles().get() && (!mappings.getClassMappings().isEmpty() || !mappings.getPackageMappings().isEmpty())) {
            log.warn("The mappings contain class/package mappings but moveFiles is disabled; remapped classes will keep their old paths and the jar may be broken");
        }
        if (this.getFillSuperMembers().get()) this.fillSuperMembers(log, fileSystem, mappings);
        this.remap(log, fileSystem, mappings);
        if (this.getRemoveEmptyDirs().get()) RemapUtils.removeEmptyDirectories(log, fileSystem);
    }

    private Mappings loadMappings() throws Throwable {
        if (!this.getMappingFile().isPresent()) {
            throw new IllegalArgumentException("mappingFile is required for remap transformer");
        }
        File mappingFile = this.getMappingFile().get().getAsFile();
        if (!mappingFile.isFile()) {
            throw new IllegalArgumentException("Mapping file does not exist: " + mappingFile.getAbsolutePath());
        }
        MappingsLoader loader = this.createLoader(mappingFile);
        return loader.getMappings();
    }

    private MappingsLoader createLoader(final File mappingFile) throws IOException {
        String format = this.getFormat().get().toLowerCase(Locale.ROOT);
        return switch (format) {
            case "auto" -> this.detectFormat(Files.readAllBytes(mappingFile.toPath()));
            case "proguard" -> new ProguardMappingsLoader(mappingFile.toPath());
            case "retroguard" -> new RetroguardMappingsLoader(mappingFile.toPath());
            case "srg", "xsrg" -> new SrgMappingsLoader(mappingFile.toPath());
            case "tsrg", "tsrg_v1" -> new TsrgV1MappingsLoader(new ByteArrayInputStream(this.stripTsrgHeader(Files.readAllBytes(mappingFile.toPath()))));
            case "tiny", "tiny_v1" -> new TinyV1MappingsLoader(mappingFile.toPath(), this.getFromNamespace().get(), this.getToNamespace().get());
            case "tiny_v2" -> new TinyV2MappingsLoader(mappingFile.toPath(), this.getFromNamespace().get(), this.getToNamespace().get());
            default -> throw new IllegalArgumentException("Unknown mapping format: " + this.getFormat().get());
        };
    }

    private MappingsLoader detectFormat(final byte[] bytes) {
        List<String> lines = this.decode(bytes).lines().filter(l -> !l.trim().isEmpty()).toList();
        if (lines.isEmpty()) throw new IllegalStateException("Mapping file is empty");
        String first = lines.get(0);
        if (first.startsWith("tiny\t") || first.startsWith("tiny ")) {
            String[] parts = first.split("\\s+");
            if (parts.length > 1) {
                if (parts[1].equals("2")) {
                    return new TinyV2MappingsLoader(new ByteArrayInputStream(bytes), this.getFromNamespace().get(), this.getToNamespace().get());
                } else if (parts[1].equals("1")) {
                    return new TinyV1MappingsLoader(new ByteArrayInputStream(bytes), this.getFromNamespace().get(), this.getToNamespace().get());
                }
                throw new IllegalStateException("Unsupported tiny major version: " + parts[1]);
            }
        }
        if (first.equals("v1") || first.startsWith("v1\t") || first.startsWith("v1 ")) {
            return new TinyV1MappingsLoader(new ByteArrayInputStream(bytes), this.getFromNamespace().get(), this.getToNamespace().get());
        }
        if (first.trim().startsWith("tsrg")) {
            return new TsrgV1MappingsLoader(new ByteArrayInputStream(this.stripTsrgHeader(bytes)));
        }
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("PK:") || trimmed.startsWith("CL:") || trimmed.startsWith("FD:") || trimmed.startsWith("MD:")) {
                return new SrgMappingsLoader(new ByteArrayInputStream(bytes));
            } else if (trimmed.startsWith(".class_map") || trimmed.startsWith(".field_map") || trimmed.startsWith(".method_map")) {
                return new RetroguardMappingsLoader(new ByteArrayInputStream(bytes));
            } else if (line.contains(" -> ")) {
                return new ProguardMappingsLoader(new ByteArrayInputStream(bytes));
            } else if (line.startsWith("\t")) {
                return new TsrgV1MappingsLoader(new ByteArrayInputStream(bytes));
            }
        }
        throw new IllegalStateException("Could not detect the mapping file format. Please set the format option manually");
    }

    private byte[] stripTsrgHeader(final byte[] bytes) {
        String content = this.decode(bytes);
        int newLine = content.indexOf('\n');
        if (newLine != -1 && content.substring(0, newLine).trim().startsWith("tsrg")) {
            return content.substring(newLine + 1).getBytes(Charset.defaultCharset());
        }
        return bytes;
    }

    private String decode(final byte[] bytes) {
        String content = new String(bytes, Charset.defaultCharset());
        return content.startsWith("\uFEFF") ? content.substring(1) : content;
    }

    private void fillSuperMembers(final Logger log, final FileSystem fileSystem, final Mappings mappings) throws IOException {
        Set<String> classes = new HashSet<>();
        this.iterateFiles(fileSystem, path -> {
            if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class")) {
                try (InputStream is = Files.newInputStream(path)) {
                    classes.add(new ClassReader(is).getClassName());
                }
            }
        });
        ClassProvider classProvider = new DelegatingClassProvider(new FileSystemClassProvider(fileSystem, false), new LoaderClassProvider());
        ClassInfoProvider classInfoProvider = ClassInfoProvider.create(classProvider);
        MappingsFiller.fillAllSuperMembers(mappings, classInfoProvider, classes, (clazz, t) -> {
            log.warn("Failed to fill super members for class: {}", clazz, t);
            return true;
        });
    }

    private void remap(final Logger log, final FileSystem fileSystem, final Mappings mappings) throws IOException {
        Function<String, String> remapFunction = s -> RemapUtils.remapName(s, internal -> {
            String mapped = mappings.map(internal);
            return mapped.equals(internal) ? null : mapped;
        });
        Function<String, String> pathRemap = this.createPathRemapFunction(mappings);
        this.iterateFiles(fileSystem, path -> {
            try {
                String pathString = path.toString();
                boolean slash = pathString.startsWith("/");
                if (slash) pathString = pathString.substring(1);
                if (!RemapUtils.shouldProcess(pathString, this.getWhitelist().get(), this.getBlacklist().get())) return;
                if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class")) {
                    this.remapClass(log, fileSystem, path, pathString, slash, mappings, remapFunction);
                } else {
                    if (pathString.toLowerCase(Locale.ROOT).startsWith("meta-inf/")) {
                        if (this.getRemapServices().get()) RemapUtils.remapServiceFile(log, fileSystem, path, pathString, slash, remapFunction);
                        if (this.getRemapManifest().get()) RemapUtils.remapManifestFile(log, path, remapFunction);
                        if (this.getRemapLog4jPlugins().get()) RemapUtils.remapLog4jPluginsFile(log, path, pathString, remapFunction);
                    }
                    if (this.getMoveFiles().get()) {
                        this.moveFile(log, fileSystem, path, pathString, slash, pathRemap);
                    }
                }
            } catch (Throwable t) {
                log.error("Failed to remap file: {}", path, t);
                throw t;
            }
        });
    }

    private Function<String, String> createPathRemapFunction(final Mappings mappings) {
        Map<String, String> prefixes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mappings.getPackageMappings().entrySet()) {
            String from = ".".equals(entry.getKey()) ? "" : entry.getKey();
            String to = ".".equals(entry.getValue()) ? "" : entry.getValue();
            prefixes.put(from, to);
        }
        for (Map.Entry<String, String> entry : mappings.getClassMappings().entrySet()) {
            int fromSlash = entry.getKey().lastIndexOf('/');
            if (fromSlash == -1) continue;
            String fromPkg = entry.getKey().substring(0, fromSlash + 1);
            int toSlash = entry.getValue().lastIndexOf('/');
            String toPkg = toSlash == -1 ? "" : entry.getValue().substring(0, toSlash + 1);
            if (!fromPkg.equals(toPkg)) prefixes.putIfAbsent(fromPkg, toPkg);
        }
        return path -> {
            String match = null;
            for (String prefix : prefixes.keySet()) {
                if ((prefix.isEmpty() ? !path.contains("/") : path.startsWith(prefix)) && (match == null || prefix.length() > match.length())) {
                    match = prefix;
                }
            }
            if (match == null) return null;
            return prefixes.get(match) + path.substring(match.length());
        };
    }

    private void moveFile(final Logger log, final FileSystem fileSystem, final Path path, final String pathString, final boolean slash, final Function<String, String> pathRemap) throws IOException {
        if (pathString.toLowerCase(Locale.ROOT).startsWith(RemapUtils.VERSIONS_PREFIX)) {
            int versionEnd = pathString.indexOf('/', RemapUtils.VERSIONS_PREFIX.length()) + 1;
            if (versionEnd <= 0) return;
            String prefix = pathString.substring(0, versionEnd);
            String suffix = pathString.substring(versionEnd);
            String remappedSuffix = pathRemap.apply(suffix);
            if (remappedSuffix != null && !suffix.equals(remappedSuffix)) {
                Path newPath = fileSystem.getPath((slash ? "/" : "") + prefix + remappedSuffix);
                Files.createDirectories(newPath.getParent());
                Files.move(path, newPath);
                log.debug("Remapped versioned file: {} -> {}", path, newPath);
            }
        } else {
            String remappedPath = pathRemap.apply(pathString);
            if (remappedPath != null && !pathString.equals(remappedPath)) {
                Path newPath = fileSystem.getPath((slash ? "/" : "") + remappedPath);
                Files.createDirectories(newPath.getParent());
                Files.move(path, newPath);
                log.debug("Remapped file: {} -> {}", path, newPath);
            }
        }
    }

    private void remapClass(final Logger log, final FileSystem fileSystem, final Path path, final String pathString, final boolean slash, final Mappings mappings, final Function<String, String> remapFunction) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        ClassNode node = ClassIO.fromBytes(bytes);
        String oldName = node.name;
        ClassNode remappedNode = Remapper.remap(node, mappings);
        if (this.getRemapStrings().get()) {
            RemapUtils.remapStrings(remappedNode, remapFunction);
        }
        if (this.getMoveFiles().get() && !oldName.equals(remappedNode.name)) {
            String prefix = "";
            if (pathString.toLowerCase(Locale.ROOT).startsWith(RemapUtils.VERSIONS_PREFIX)) {
                prefix = pathString.substring(0, pathString.indexOf('/', RemapUtils.VERSIONS_PREFIX.length()) + 1);
            }
            Path newPath = fileSystem.getPath((slash ? "/" : "") + prefix + remappedNode.name + ".class");
            Files.createDirectories(newPath.getParent());
            Files.write(newPath, ClassIO.toStacklessBytes(remappedNode));
            Files.delete(path);
            log.debug("Remapped class file: {} -> {}", path, newPath);
        } else {
            Files.write(path, ClassIO.toStacklessBytes(remappedNode));
            log.debug("Remapped class: {}", path);
        }
    }

}
