package com.spw.miniplayer;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 宿主（Salt Player for Windows）内部类的加载与反射工具。
 *
 * <p>插件类加载器会把 {@code com.xuncorp.*} 等宿主包交给父加载器，
 * 但为了在不同版本/不同加载策略下都能拿到宿主类，这里依次尝试
 * 线程上下文、插件自身、系统等多个类加载器。
 */
final class HostReflection {

    private HostReflection() {
    }

    /** 依序尝试的类加载器（去重）。 */
    private static Set<ClassLoader> candidates() {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        try {
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            if (tccl != null) {
                loaders.add(tccl);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            ClassLoader own = HostReflection.class.getClassLoader();
            if (own != null) {
                // 先把插件自身的加载器链全部加入
                ClassLoader current = own;
                while (current != null) {
                    loaders.add(current);
                    current = current.getParent();
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            ClassLoader system = ClassLoader.getSystemClassLoader();
            if (system != null) {
                loaders.add(system);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return loaders;
    }

    /**
     * 加载宿主类，找不到返回 null。
     *
     * @param className 宿主类全限定名
     */
    static Class<?> loadHostClass(String className) {
        for (ClassLoader loader : candidates()) {
            try {
                return Class.forName(className, false, loader);
            } catch (Throwable ignored) {
                // 换下一个加载器
            }
        }
        try {
            return Class.forName(className, false, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 读取 public static 字段。 */
    static Object staticField(Class<?> type, String name) {
        try {
            return type.getField(name).get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 查找方法：优先 public，其次 declared（含父类），按名称精确匹配，参数类型宽松匹配。
     */
    static java.lang.reflect.Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (Throwable ignored) {
            // 继续按名称扫描
        }
        Class<?> current = type;
        while (current != null) {
            for (java.lang.reflect.Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)) {
                    continue;
                }
                Class<?>[] actual = method.getParameterTypes();
                if (actual.length != params.length) {
                    continue;
                }
                boolean compatible = true;
                for (int i = 0; i < actual.length; i++) {
                    if (!actual[i].isAssignableFrom(params[i])
                            && !params[i].isAssignableFrom(actual[i])) {
                        // 基本类型也要能对上
                        if (!(actual[i].isPrimitive() || params[i].isPrimitive())) {
                            compatible = false;
                            break;
                        }
                    }
                }
                if (compatible) {
                    try {
                        method.setAccessible(true);
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
