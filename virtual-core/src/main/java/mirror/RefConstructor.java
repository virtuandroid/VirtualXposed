package mirror;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

public class RefConstructor<T> {
    private Constructor<?> ctor;

    public RefConstructor(Class<?> cls, Field field) throws NoSuchMethodException {
        if (field.isAnnotationPresent(MethodParams.class)) {
            Class<?>[] types = field.getAnnotation(MethodParams.class).value();
            ctor = cls.getDeclaredConstructor(types);
        } else if (field.isAnnotationPresent(MethodReflectParams.class)) {
            String[] values = field.getAnnotation(MethodReflectParams.class).value();
            Class[] parameterTypes = new Class[values.length];
            int N = 0;
            while (N < values.length) {
                try {
                    parameterTypes[N] = classForName(values[N]);
                    N++;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            ctor = cls.getDeclaredConstructor(parameterTypes);
        } else {
            ctor = cls.getDeclaredConstructor();
        }
        if (ctor != null && !ctor.isAccessible()) {
            ctor.setAccessible(true);
        }
    }

    static Class<?> classForName(String className) throws ClassNotFoundException {
        if (className.equals("int")) {
            return Integer.TYPE;
        }
        if (className.equals("long")) {
            return Long.TYPE;
        }
        if (className.equals("boolean")) {
            return Boolean.TYPE;
        }
        if (className.equals("byte")) {
            return Byte.TYPE;
        }
        if (className.equals("short")) {
            return Short.TYPE;
        }
        if (className.equals("char")) {
            return Character.TYPE;
        }
        if (className.equals("float")) {
            return Float.TYPE;
        }
        if (className.equals("double")) {
            return Double.TYPE;
        }
        if (className.equals("void")) {
            return Void.TYPE;
        }
        return Class.forName(className);
    }

    public T newInstance() {
        try {
            return (T) ctor.newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    public T newInstance(Object... params) {
        try {
            return (T) ctor.newInstance(params);
        } catch (Exception e) {
            return null;
        }
    }
}
