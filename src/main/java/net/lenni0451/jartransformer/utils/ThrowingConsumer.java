package net.lenni0451.jartransformer.utils;

@FunctionalInterface
public interface ThrowingConsumer<T> {

    void accept(final T t) throws Throwable;

}
