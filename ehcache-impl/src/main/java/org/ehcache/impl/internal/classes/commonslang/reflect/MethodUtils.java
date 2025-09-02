/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * This is a modified version of the original Apache class.  It has had unused
 * members removed.
 */
package org.ehcache.impl.internal.classes.commonslang.reflect;

import org.ehcache.impl.internal.classes.commonslang.ArrayUtils;
import org.ehcache.impl.internal.classes.commonslang.ClassUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utility reflection methods focused on {@link Method}s, originally from Commons BeanUtils.
 * Differences from the BeanUtils version may be noted, especially where similar functionality
 * already existed within Lang.
 *
 * <h2>Known Limitations</h2>
 * <h3>Accessing Public Methods In A Default Access Superclass</h3>
 * <p>There is an issue when invoking {@code public} methods contained in a default access superclass on JREs prior to 1.4.
 * Reflection locates these methods fine and correctly assigns them as {@code public}.
 * However, an {@link IllegalAccessException} is thrown if the method is invoked.</p>
 *
 * <p>
 * {@link MethodUtils} contains a workaround for this situation.
 * It will attempt to call {@link java.lang.reflect.AccessibleObject#setAccessible(boolean)} on this method.
 * If this call succeeds, then the method can be invoked as normal.
 * This call will only succeed when the application has sufficient security privileges.
 * If this call fails then the method may fail.
 * </p>
 *
 * @since 2.5
 */
public class MethodUtils {

    private static final Comparator<Method> METHOD_BY_SIGNATURE = Comparator.comparing(Method::toString);

    /**
     * Computes the aggregate number of inheritance hops between assignable argument class types.  Returns -1
     * if the arguments aren't assignable.  Fills a specific purpose for getMatchingMethod and is not generalized.
     *
     * @param fromClassArray the Class array to calculate the distance from.
     * @param toClassArray the Class array to calculate the distance to.
     * @return the aggregate number of inheritance hops between assignable argument class types.
     */
    private static int distance(final Class<?>[] fromClassArray, final Class<?>[] toClassArray) {
        int answer = 0;
        if (!ClassUtils.isAssignable(fromClassArray, toClassArray, true)) {
            return -1;
        }
        for (int offset = 0; offset < fromClassArray.length; offset++) {
            // Note InheritanceUtils.distance() uses different scoring system.
            final Class<?> aClass = fromClassArray[offset];
            final Class<?> toClass = toClassArray[offset];
            if (aClass == null || aClass.equals(toClass)) {
                continue;
            }
            if (ClassUtils.isAssignable(aClass, toClass, true) && !ClassUtils.isAssignable(aClass, toClass, false)) {
                answer++;
            } else {
                answer += 2;
            }
        }
        return answer;
    }

    /**
     * Gets an accessible method (that is, one that can be invoked via reflection) that implements the specified Method. If no such method can be found, return
     * {@code null}.
     *
     * @param cls The implementing class, may be null.
     * @param method The method that we wish to call, may be null.
     * @return The accessible method or null.
     * @since 3.19.0
     */
    public static Method getAccessibleMethod(final Class<?> cls, final Method method) {
        if (!MemberUtils.isPublic(method)) {
            return null;
        }
        // If the declaring class is public, we are done
        if (ClassUtils.isPublic(cls)) {
            return method;
        }
        final String methodName = method.getName();
        final Class<?>[] parameterTypes = method.getParameterTypes();
        // Check the implemented interfaces and subinterfaces
        final Method method2 = getAccessibleMethodFromInterfaceNest(cls, methodName, parameterTypes);
        // Check the superclass chain
        return method2 != null ? method2 : getAccessibleMethodFromSuperclass(cls, methodName, parameterTypes);
    }

    /**
     * Gets an accessible method (that is, one that can be invoked via reflection) with given name and parameters. If no such method can be found, return
     * {@code null}. This is just a convenience wrapper for {@link #getAccessibleMethod(Method)}.
     *
     * @param cls            get method from this class.
     * @param methodName     get method with this name.
     * @param parameterTypes with these parameters types.
     * @return The accessible method.
     */
    public static Method getAccessibleMethod(final Class<?> cls, final String methodName, final Class<?>... parameterTypes) {
        return getAccessibleMethod(getMethodObject(cls, methodName, parameterTypes));
    }

    /**
     * Gets an accessible method (that is, one that can be invoked via reflection) that implements the specified Method. If no such method can be found, return
     * {@code null}.
     *
     * @param method The method that we wish to call, may be null.
     * @return The accessible method
     */
    public static Method getAccessibleMethod(final Method method) {
        return method != null ? getAccessibleMethod(method.getDeclaringClass(), method) : null;
    }

    /**
     * Gets an accessible method (that is, one that can be invoked via
     * reflection) that implements the specified method, by scanning through
     * all implemented interfaces and subinterfaces. If no such method
     * can be found, return {@code null}.
     *
     * <p>
     * There isn't any good reason why this method must be {@code private}.
     * It is because there doesn't seem any reason why other classes should
     * call this rather than the higher level methods.
     * </p>
     *
     * @param cls Parent class for the interfaces to be checked
     * @param methodName Method name of the method we wish to call
     * @param parameterTypes The parameter type signatures
     * @return the accessible method or {@code null} if not found
     */
    private static Method getAccessibleMethodFromInterfaceNest(Class<?> cls, final String methodName, final Class<?>... parameterTypes) {
        // Search up the superclass chain
        for (; cls != null; cls = cls.getSuperclass()) {
            // Check the implemented interfaces of the parent class
            final Class<?>[] interfaces = cls.getInterfaces();
            for (final Class<?> anInterface : interfaces) {
                // Is this interface public?
                if (!ClassUtils.isPublic(anInterface)) {
                    continue;
                }
                // Does the method exist on this interface?
                try {
                    return anInterface.getDeclaredMethod(methodName, parameterTypes);
                } catch (final NoSuchMethodException ignored) {
                    /*
                     * Swallow, if no method is found after the loop then this method returns null.
                     */
                }
                // Recursively check our parent interfaces
                final Method method = getAccessibleMethodFromInterfaceNest(anInterface, methodName, parameterTypes);
                if (method != null) {
                    return method;
                }
            }
        }
        return null;
    }

    /**
     * Gets an accessible method (that is, one that can be invoked via
     * reflection) by scanning through the superclasses. If no such method
     * can be found, return {@code null}.
     *
     * @param cls Class to be checked.
     * @param methodName Method name of the method we wish to call.
     * @param parameterTypes The parameter type signatures.
     * @return the accessible method or {@code null} if not found.
     */
    private static Method getAccessibleMethodFromSuperclass(final Class<?> cls, final String methodName, final Class<?>... parameterTypes) {
        Class<?> parentClass = cls.getSuperclass();
        while (parentClass != null) {
            if (ClassUtils.isPublic(parentClass)) {
                return getMethodObject(parentClass, methodName, parameterTypes);
            }
            parentClass = parentClass.getSuperclass();
        }
        return null;
    }

    /**
     * Gets a combination of {@link ClassUtils#getAllSuperclasses(Class)} and
     * {@link ClassUtils#getAllInterfaces(Class)}, one from superclasses, one
     * from interfaces, and so on in a breadth first way.
     *
     * @param cls  the class to look up, may be {@code null}
     * @return the combined {@link List} of superclasses and interfaces in order
     * going up from this one
     *  {@code null} if null input
     */
    private static List<Class<?>> getAllSuperclassesAndInterfaces(final Class<?> cls) {
        if (cls == null) {
            return null;
        }
        final List<Class<?>> allSuperClassesAndInterfaces = new ArrayList<>();
        final List<Class<?>> allSuperclasses = ClassUtils.getAllSuperclasses(cls);
        int superClassIndex = 0;
        final List<Class<?>> allInterfaces = ClassUtils.getAllInterfaces(cls);
        int interfaceIndex = 0;
        while (interfaceIndex < allInterfaces.size() || superClassIndex < allSuperclasses.size()) {
            final Class<?> acls;
            if (interfaceIndex >= allInterfaces.size() || superClassIndex < allSuperclasses.size() && superClassIndex < interfaceIndex) {
                acls = allSuperclasses.get(superClassIndex++);
            } else {
                acls = allInterfaces.get(interfaceIndex++);
            }
            allSuperClassesAndInterfaces.add(acls);
        }
        return allSuperClassesAndInterfaces;
    }

    /**
     * Gets an accessible method that matches the given name and has compatible parameters. Compatible parameters mean that every method parameter is assignable
     * from the given parameters. In other words, it finds a method with the given name that will take the parameters given.
     *
     * <p>
     * This method is used by {@link #invokeMethod(Object object, String methodName, Object[] args, Class[] parameterTypes)}.
     * </p>
     * <p>
     * This method can match primitive parameter by passing in wrapper classes. For example, a {@link Boolean} will match a primitive {@code boolean} parameter.
     * </p>
     *
     * @param cls            find method in this class.
     * @param methodName     find method with this name.
     * @param parameterTypes find method with most compatible parameters.
     * @return The accessible method or null.
     * @throws SecurityException if an underlying accessible object's method denies the request.
     * @see SecurityManager#checkPermission
     */
    public static Method getMatchingAccessibleMethod(final Class<?> cls, final String methodName, final Class<?>... parameterTypes) {
        final Method candidate = getMethodObject(cls, methodName, parameterTypes);
        if (candidate != null) {
            return MemberUtils.setAccessibleWorkaround(candidate);
        }
        // search through all methods
        final Method[] methods = cls.getMethods();
        final List<Method> matchingMethods = Stream.of(methods)
                .filter(method -> method.getName().equals(methodName) && MemberUtils.isMatchingMethod(method, parameterTypes)).collect(Collectors.toList());
        // Sort methods by signature to force deterministic result
        matchingMethods.sort(METHOD_BY_SIGNATURE);
        Method bestMatch = null;
        for (final Method method : matchingMethods) {
            // get accessible version of method
            final Method accessibleMethod = getAccessibleMethod(method);
            if (accessibleMethod != null && (bestMatch == null || MemberUtils.compareMethodFit(accessibleMethod, bestMatch, parameterTypes) < 0)) {
                bestMatch = accessibleMethod;
            }
        }
        if (bestMatch != null) {
            MemberUtils.setAccessibleWorkaround(bestMatch);
        }
        if (bestMatch != null && bestMatch.isVarArgs() && bestMatch.getParameterTypes().length > 0 && parameterTypes.length > 0) {
            final Class<?>[] methodParameterTypes = bestMatch.getParameterTypes();
            final Class<?> methodParameterComponentType = methodParameterTypes[methodParameterTypes.length - 1].getComponentType();
            final String methodParameterComponentTypeName = ClassUtils.primitiveToWrapper(methodParameterComponentType).getName();
            final Class<?> lastParameterType = parameterTypes[parameterTypes.length - 1];
            final String parameterTypeName = lastParameterType == null ? null : lastParameterType.getName();
            final String parameterTypeSuperClassName = lastParameterType == null ? null
                    : lastParameterType.getSuperclass() != null ? lastParameterType.getSuperclass().getName() : null;
            if (parameterTypeName != null && parameterTypeSuperClassName != null && !methodParameterComponentTypeName.equals(parameterTypeName)
                    && !methodParameterComponentTypeName.equals(parameterTypeSuperClassName)) {
                return null;
            }
        }
        return bestMatch;
    }

    /**
     * Gets a Method, or {@code null} if a documented {@link Class#getMethod(String, Class...) } exception is thrown.
     *
     * @param cls            Receiver for {@link Class#getMethod(String, Class...)}.
     * @param name           the name of the method.
     * @param parameterTypes the list of parameters.
     * @return a Method or {@code null}.
     * @see SecurityManager#checkPermission
     * @see Class#getMethod(String, Class...)
     * @since 3.15.0
     */
    public static Method getMethodObject(final Class<?> cls, final String name, final Class<?>... parameterTypes) {
        try {
            return name != null && cls != null ? cls.getMethod(name, parameterTypes) : null;
        } catch (final NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    /**
     * Gets all class level public methods of the given class that are annotated with the given annotation.
     * @param cls
     *            the {@link Class} to query
     * @param annotationCls
     *            the {@link Annotation} that must be present on a method to be matched
     * @return a list of Methods (possibly empty).
     * @throws NullPointerException
     *            if the class or annotation are {@code null}
     * @since 3.4
     */
    public static List<Method> getMethodsListWithAnnotation(final Class<?> cls, final Class<? extends Annotation> annotationCls) {
        return getMethodsListWithAnnotation(cls, annotationCls, false, false);
    }

    /**
     * Gets all methods of the given class that are annotated with the given annotation.
     *
     * @param cls
     *            the {@link Class} to query
     * @param annotationCls
     *            the {@link Annotation} that must be present on a method to be matched
     * @param searchSupers
     *            determines if a lookup in the entire inheritance hierarchy of the given class should be performed
     * @param ignoreAccess
     *            determines if non-public methods should be considered
     * @return a list of Methods (possibly empty).
     * @throws NullPointerException if either the class or annotation class is {@code null}
     * @since 3.6
     */
    public static List<Method> getMethodsListWithAnnotation(final Class<?> cls, final Class<? extends Annotation> annotationCls, final boolean searchSupers,
            final boolean ignoreAccess) {
        Objects.requireNonNull(cls, "cls");
        Objects.requireNonNull(annotationCls, "annotationCls");
        final List<Class<?>> classes = searchSupers ? getAllSuperclassesAndInterfaces(cls) : new ArrayList<>();
        classes.add(0, cls);
        final List<Method> annotatedMethods = new ArrayList<>();
        classes.forEach(acls -> {
            final Method[] methods = ignoreAccess ? acls.getDeclaredMethods() : acls.getMethods();
            Stream.of(methods).filter(method -> method.isAnnotationPresent(annotationCls)).forEachOrdered(annotatedMethods::add);
        });
        return annotatedMethods;
    }

    /**
     * Gets all class level public methods of the given class that are annotated with the given annotation.
     *
     * @param cls
     *            the {@link Class} to query
     * @param annotationCls
     *            the {@link java.lang.annotation.Annotation} that must be present on a method to be matched
     * @return an array of Methods (possibly empty).
     * @throws NullPointerException if the class or annotation are {@code null}
     * @since 3.4
     */
    public static Method[] getMethodsWithAnnotation(final Class<?> cls, final Class<? extends Annotation> annotationCls) {
        return getMethodsWithAnnotation(cls, annotationCls, false, false);
    }

    /**
     * Gets all methods of the given class that are annotated with the given annotation.
     *
     * @param cls
     *            the {@link Class} to query
     * @param annotationCls
     *            the {@link java.lang.annotation.Annotation} that must be present on a method to be matched
     * @param searchSupers
     *            determines if a lookup in the entire inheritance hierarchy of the given class should be performed
     * @param ignoreAccess
     *            determines if non-public methods should be considered
     * @return an array of Methods (possibly empty).
     * @throws NullPointerException if the class or annotation are {@code null}
     * @since 3.6
     */
    public static Method[] getMethodsWithAnnotation(final Class<?> cls, final Class<? extends Annotation> annotationCls, final boolean searchSupers,
            final boolean ignoreAccess) {
        return getMethodsListWithAnnotation(cls, annotationCls, searchSupers, ignoreAccess).toArray(ArrayUtils.EMPTY_METHOD_ARRAY);
    }

    /**
     * Gets an array of arguments in the canonical form, given an arguments array passed to a varargs method,
     * for example an array with the declared number of parameters, and whose last parameter is an array of the varargs type.
     *
     * @param args the array of arguments passed to the varags method
     * @param methodParameterTypes the declared array of method parameter types
     * @return an array of the variadic arguments passed to the method
     * @since 3.5
     */
    static Object[] getVarArgs(final Object[] args, final Class<?>[] methodParameterTypes) {
        final int mptLength = methodParameterTypes.length;
        if (args.length == mptLength) {
            final Object lastArg = args[args.length - 1];
            if (lastArg == null || lastArg.getClass().equals(methodParameterTypes[mptLength - 1])) {
                // The args array is already in the canonical form for the method.
                return args;
            }
        }
        // Construct a new array matching the method's declared parameter types.
        // Copy the normal (non-varargs) parameters
        final Object[] newArgs = ArrayUtils.arraycopy(args, 0, 0, mptLength - 1, () -> new Object[mptLength]);
        // Construct a new array for the variadic parameters
        final Class<?> varArgComponentType = methodParameterTypes[mptLength - 1].getComponentType();
        final int varArgLength = args.length - mptLength + 1;
        // Copy the variadic arguments into the varargs array.
        Object varArgsArray = ArrayUtils.arraycopy(args, mptLength - 1, 0, varArgLength,
                s -> Array.newInstance(ClassUtils.primitiveToWrapper(varArgComponentType), varArgLength));
        if (varArgComponentType.isPrimitive()) {
            // unbox from wrapper type to primitive type
            varArgsArray = ArrayUtils.toPrimitive(varArgsArray);
        }
        // Store the varargs array in the last position of the array to return
        newArgs[mptLength - 1] = varArgsArray;
        // Return the canonical varargs array.
        return newArgs;
    }

    /**
     * Invokes a method whose parameter types match exactly the object type.
     *
     * <p>
     * This uses reflection to invoke the method obtained from a call to {@link #getAccessibleMethod(Class, String, Class[])}.
     * </p>
     *
     * @param object     invoke method on this object.
     * @param methodName get method with this name.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws NullPointerException        Thrown if the specified {@code object} is null.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     * @since 3.4
     */
    public static Object invokeExactMethod(final Object object, final String methodName)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        return invokeExactMethod(object, methodName, ArrayUtils.EMPTY_OBJECT_ARRAY, null);
    }

    /**
     * Invokes a method whose parameter types match exactly the object types.
     *
     * <p>
     * This uses reflection to invoke the method obtained from a call to {@link #getAccessibleMethod(Class, String, Class[])}.
     * </p>
     *
     * @param object     invoke method on this object.
     * @param methodName get method with this name.
     * @param args       use these arguments - treat null as empty array.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws NullPointerException        Thrown if the specified {@code object} is null.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     */
    public static Object invokeExactMethod(final Object object, final String methodName, final Object... args)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Object[] actuals = ArrayUtils.nullToEmpty(args);
        return invokeExactMethod(object, methodName, actuals, ClassUtils.toClass(actuals));
    }

    /**
     * Invokes a method whose parameter types match exactly the parameter types given.
     *
     * <p>
     * This uses reflection to invoke the method obtained from a call to {@link #getAccessibleMethod(Class, String, Class[])}.
     * </p>
     *
     * @param object         Invokes a method on this object.
     * @param methodName     Gets a method with this name.
     * @param args           Method arguments - treat null as empty array.
     * @param parameterTypes Match these parameters - treat {@code null} as empty array.
     * @return The value returned by the invoked method.
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws NullPointerException        Thrown if the specified {@code object} is null.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     */
    public static Object invokeExactMethod(final Object object, final String methodName, final Object[] args, final Class<?>[] parameterTypes)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Class<?> cls = Objects.requireNonNull(object, "object").getClass();
        final Method method = getAccessibleMethod(cls, methodName, ArrayUtils.nullToEmpty(parameterTypes));
        if (method == null) {
            throw new NoSuchMethodException("No such accessible method: " + methodName + "() on object: " + cls.getName());
        }
        return method.invoke(object, ArrayUtils.nullToEmpty(args));
    }

    /**
     * Invokes a {@code static} method whose parameter types match exactly the object types.
     *
     * <p>
     * This uses reflection to invoke the method obtained from a call to {@link #getAccessibleMethod(Class, String, Class[])}.
     * </p>
     *
     * @param cls        invoke static method on this class
     * @param methodName get method with this name
     * @param args       use these arguments - treat {@code null} as empty array
     * @return The value returned by the invoked method
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     */
    public static Object invokeExactStaticMethod(final Class<?> cls, final String methodName, final Object... args)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Object[] actuals = ArrayUtils.nullToEmpty(args);
        return invokeExactStaticMethod(cls, methodName, actuals, ClassUtils.toClass(actuals));
    }

    /**
     * Invokes a {@code static} method whose parameter types match exactly the parameter types given.
     *
     * <p>
     * This uses reflection to invoke the method obtained from a call to {@link #getAccessibleMethod(Class, String, Class[])}.
     * </p>
     *
     * @param cls            invoke static method on this class
     * @param methodName     get method with this name
     * @param args           use these arguments - treat {@code null} as empty array
     * @param parameterTypes match these parameters - treat {@code null} as empty array
     * @return The value returned by the invoked method
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     */
    public static Object invokeExactStaticMethod(final Class<?> cls, final String methodName, final Object[] args, final Class<?>[] parameterTypes)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Method method = getAccessibleMethod(cls, methodName, ArrayUtils.nullToEmpty(parameterTypes));
        if (method == null) {
            throw new NoSuchMethodException("No such accessible method: " + methodName + "() on class: " + cls.getName());
        }
        return method.invoke(null, ArrayUtils.nullToEmpty(args));
    }

    /**
     * Invokes a named method without parameters.
     *
     * <p>
     * This is a convenient wrapper for
     * {@link #invokeMethod(Object object, boolean forceAccess, String methodName, Object[] args, Class[] parameterTypes)}.
     * </p>
     *
     * @param object invoke method on this object
     * @param forceAccess force access to invoke method even if it's not accessible
     * @param methodName get method with this name
     * @return The value returned by the invoked method
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws NullPointerException        Thrown if the specified {@code object} is null.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     * @see SecurityManager#checkPermission
     * @since 3.5
     */
    public static Object invokeMethod(final Object object, final boolean forceAccess, final String methodName)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        return invokeMethod(object, forceAccess, methodName, ArrayUtils.EMPTY_OBJECT_ARRAY, null);
    }

    /**
     * Invokes a named method whose parameter type matches the object type.
     *
     * <p>
     * This method supports calls to methods taking primitive parameters
     * via passing in wrapping classes. So, for example, a {@link Boolean} object
     * would match a {@code boolean} primitive.
     * </p>
     * <p>
     * This is a convenient wrapper for
     * {@link #invokeMethod(Object object, boolean forceAccess, String methodName, Object[] args, Class[] parameterTypes)}.
     * </p>
     *
     * @param object invoke method on this object
     * @param forceAccess force access to invoke method even if it's not accessible
     * @param methodName get method with this name
     * @param args use these arguments - treat null as empty array
     * @return The value returned by the invoked method
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws NullPointerException        Thrown if the specified {@code object} is null.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     * @see SecurityManager#checkPermission
     * @since 3.5
     */
    public static Object invokeMethod(final Object object, final boolean forceAccess, final String methodName, final Object... args)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Object[] actuals = ArrayUtils.nullToEmpty(args);
        return invokeMethod(object, forceAccess, methodName, actuals, ClassUtils.toClass(actuals));
    }

    /**
     * Invokes a named method without parameters.
     *
     * <p>
     * This method delegates the method search to {@link #getMatchingAccessibleMethod(Class, String, Class[])}.
     * </p>
     * <p>
     * This is a convenient wrapper for
     * {@link #invokeMethod(Object object, String methodName, Object[] args, Class[] parameterTypes)}.
     * </p>
     *
     * @param object invoke method on this object
     * @param methodName get method with this name
     * @return The value returned by the invoked method
     * @throws NoSuchMethodException if there is no such accessible method
     * @throws InvocationTargetException wraps an exception thrown by the method invoked
     * @throws IllegalAccessException if the requested method is not accessible via reflection
     * @throws SecurityException if an underlying accessible object's method denies the request.
     * @see SecurityManager#checkPermission
     * @since 3.4
     */
    public static Object invokeMethod(final Object object, final String methodName) throws NoSuchMethodException,
            IllegalAccessException, InvocationTargetException {
        return invokeMethod(object, methodName, ArrayUtils.EMPTY_OBJECT_ARRAY, null);
    }

    /**
     * Invokes a named method whose parameter type matches the object type.
     *
     * <p>
     * This method delegates the method search to {@link #getMatchingAccessibleMethod(Class, String, Class[])}.
     * </p>
     * <p>
     * This method supports calls to methods taking primitive parameters
     * via passing in wrapping classes. So, for example, a {@link Boolean} object
     * would match a {@code boolean} primitive.
     * </p>
     * <p>
     * This is a convenient wrapper for
     * {@link #invokeMethod(Object object, String methodName, Object[] args, Class[] parameterTypes)}.
     * </p>
     *
     * @param object invoke method on this object
     * @param methodName get method with this name
     * @param args use these arguments - treat null as empty array
     * @return The value returned by the invoked method
     * @throws NoSuchMethodException if there is no such accessible method
     * @throws InvocationTargetException wraps an exception thrown by the method invoked
     * @throws IllegalAccessException if the requested method is not accessible via reflection
     * @throws NullPointerException if the object or method name are {@code null}
     * @throws SecurityException if an underlying accessible object's method denies the request.
     * @see SecurityManager#checkPermission
     */
    public static Object invokeMethod(final Object object, final String methodName, final Object... args)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Object[] actuals = ArrayUtils.nullToEmpty(args);
        return invokeMethod(object, methodName, actuals, ClassUtils.toClass(actuals));
    }

    /**
     * Invokes a named method whose parameter type matches the object type.
     *
     * <p>
     * This method delegates the method search to {@link #getMatchingAccessibleMethod(Class, String, Class[])}.
     * </p>
     * <p>
     * This method supports calls to methods taking primitive parameters
     * via passing in wrapping classes. So, for example, a {@link Boolean} object
     * would match a {@code boolean} primitive.
     * </p>
     *
     * @param object invoke method on this object
     * @param methodName get method with this name
     * @param args use these arguments - treat null as empty array
     * @param parameterTypes match these parameters - treat null as empty array
     * @return The value returned by the invoked method
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws NullPointerException        Thrown if the specified {@code object} is null.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     * @see SecurityManager#checkPermission
     */
    public static Object invokeMethod(final Object object, final String methodName, final Object[] args, final Class<?>[] parameterTypes)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        return invokeMethod(object, false, methodName, args, parameterTypes);
    }

    /**
     * Invokes a named {@code static} method whose parameter type matches the object type.
     *
     * <p>
     * This method delegates the method search to {@link #getMatchingAccessibleMethod(Class, String, Class[])}.
     * </p>
     * <p>
     * This method supports calls to methods taking primitive parameters
     * via passing in wrapping classes. So, for example, a {@link Boolean} class
     * would match a {@code boolean} primitive.
     * </p>
     * <p>
     * This is a convenient wrapper for
     * {@link #invokeStaticMethod(Class, String, Object[], Class[])}.
     * </p>
     *
     * @param cls invoke static method on this class
     * @param methodName get method with this name
     * @param args use these arguments - treat {@code null} as empty array
     * @return The value returned by the invoked method
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     * @see SecurityManager#checkPermission
     */
    public static Object invokeStaticMethod(final Class<?> cls, final String methodName, final Object... args)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Object[] actuals = ArrayUtils.nullToEmpty(args);
        return invokeStaticMethod(cls, methodName, actuals, ClassUtils.toClass(actuals));
    }

    /**
     * Invokes a named {@code static} method whose parameter type matches the object type.
     *
     * <p>
     * This method delegates the method search to {@link #getMatchingAccessibleMethod(Class, String, Class[])}.
     * </p>
     * <p>
     * This method supports calls to methods taking primitive parameters
     * via passing in wrapping classes. So, for example, a {@link Boolean} class
     * would match a {@code boolean} primitive.
     * </p>
     *
     * @param cls invoke static method on this class
     * @param methodName get method with this name
     * @param args use these arguments - treat {@code null} as empty array
     * @param parameterTypes match these parameters - treat {@code null} as empty array
     * @return The value returned by the invoked method
     * @throws NoSuchMethodException       Thrown if there is no such accessible method.
     * @throws IllegalAccessException      Thrown if this found {@code Method} is enforcing Java language access control and the underlying method is
     *                                     inaccessible.
     * @throws IllegalArgumentException    Thrown if:
     *                                     <ul>
     *                                     <li>the found {@code Method} is an instance method and the specified {@code object} argument is not an instance of
     *                                     the class or interface declaring the underlying method (or of a subclass or interface implementor);</li>
     *                                     <li>the number of actual and formal parameters differ;</li>
     *                                     <li>an unwrapping conversion for primitive arguments fails; or</li>
     *                                     <li>after possible unwrapping, a parameter value can't be converted to the corresponding formal parameter type by a
     *                                     method invocation conversion.</li>
     *                                     </ul>
     * @throws InvocationTargetException   Thrown if the underlying method throws an exception.
     * @throws ExceptionInInitializerError Thrown if the initialization provoked by this method fails.
     * @see SecurityManager#checkPermission
     */
    public static Object invokeStaticMethod(final Class<?> cls, final String methodName, final Object[] args, final Class<?>[] parameterTypes)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Method method = getMatchingAccessibleMethod(cls, methodName, ArrayUtils.nullToEmpty(parameterTypes));
        if (method == null) {
            throw new NoSuchMethodException("No such accessible method: " + methodName + "() on class: " + cls.getName());
        }
        return method.invoke(null, toVarArgs(method, ArrayUtils.nullToEmpty(args)));
    }

    private static Object[] toVarArgs(final Method method, final Object[] args) {
        return method.isVarArgs() ? getVarArgs(args, method.getParameterTypes()) : args;
    }
}