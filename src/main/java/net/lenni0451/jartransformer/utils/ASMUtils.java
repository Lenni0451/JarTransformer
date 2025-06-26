package net.lenni0451.jartransformer.utils;

import net.lenni0451.commons.asm.annotations.AnnotationUtils;
import org.objectweb.asm.tree.*;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Function;

public class ASMUtils {

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

    public static boolean mutateStrings(final ClassNode node, final Function<String, String> mutator) {
        boolean[] mutated = {false};
        AnnotationUtils.forEach(node, annotation -> mutateAnnotationStrings(annotation, mutator, mutated));
        for (FieldNode field : node.fields) {
            AnnotationUtils.forEach(field, annotation -> mutateAnnotationStrings(annotation, mutator, mutated));
            if (field.value instanceof String s) {
                String newValue = mutator.apply(s);
                if (newValue != null && !newValue.equals(s)) {
                    field.value = newValue;
                    mutated[0] = true;
                }
            }
        }
        for (MethodNode method : node.methods) {
            AnnotationUtils.forEach(method, annotation -> mutateAnnotationStrings(annotation, mutator, mutated));
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                    String newValue = mutator.apply(s);
                    if (newValue != null && !newValue.equals(s)) {
                        ldc.cst = newValue;
                        mutated[0] = true;
                    }
                } else if (insn instanceof InvokeDynamicInsnNode idin) {
                    if (idin.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory") && idin.bsm.getName().equals("makeConcatWithConstants")) {
                        for (int i = 0; i < idin.bsmArgs.length; i++) {
                            if (idin.bsmArgs[i] instanceof String s) {
                                String newValue = mutator.apply(s);
                                if (newValue != null && !newValue.equals(s)) {
                                    idin.bsmArgs[i] = newValue;
                                    mutated[0] = true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return mutated[0];
    }

    private static void mutateAnnotationStrings(final AnnotationNode annotation, final Function<String, String> mutator, final boolean[] mutated) {
        if (annotation.values == null) return;
        for (int i = 1; i < annotation.values.size(); i += 2) {
            Object value = annotation.values.get(i);
            if (value instanceof String s) {
                String newValue = mutator.apply(s);
                if (newValue != null && !newValue.equals(s)) {
                    annotation.values.set(i, newValue);
                    mutated[0] = true;
                }
            } else if (value instanceof AnnotationNode other) {
                mutateAnnotationStrings(other, mutator, mutated);
            } else if (value instanceof List list) {
                for (int l = 0; l < list.size(); l++) {
                    Object listValue = list.get(l);
                    if (listValue instanceof String s) {
                        String newValue = mutator.apply(s);
                        if (newValue != null && !newValue.equals(s)) {
                            list.set(l, newValue);
                            mutated[0] = true;
                        }
                    } else if (listValue instanceof AnnotationNode other) {
                        mutateAnnotationStrings(other, mutator, mutated);
                    }
                }
            }
        }
    }

}
