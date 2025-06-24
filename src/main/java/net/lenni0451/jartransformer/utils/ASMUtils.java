package net.lenni0451.jartransformer.utils;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import javax.annotation.Nullable;

public class ASMUtils {

    public static ClassNode fromBytes(final byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    public static byte[] toBytes(final ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    public static ClassNode remap(final ClassNode classNode, final Remapper remapper) {
        ClassNode remappedNode = new ClassNode();
        classNode.accept(new ClassRemapper(remappedNode, remapper));
        return remappedNode;
    }

    @Nullable
    public static String remap(final PackageRemapper remapper, final String s) {
        try {
            if (s.contains("/")) {
                return remapper.mapUnchecked(s);
            } else {
                return remapper.mapUnchecked(s.replace('.', '/')).replace('/', '.');
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

}
