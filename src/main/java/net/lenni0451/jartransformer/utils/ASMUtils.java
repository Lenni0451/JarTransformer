package net.lenni0451.jartransformer.utils;

import javax.annotation.Nullable;

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

}
