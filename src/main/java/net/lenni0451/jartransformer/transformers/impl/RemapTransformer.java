package net.lenni0451.jartransformer.transformers.impl;

import net.lenni0451.commons.asm.info.ClassInfoProvider;
import net.lenni0451.commons.asm.io.ClassIO;
import net.lenni0451.commons.asm.mappings.Mappings;
import net.lenni0451.commons.asm.mappings.MappingsFiller;
import net.lenni0451.commons.asm.mappings.Remapper;
import net.lenni0451.commons.asm.mappings.loader.MappingsFormat;
import net.lenni0451.commons.asm.mappings.loader.MappingsLoader;
import net.lenni0451.commons.asm.mappings.loader.formats.ProguardMappingsLoader;
import net.lenni0451.commons.asm.mappings.loader.formats.RetroguardMappingsLoader;
import net.lenni0451.commons.asm.mappings.loader.formats.SrgMappingsLoader;
import net.lenni0451.commons.asm.mappings.loader.formats.TinyV1MappingsLoader;
import net.lenni0451.commons.asm.mappings.loader.formats.TinyV2MappingsLoader;
import net.lenni0451.commons.asm.mappings.loader.formats.TsrgV1MappingsLoader;
import net.lenni0451.commons.asm.mappings.loader.formats.TsrgV2MappingsLoader;
import net.lenni0451.commons.asm.provider.ClassProvider;
import net.lenni0451.commons.asm.provider.DelegatingClassProvider;
import net.lenni0451.commons.asm.provider.LoaderClassProvider;
import net.lenni0451.commons.asm.provider.io.FileSystemClassProvider;
import net.lenni0451.commons.asm.provider.io.JarFileClassProvider;
import net.lenni0451.commons.asm.provider.io.PathClassProvider;
import net.lenni0451.jartransformer.transformers.Transformer;
import net.lenni0451.jartransformer.utils.RemapUtils;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Classpath;
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
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
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
        this.getWhitelist().convention(Set.of());
        this.getBlacklist().convention(Set.of());
        this.getRemapStrings().convention(false);
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

    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    @Input
    public abstract Property<Boolean> getReverse();

    @Input
    public abstract SetProperty<String> getWhitelist();

    @Input
    public abstract SetProperty<String> getBlacklist();

    @Input
    public abstract Property<Boolean> getRemapStrings();

    @Override
    public void transform(Logger log, FileSystem fileSystem) throws Throwable {
        Mappings mappings = this.loadMappings();
        if (this.getReverse().get()) mappings = mappings.reverse();
        if (mappings.isEmpty()) {
            log.warn("Loaded mappings are empty, nothing to remap");
            return;
        }
        boolean movedFiles = !mappings.getClassMappings().isEmpty() || !mappings.getPackageMappings().isEmpty();
        if (!mappings.getFieldMappings().isEmpty() || !mappings.getMethodMappings().isEmpty()) {
            this.fillSuperMembers(log, fileSystem, mappings);
        }
        this.remap(log, fileSystem, mappings);
        if (movedFiles) RemapUtils.removeEmptyDirectories(log, fileSystem);
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
        byte[] bytes = Files.readAllBytes(mappingFile.toPath());
        MappingsFormat format = switch (this.getFormat().get().toLowerCase(Locale.ROOT)) {
            case "auto" -> {
                MappingsFormat detected = MappingsFormat.detect(bytes);
                if (detected == null) throw new IllegalStateException("Could not detect the mapping file format. Please set the format option manually");
                yield detected;
            }
            case "proguard" -> MappingsFormat.PROGUARD;
            case "retroguard" -> MappingsFormat.RETROGUARD;
            case "srg", "xsrg" -> MappingsFormat.SRG;
            case "tsrg", "tsrg_v1" -> MappingsFormat.TSRG_V1;
            case "tsrg_v2" -> MappingsFormat.TSRG_V2;
            case "tiny", "tiny_v1" -> MappingsFormat.TINY_V1;
            case "tiny_v2" -> MappingsFormat.TINY_V2;
            default -> throw new IllegalArgumentException("Unknown mapping format: " + this.getFormat().get());
        };
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        return switch (format) {
            case PROGUARD -> new ProguardMappingsLoader(input);
            case RETROGUARD -> new RetroguardMappingsLoader(input);
            case SRG -> new SrgMappingsLoader(input);
            case TSRG_V1 -> new TsrgV1MappingsLoader(input);
            case TSRG_V2 -> new TsrgV2MappingsLoader(input, this.getFromNamespace().get(), this.getToNamespace().get());
            case TINY_V1 -> new TinyV1MappingsLoader(input, this.getFromNamespace().get(), this.getToNamespace().get());
            case TINY_V2 -> new TinyV2MappingsLoader(input, this.getFromNamespace().get(), this.getToNamespace().get());
        };
    }

    private void fillSuperMembers(final Logger log, final FileSystem fileSystem, final Mappings mappings) throws Exception {
        Set<String> classes = new HashSet<>();
        this.iterateFiles(fileSystem, path -> {
            if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class")) {
                try (InputStream is = Files.newInputStream(path)) {
                    classes.add(new ClassReader(is).getClassName());
                }
            }
        });
        try (ClassProvider classProvider = this.createClassProvider(fileSystem)) {
            ClassInfoProvider classInfoProvider = ClassInfoProvider.create(classProvider);
            MappingsFiller.fillAllSuperMembers(mappings, classInfoProvider, classes, (clazz, t) -> {
                log.warn("Failed to fill super members for class: {}", clazz, t);
                return true;
            });
        }
    }

    private ClassProvider createClassProvider(final FileSystem fileSystem) {
        List<ClassProvider> providers = new ArrayList<>();
        providers.add(new FileSystemClassProvider(fileSystem, false));
        List<File> jars = new ArrayList<>();
        for (File file : this.getClasspath()) {
            if (file.isDirectory()) {
                providers.add(new PathClassProvider(file.toPath()));
            } else if (file.isFile()) {
                jars.add(file);
            } else {
                throw new IllegalArgumentException("Classpath entry does not exist: " + file.getAbsolutePath());
            }
        }
        if (!jars.isEmpty()) providers.add(JarFileClassProvider.lazyDelegating(jars));
        providers.add(new LoaderClassProvider());
        return new DelegatingClassProvider(providers.toArray(new ClassProvider[0]));
    }

    private void remap(final Logger log, final FileSystem fileSystem, final Mappings mappings) throws IOException {
        Function<String, String> remapFunction = s -> RemapUtils.remapName(s, internal -> {
            String mapped = mappings.map(internal);
            return mapped.equals(internal) ? null : mapped;
        });
        Function<String, String> pathRemap = RemapUtils.servicePathRemap(this.createPathRemapFunction(mappings), remapFunction);
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
                        RemapUtils.remapServiceFile(log, path, pathString, remapFunction);
                        RemapUtils.remapManifestFile(log, path, remapFunction);
                        RemapUtils.remapLog4jPluginsFile(log, path, pathString, remapFunction);
                    }
                    RemapUtils.moveRemappedFile(log, fileSystem, path, pathString, slash, pathRemap);
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
            String fromPkg = fromSlash == -1 ? "" : entry.getKey().substring(0, fromSlash + 1);
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

    private void remapClass(final Logger log, final FileSystem fileSystem, final Path path, final String pathString, final boolean slash, final Mappings mappings, final Function<String, String> remapFunction) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        ClassNode node = ClassIO.fromBytes(bytes);
        String oldName = node.name;
        ClassNode remappedNode = Remapper.remap(node, mappings);
        if (this.getRemapStrings().get()) {
            RemapUtils.remapStrings(remappedNode, remapFunction);
        }
        if (!oldName.equals(remappedNode.name)) {
            String prefix = "";
            if (pathString.toLowerCase(Locale.ROOT).startsWith(RemapUtils.VERSIONS_PREFIX)) {
                prefix = pathString.substring(0, pathString.indexOf('/', RemapUtils.VERSIONS_PREFIX.length()) + 1);
            }
            Path newPath = fileSystem.getPath((slash ? "/" : "") + prefix + remappedNode.name + ".class");
            if (Files.exists(newPath)) {
                throw new IllegalStateException("Remap collision: " + path + " cannot be remapped to existing path " + newPath);
            }
            if (newPath.getParent() != null) Files.createDirectories(newPath.getParent());
            Files.write(newPath, ClassIO.toStacklessBytes(remappedNode));
            Files.delete(path);
            log.debug("Remapped class file: {} -> {}", path, newPath);
        } else {
            Files.write(path, ClassIO.toStacklessBytes(remappedNode));
            log.debug("Remapped class: {}", path);
        }
    }

}
