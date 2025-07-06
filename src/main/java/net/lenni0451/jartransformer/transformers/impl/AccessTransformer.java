package net.lenni0451.jartransformer.transformers.impl;

import lombok.extern.slf4j.Slf4j;
import net.lenni0451.commons.asm.ASMUtils;
import net.lenni0451.commons.asm.Modifiers;
import net.lenni0451.commons.asm.io.ClassIO;
import net.lenni0451.jartransformer.transformers.Transformer;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public abstract class AccessTransformer extends Transformer {

    private static final Pattern CLASS_REGEX = Pattern.compile("^(\\w[\\w/]+\\w)$|^L(\\w[\\w/]+\\w);$");
    public static final Pattern FIELD_REGEX = Pattern.compile("^(?>L([^;]+);|([^.]+)\\.)([^(]+):(.+)$");
    public static final Pattern METHOD_REGEX = Pattern.compile("^(?>L([^;]+);|([^.]+)\\.)([^(]+)(\\([^)]*\\).+)$");

    @Inject
    public AccessTransformer(final String name) {
        super(name);
        this.getAccessible().convention(Set.of());
        this.getMutable().convention(Set.of());
    }

    @Input
    public abstract SetProperty<String> getAccessible();

    @Input
    public abstract SetProperty<String> getMutable();

    @Input
    public abstract SetProperty<String> getFull();

    @Override
    public void transform(Logger log, FileSystem fileSystem) throws Throwable {
        List<ClassMutator> targets = new ArrayList<>();
        this.iterateEntries(targets, this.getAccessible().get(), access -> Modifiers.setAccess(access, Opcodes.ACC_PUBLIC));
        this.iterateEntries(targets, this.getMutable().get(), access -> Modifiers.remove(access, Opcodes.ACC_FINAL));
        this.iterateEntries(targets, this.getFull().get(), access -> {
            int out = Modifiers.setAccess(access, Opcodes.ACC_PUBLIC);
            out = Modifiers.remove(out, Opcodes.ACC_FINAL);
            return out;
        });
        this.iterateFiles(fileSystem, path -> {
            if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class")) {
                byte[] bytes = Files.readAllBytes(path);
                ClassNode node = ClassIO.fromBytes(bytes);
                boolean modified = false;
                for (ClassMutator target : targets) {
                    if (!target.className().equals(node.name)) continue;
                    target.mutate(node);
                    modified = true;
                }
                if (modified) {
                    Files.write(path, ClassIO.toStacklessBytes(node));
                    log.debug("Processed access transformer for class: {}", path);
                }
            }
        });
    }

    private void iterateEntries(final List<ClassMutator> targets, final Set<String> entries, final AccessMutator mutator) {
        for (String s : entries) {
            Matcher matcher = CLASS_REGEX.matcher(s);
            if (matcher.matches()) {
                String className = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                targets.add(new ClassTarget(className, classNode -> {
                    classNode.access = mutator.mutate(classNode.access);
                }));
                continue;
            }
            matcher = FIELD_REGEX.matcher(s);
            if (matcher.matches()) {
                String className = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                String fieldName = matcher.group(3);
                String fieldDesc = matcher.group(4);
                targets.add(new FieldTarget(className, fieldName, fieldDesc, fieldNode -> {
                    fieldNode.access = mutator.mutate(fieldNode.access);
                }));
                continue;
            }
            matcher = METHOD_REGEX.matcher(s);
            if (matcher.matches()) {
                String className = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                String methodName = matcher.group(3);
                String methodDesc = matcher.group(4);
                targets.add(new MethodTarget(className, methodName, methodDesc, methodNode -> {
                    methodNode.access = mutator.mutate(methodNode.access);
                }));
                continue;
            }
            throw new IllegalArgumentException("Invalid target: " + s);
        }
    }


    private interface ClassMutator {
        String className();

        void mutate(final ClassNode classNode);
    }

    private record ClassTarget(String className, Consumer<ClassNode> mutator) implements ClassMutator {
        @Override
        public void mutate(ClassNode classNode) {
            this.mutator.accept(classNode);
        }
    }

    private record FieldTarget(String className, String fieldName, String fieldDesc, Consumer<FieldNode> mutator) implements ClassMutator {
        @Override
        public void mutate(ClassNode classNode) {
            FieldNode fieldNode = ASMUtils.getField(classNode, this.fieldName, this.fieldDesc);
            if (fieldNode == null) throw new IllegalArgumentException("Field '" + this.fieldName + ":" + this.fieldDesc + "' not found in class '" + this.className + "'");
            this.mutator.accept(fieldNode);
        }
    }

    private record MethodTarget(String className, String methodName, String methodDesc, Consumer<MethodNode> mutator) implements ClassMutator {
        @Override
        public void mutate(ClassNode classNode) {
            MethodNode methodNode = ASMUtils.getMethod(classNode, this.methodName, this.methodDesc);
            if (methodNode == null) throw new IllegalArgumentException("Method '" + this.methodName + this.methodDesc + "' not found in class '" + this.className + "'");
            this.mutator.accept(methodNode);
        }
    }

    @FunctionalInterface
    private interface AccessMutator {
        int mutate(final int access);
    }

}
