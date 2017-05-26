package org.particleframework.context;

import groovy.lang.SpreadMap;
import org.particleframework.context.exceptions.BeanInstantiationException;
import org.particleframework.context.exceptions.DependencyInjectionException;
import org.particleframework.context.exceptions.NoSuchBeanException;
import org.particleframework.core.reflect.GenericTypeUtils;
import org.particleframework.inject.*;
import org.particleframework.core.annotation.Internal;
import org.particleframework.inject.qualifiers.Qualifiers;
import org.particleframework.context.annotation.Provided;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Provider;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * <p></p>Default implementation of the {@link BeanDefinition} interface. This class is generally not used directly in user code.
 * Instead a build time tool does analysis of source code and dynamically produces subclasses of this class containing
 * information about the available injection points for a given class.</p>
 *
 * <p>For technical reasons the class has to be marked as public, but is regarded as internal and should be used by compiler tools and plugins (such as AST transformation frameworks)</p>
 *
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@Internal
public abstract class AbstractBeanDefinition<T> implements InjectableBeanDefinition<T> {

    private static final LinkedHashMap<String, Class> EMPTY_MAP = new LinkedHashMap<>(0);
    private final Annotation scope;
    private final boolean singleton;
    private final Class<T> type;
    private final boolean provided;
    private boolean hasPreDestroyMethods = false;
    private boolean hasPostConstructMethods = false;
    private final ConstructorInjectionPoint<T> constructor;
    private final Collection<Class> requiredComponents = new HashSet<>(3);
    protected final List<MethodInjectionPoint> methodInjectionPoints = new ArrayList<>(3);
    protected final List<FieldInjectionPoint> fieldInjectionPoints = new ArrayList<>(3);
    protected final List<MethodInjectionPoint> postConstructMethods = new ArrayList<>(1);
    protected final List<MethodInjectionPoint> preDestroyMethods = new ArrayList<>(1);


    protected AbstractBeanDefinition(Annotation scope,
                                     boolean singleton,
                                     Class<T> type,
                                     Constructor<T> constructor,
                                     Map<String, Class> arguments,
                                     Map<String, Class> qualifiers,
                                     Map<String, List<Class>> genericTypes) {
        this.scope = scope;
        this.singleton = singleton;
        this.type = type;
        this.provided = type.getAnnotation(Provided.class) != null;
        LinkedHashMap<String, Annotation> qualifierMap = null;
        if(qualifiers != null) {
            qualifierMap = new LinkedHashMap<>();
            populateQualifiersFromParameterAnnotations(arguments, qualifiers, qualifierMap, constructor.getParameterAnnotations());
        }
        this.constructor = new DefaultConstructorInjectionPoint<>(this,constructor, arguments, qualifierMap, genericTypes);
    }

    protected AbstractBeanDefinition(Annotation scope,
                                     boolean singleton,
                                     Class<T> type,
                                     Constructor<T> constructor) {
        this(scope, singleton, type, constructor, EMPTY_MAP, null, null);
    }

    protected AbstractBeanDefinition(Annotation scope,
                                     boolean singleton,
                                     Class<T> type,
                                     Constructor<T> constructor,
                                     Map<String, Class> arguments) {
        this(scope, singleton, type, constructor, arguments, null, null);
    }

    @Override
    public boolean isProvided() {
        return provided;
    }

    @Override
    public boolean isSingleton() {
        return singleton;
    }

    @Override
    public Annotation getScope() {
        return this.scope;
    }

    @Override
    public Class<T> getType() {
        return type;
    }

    @Override
    public ConstructorInjectionPoint<T> getConstructor() {
        return constructor;
    }

    @Override
    public Collection<Class> getRequiredComponents() {
        return Collections.unmodifiableCollection(requiredComponents);
    }

    @Override
    public Collection<MethodInjectionPoint> getInjectedMethods() {
        return Collections.unmodifiableCollection(methodInjectionPoints);
    }

    @Override
    public Collection<FieldInjectionPoint> getInjectedFields() {
        return Collections.unmodifiableCollection(fieldInjectionPoints);
    }

    @Override
    public Collection<MethodInjectionPoint> getPostConstructMethods() {
        return Collections.unmodifiableCollection(postConstructMethods);
    }

    @Override
    public Collection<MethodInjectionPoint> getPreDestroyMethods() {
        return Collections.unmodifiableCollection(preDestroyMethods);
    }

    @Override
    public String getName() {
        return getType().getName();
    }

    @Override
    public T inject(BeanContext context, T bean) {
        return (T) injectBean(new DefaultBeanResolutionContext(context, this), context, bean);
    }

    @Override
    public T inject(BeanResolutionContext resolutionContext, BeanContext context, T bean) {
        return (T) injectBean(resolutionContext, context, bean);
    }

    /**
     * Adds an injection point for a field. Typically called by a dynamically generated subclass.
     *
     * @param field The field
     * @param qualifier The qualifier, can be null
     * @return this component definition
     */
    protected AbstractBeanDefinition addInjectionPoint(Field field, Annotation qualifier, boolean requiresReflection) {
        requiredComponents.add(field.getType());
        fieldInjectionPoints.add(new DefaultFieldInjectionPoint(this,field, qualifier, requiresReflection));
        return this;
    }


    /**
     * Adds an injection point for a field. Typically called by a dynamically generated subclass.
     *
     * @param field The field
     * @return this component definition
     */
    protected AbstractBeanDefinition addInjectionPoint(Field field, boolean requiresReflection) {
        requiredComponents.add(field.getType());
        fieldInjectionPoints.add(new DefaultFieldInjectionPoint(this,field, null, requiresReflection));
        return this;
    }

    /**
     * Adds an injection point for a method. Typically called by a dynamically generated subclass.
     *
     * @param method The method
     * @param  arguments The arguments to the method
     * @return this component definition
     */
    protected AbstractBeanDefinition addInjectionPoint(
                                                Method method,
                                                Map<String, Class> arguments,
                                                Map<String, Class> qualifiers,
                                                Map<String, List<Class>> genericTypes,
                                                boolean requiresReflection) {
        Collection<MethodInjectionPoint> methodInjectionPoints = this.methodInjectionPoints;
        return addMethodInjectionPointInternal(null, method, arguments, qualifiers, genericTypes,requiresReflection, methodInjectionPoints);
    }

    /**
     * Adds an injection point for a setter and field to be set. Typically called by a dynamically generated subclass.
     *
     * @param setter The method
     * @return this component definition
     */
    protected AbstractBeanDefinition addInjectionPoint(
            Field field,
            Method setter,
            Annotation qualifier,
            List<Class> genericTypes,
            boolean requiresReflection) {

        Map<String, Annotation> qualifiers = null;
        Map<String, List<Class>> genericTypeMap = null;
        String fieldName = field.getName();
        if(qualifier != null) {
            qualifiers = Collections.singletonMap(fieldName, qualifier);
        }
        if(genericTypes != null) {
            genericTypeMap = Collections.singletonMap(fieldName, genericTypes);
        }
        DefaultMethodInjectionPoint methodInjectionPoint = new DefaultMethodInjectionPoint(
                this,
                                setter,
                                requiresReflection,
                                Collections.singletonMap(fieldName, field.getType()),
                                qualifiers,
                                genericTypeMap );
        requiredComponents.add(field.getType());
        methodInjectionPoints.add(methodInjectionPoint);
        return this;
    }


    protected AbstractBeanDefinition addPostConstruct(Method method,
                                                      LinkedHashMap<String, Class> arguments,
                                                      Map<String, Class> qualifiers,
                                                      Map<String, List<Class>> genericTypes,
                                                      boolean requiresReflection) {
        return addMethodInjectionPointInternal(null, method, arguments, qualifiers, genericTypes, requiresReflection, postConstructMethods);
    }

    protected AbstractBeanDefinition addPreDestroy(Method method,
                                                   LinkedHashMap<String, Class> arguments,
                                                   Map<String, Class> qualifiers,
                                                   Map<String, List<Class>> genericTypes,
                                                   boolean requiresReflection) {
        return addMethodInjectionPointInternal(null, method, arguments, qualifiers, genericTypes, requiresReflection, preDestroyMethods);
    }

    protected Object injectBean(BeanResolutionContext resolutionContext, BeanContext context, Object bean) {
        DefaultBeanContext defaultContext = (DefaultBeanContext) context;


        // Inject fields that require reflection
        injectBeanFields(resolutionContext, defaultContext, bean);

        // Inject methods that require reflection
        injectBeanMethods(resolutionContext, defaultContext, bean);

        return bean;
    }

    /**
     * Inject another bean, for example one created via factory
     *
     * @param resolutionContext The reslution context
     * @param context The context
     * @param bean The bean
     * @return The bean
     */
    protected Object injectAnother(BeanResolutionContext resolutionContext, BeanContext context, Object bean) {
        DefaultBeanContext defaultContext = (DefaultBeanContext) context;
        return defaultContext.inject(resolutionContext, bean);
    }
    /**
     * Default postConstruct hook that only invokes methods that require reflection. Generated subclasses should override to call methods that don't require reflection
     *
     * @param resolutionContext The resolution hook
     * @param context The context
     * @param bean The bean
     * @return The bean
     */
    protected Object postConstruct(BeanResolutionContext resolutionContext, BeanContext context, Object bean) {
        DefaultBeanContext defaultContext = (DefaultBeanContext) context;
        for (MethodInjectionPoint methodInjectionPoint : methodInjectionPoints) {
            if(methodInjectionPoint.isPostConstructMethod() && methodInjectionPoint.requiresReflection()) {
                injectBeanMethod(resolutionContext, defaultContext, bean, resolutionContext.getPath(), methodInjectionPoint);
            }
        }
        if(bean instanceof LifeCycle) {
            bean = ((LifeCycle) bean).start();
        }
        return bean;
    }

    /**
     * Default preDestroy hook that only invokes methods that require reflection. Generated subclasses should override to call methods that don't require reflection
     *
     * @param resolutionContext The resolution hook
     * @param context The context
     * @param bean The bean
     * @return The bean
     */
    protected Object preDestroy(BeanResolutionContext resolutionContext, BeanContext context, Object bean) {
        DefaultBeanContext defaultContext = (DefaultBeanContext) context;
        for (MethodInjectionPoint methodInjectionPoint : methodInjectionPoints) {
            if(methodInjectionPoint.isPreDestroyMethod() && methodInjectionPoint.requiresReflection()) {
                injectBeanMethod(resolutionContext, defaultContext, bean, resolutionContext.getPath(), methodInjectionPoint);
            }
        }
        if(bean instanceof LifeCycle) {
            bean = ((LifeCycle) bean).stop();
        }
        return bean;
    }

    protected void injectBeanMethods(BeanResolutionContext resolutionContext, DefaultBeanContext context, Object bean) {
        BeanResolutionContext.Path path = resolutionContext.getPath();
        for (MethodInjectionPoint methodInjectionPoint : methodInjectionPoints) {
            if (methodInjectionPoint.requiresReflection() && !methodInjectionPoint.isPostConstructMethod() && !methodInjectionPoint.isPreDestroyMethod()) {
                injectBeanMethod(resolutionContext, context, bean, path, methodInjectionPoint);
            }
        }
    }

    private void injectBeanMethod(BeanResolutionContext resolutionContext, DefaultBeanContext context, Object bean, BeanResolutionContext.Path path, MethodInjectionPoint methodInjectionPoint) {
        Argument[] methodArgumentTypes = methodInjectionPoint.getArguments();
        Object[] methodArgs = new Object[methodArgumentTypes.length];
        for (int i = 0; i < methodArgumentTypes.length; i++) {
            Argument argument = methodArgumentTypes[i];

            methodArgs[i] = getBeanForMethodArgument(resolutionContext, context, methodInjectionPoint, argument);
        }
        try {
            methodInjectionPoint.invoke(bean, methodArgs);
        } catch (Throwable e) {
            throw new BeanInstantiationException(this, e);
        }
    }

    protected void injectBeanFields(BeanResolutionContext resolutionContext , DefaultBeanContext defaultContext, Object bean) {
        for (FieldInjectionPoint fieldInjectionPoint : fieldInjectionPoints) {
            if(fieldInjectionPoint.requiresReflection()) {
                Object value = getBeanForField(resolutionContext, defaultContext, fieldInjectionPoint);
                try {
                    fieldInjectionPoint.set(bean, value);
                } catch (Exception e) {
                    throw new DependencyInjectionException(resolutionContext, fieldInjectionPoint, "Error setting field value: " + e.getMessage());
                }
            }
        }
    }

    private Object[] collectionToArray(Class arrayType, Collection beans) {
        Object[] newArray = (Object[]) Array.newInstance(arrayType, beans.size());
        int i = 0;
        for (Object foundBean : beans) {
            newArray[i++] = foundBean;
        }
        return newArray;
    }

    /**
     * Obtains a bean definition for the method at the given index and the argument at the given index
     *
     * Warning: this method is used by internal generated code and should not be called by user code.
     *
     * @param resolutionContext The resolution context
     * @param context The context
     * @param methodIndex The method index
     * @param argIndex The argument index
     * @return The resolved bean
     */
    @Internal
    protected Object getBeanForMethodArgument(BeanResolutionContext resolutionContext, BeanContext context, int methodIndex, int argIndex) {
        MethodInjectionPoint injectionPoint = methodInjectionPoints.get(methodIndex);
        Argument argument = injectionPoint.getArguments()[argIndex];
        return getBeanForMethodArgument(resolutionContext, context, injectionPoint, argument);
    }

    protected Object getBeanForMethodArgument(BeanResolutionContext resolutionContext, BeanContext context, MethodInjectionPoint injectionPoint, Argument argument) {
        Class argumentType = argument.getType();
        if(argumentType.isArray()) {
            Collection beansOfType = getBeansOfTypeForMethodArgument(resolutionContext, context, injectionPoint,argument);
            return beansOfType.toArray((Object[]) Array.newInstance(argumentType.getComponentType(), beansOfType.size()));
        }
        else if (Collection.class.isAssignableFrom(argumentType)) {
            Collection beansOfType = getBeansOfTypeForMethodArgument(resolutionContext, context, injectionPoint,argument);
            return coerceCollectionToCorrectType(resolutionContext, argument, argumentType, beansOfType);
        }
        else if (Stream.class.isAssignableFrom(argumentType)) {
            return streamOfTypeForMethodArgument(resolutionContext, context, injectionPoint, argument);
        }
        else if(Provider.class.isAssignableFrom(argumentType)) {
            return getBeanProviderForMethodArgument(resolutionContext, context, injectionPoint, argument);
        }
        else {
            BeanResolutionContext.Path path = resolutionContext.getPath();
            path.pushMethodArgumentResolve(this, injectionPoint, argument);
            try {
                Qualifier qualifier = resolveQualifier(argument);
                Object bean = ((DefaultBeanContext)context).getBean(resolutionContext, argumentType, qualifier);
                path.pop();
                return bean;
            } catch (NoSuchBeanException e) {
                throw new DependencyInjectionException(resolutionContext, injectionPoint, argument, e);
            }
        }
    }

    private Object coerceCollectionToCorrectType(BeanResolutionContext resolutionContext, Argument argument, Class collectionType, Collection beansOfType) {
        if(collectionType.isInstance(beansOfType)) {
            return beansOfType;
        }
        else {
            try {
                return coerceToType(beansOfType, collectionType);
            } catch (Exception e) {
                throw new DependencyInjectionException(resolutionContext, argument, "Cannot convert collection to target iterable type: " + collectionType.getName());
            }
        }
    }


    /**
     * Obtains all bean definitions for the method at the given index and the argument at the given index
     *
     * Warning: this method is used by internal generated code and should not be called by user code.
     *
     * @param resolutionContext The resolution context
     * @param context The context
     * @return The resolved bean
     */
    @Internal
    protected Collection getBeansOfTypeForMethodArgument(BeanResolutionContext resolutionContext, BeanContext context, MethodInjectionPoint injectionPoint, Argument argument) {
        BeanResolutionContext.Path path = resolutionContext.getPath();
        path.pushMethodArgumentResolve(this, injectionPoint, argument);
        try {
            Qualifier qualifier = resolveQualifier(argument);
            Class[] genericTypes = argument.getGenericTypes();
            Class genericType;
            if(genericTypes.length != 1) {
                throw new DependencyInjectionException(resolutionContext, injectionPoint, argument, "Expected exactly 1 generic type argument");
            }
            else {
                genericType = genericTypes[0];
            }
            Collection beansOfType = ((DefaultBeanContext) context).getBeansOfType(resolutionContext, genericType, qualifier);
            path.pop();
            return beansOfType;
        } catch (NoSuchBeanException e) {
            throw new DependencyInjectionException(resolutionContext, injectionPoint, argument, e);
        }
    }


    /**
     * Obtains all bean definitions for the method at the given index and the argument at the given index
     *
     * Warning: this method is used by internal generated code and should not be called by user code.
     *
     * @param resolutionContext The resolution context
     * @param context The context
     * @return The resolved bean
     */
    @Internal
    protected Stream streamOfTypeForMethodArgument(BeanResolutionContext resolutionContext, BeanContext context, MethodInjectionPoint injectionPoint, Argument argument) {
        BeanResolutionContext.Path path = resolutionContext.getPath();
        path.pushMethodArgumentResolve(this, injectionPoint, argument);
        try {
            Qualifier qualifier = resolveQualifier(argument);
            Class[] genericTypes = argument.getGenericTypes();
            Class genericType;
            if(genericTypes.length != 1) {
                throw new DependencyInjectionException(resolutionContext, injectionPoint, argument, "Expected exactly 1 generic type argument");
            }
            else {
                genericType = genericTypes[0];
            }
            Stream beansOfType = ((DefaultBeanContext) context).streamOfType(resolutionContext, genericType, qualifier);
            path.pop();
            return beansOfType;
        } catch (NoSuchBeanException e) {
            throw new DependencyInjectionException(resolutionContext, injectionPoint, argument, e);
        }
    }

    /**
     * Obtains all bean definitions for a constructor argument at the given index
     *
     * Warning: this method is used by internal generated code and should not be called by user code.
     *
     * @param resolutionContext The resolution context
     * @param context The context
     * @return The resolved bean
     */
    @Internal
    protected Collection getBeansOfTypeForConstructorArgument(BeanResolutionContext resolutionContext, BeanContext context, ConstructorInjectionPoint<T> constructorInjectionPoint, Argument argument) {
        BeanResolutionContext.Path path = resolutionContext.getPath();
        path.pushConstructorResolve(this,  argument);
        try {
            Qualifier qualifier = resolveQualifier(argument);
            Class genericType;
            Class argumentType = argument.getType();
            boolean isArray = argumentType.isArray();
            if(isArray) {
                genericType = argumentType.getComponentType();
            }
            else {
                Class[] genericTypes = argument.getGenericTypes();
                if(genericTypes.length != 1) {
                    throw new DependencyInjectionException(resolutionContext, argument, "Expected exactly 1 generic type argument to constructor");
                }
                else {
                    genericType = genericTypes[0];
                }
            }
            Collection beansOfType = ((DefaultBeanContext) context).getBeansOfType(resolutionContext, genericType, qualifier);
            return beansOfType;
        } catch (NoSuchBeanException e) {
            throw new DependencyInjectionException(resolutionContext, argument , e);
        }
    }

    /**
     * Obtains all bean definitions for a constructor argument at the given index
     *
     * Warning: this method is used by internal generated code and should not be called by user code.
     *
     * @param resolutionContext The resolution context
     * @param context The context
     * @return The resolved bean
     */
    @Internal
    protected Stream streamOfTypeForConstructorArgument(BeanResolutionContext resolutionContext, BeanContext context, ConstructorInjectionPoint<T> constructorInjectionPoint, Argument argument) {
        BeanResolutionContext.Path path = resolutionContext.getPath();
        path.pushConstructorResolve(this,  argument);
        try {
            Class genericType;
            Qualifier qualifier = resolveQualifier(argument);
            Class[] genericTypes = argument.getGenericTypes();
            if(genericTypes.length != 1) {
                throw new DependencyInjectionException(resolutionContext, argument, "Expected exactly 1 generic type argument to constructor");
            }
            else {
                genericType = genericTypes[0];
            }
            Stream beansOfType = ((DefaultBeanContext) context).streamOfType(resolutionContext, genericType, qualifier);
            return beansOfType;
        } catch (NoSuchBeanException e) {
            throw new DependencyInjectionException(resolutionContext, argument , e);
        }
    }
    /**
     * Obtains a bean definition for the field at the given index and the argument at the given index
     *
     * Warning: this method is used by internal generated code and should not be called by user code.
     *
     * @param resolutionContext The resolution context
     * @param context The context
     * @return The resolved bean
     */
    @Internal
    protected Object getBeanForField(BeanResolutionContext resolutionContext, BeanContext context, int fieldIndex) {
        FieldInjectionPoint injectionPoint = fieldInjectionPoints.get(fieldIndex);
        return getBeanForField(resolutionContext, context, injectionPoint);

    }

    protected Object getBeanForField(BeanResolutionContext resolutionContext, BeanContext context, FieldInjectionPoint injectionPoint) {
        Class beanType = injectionPoint.getType();
        if(beanType.isArray()) {
            Collection beansOfType = getBeansOfTypeForField(resolutionContext, context, injectionPoint);
            return beansOfType.toArray((Object[]) Array.newInstance(beanType.getComponentType(), beansOfType.size()));
        }
        else if (Collection.class.isAssignableFrom(beanType)) {
            Collection beansOfType = getBeansOfTypeForField(resolutionContext, context, injectionPoint);
            if(beanType.isInstance(beansOfType)) {
                return beansOfType;
            }
            else {
                try {
                    return coerceToType(beansOfType, beanType);
                } catch (Exception e) {
                    throw new DependencyInjectionException(resolutionContext, injectionPoint, "Cannot convert collection to target iterable type: " + beanType.getName());
                }
            }
        }
        else if(Stream.class.isAssignableFrom(beanType)) {
            return getStreamOfTypeForField(resolutionContext, context, injectionPoint);
        }
        else if(Provider.class.isAssignableFrom(beanType)) {
            return getBeanProviderForField(resolutionContext, context, injectionPoint);
        }
        else {
            BeanResolutionContext.Path path = resolutionContext.getPath();
            path.pushFieldResolve(this, injectionPoint);

            try {
                Qualifier qualifier = resolveQualifier(injectionPoint);
                Object bean = ((DefaultBeanContext)context).getBean(resolutionContext, beanType, qualifier);
                path.pop();
                return bean;
            } catch (NoSuchBeanException e) {
                throw new DependencyInjectionException(resolutionContext, injectionPoint, e);
            }
        }
    }

    /**
     * Obtains a bean definition for the field at the given index and the argument at the given index
     *
     * Warning: this method is used by internal generated code and should not be called by user code.
     *
     * @param resolutionContext The resolution context
     * @param context The context
     * @return The resolved bean
     */
    @Internal
    protected Object getBeanProviderForField(BeanResolutionContext resolutionContext, BeanContext context, FieldInjectionPoint injectionPoint) {
        BeanResolutionContext.Path path = resolutionContext.getPath();
        path.pushFieldResolve(this, injectionPoint);

        try {
            Class genericType = GenericTypeUtils.resolveGenericTypeArgument(injectionPoint.getField());
            if(genericType == null) {
                throw new DependencyInjectionException(resolutionContext, injectionPoint, "Expected exactly 1 generic type for field");
            }
            Qualifier qualifier = resolveQualifier(injectionPoint);
            Object bean = ((DefaultBeanContext)context).getBeanProvider(resolutionContext,genericType, qualifier);
            path.pop();
            return bean;
        } catch (NoSuchBeanException e) {
            throw new DependencyInjectionException(resolutionContext, injectionPoint, e);
        }
    }

    /**
     * Obtains a bean definition for the field at the given index and the argument at the given index
     *
     * Warning: this method is used by internal generated code and should not be called by user code.
     *
     * @param resolutionContext The resolution context
     * @param context The context
     * @return The resolved bean
     */
    @Internal
    protected Collection getBeansOfTypeForField(BeanResolutionContext resolutionContext, BeanContext context, FieldInjectionPoint injectionPoint) {
        BeanResolutionContext.Path path = resolutionContext.getPath();
        path.pushFieldResolve(this, injectionPoint);

        try {
            Field field = injectionPoint.getField();
            Class genericType;
            Class<?> fieldType = field.getType();
            if(fieldType.isArray()) {
                genericType = fieldType.getComponentType();
            } else {
                genericType = GenericTypeUtils.resolveGenericTypeArgument(field);
            }

            if(genericType == null) {
                throw new DependencyInjectionException(resolutionContext, injectionPoint, "Expected exactly 1 generic type for field");
            }
            Qualifier qualifier = resolveQualifier(injectionPoint);
            Collection beans = ((DefaultBeanContext)context).getBeansOfType(resolutionContext,genericType, qualifier);
            path.pop();
            return beans;
        } catch (NoSuchBeanException e) {
            throw new DependencyInjectionException(resolutionContext, injectionPoint, e);
        }
    }

    /**
     * Obtains a bean definition for the field at the given index and the argument at the given index
     *
     * Warning: this method is used by internal generated code and should not be called by user code.
     *
     * @param resolutionContext The resolution context
     * @param context The context
     * @return The resolved bean
     */
    @Internal
    protected Stream getStreamOfTypeForField(BeanResolutionContext resolutionContext, BeanContext context, FieldInjectionPoint injectionPoint) {
        BeanResolutionContext.Path path = resolutionContext.getPath();
        path.pushFieldResolve(this, injectionPoint);

        try {
            Class genericType = GenericTypeUtils.resolveGenericTypeArgument(injectionPoint.getField());
            if(genericType == null) {
                throw new DependencyInjectionException(resolutionContext, injectionPoint, "Expected exactly 1 generic type for field");
            }
            Qualifier qualifier = resolveQualifier(injectionPoint);
            Stream beans = ((DefaultBeanContext)context).streamOfType(resolutionContext,genericType, qualifier);
            path.pop();
            return beans;
        } catch (NoSuchBeanException e) {
            throw new DependencyInjectionException(resolutionContext, injectionPoint, e);
        }
    }

    private Qualifier resolveQualifier(FieldInjectionPoint injectionPoint) {
        Qualifier qualifier = null;
        Annotation ann = injectionPoint.getQualifier();
        if(ann != null) {
            qualifier = Qualifiers.qualify(ann);
        }
        return qualifier;
    }

    private Qualifier resolveQualifier(Argument argument) {
        Qualifier qualifier = null;
        Annotation ann = argument.getQualifier();
        if(ann != null) {
            qualifier = Qualifiers.qualify(ann);
        }
        return qualifier;
    }



    /**
     * Obtains a bean definition for a constructor at the given index
     *
     * Warning: this method is used by internal generated code and should not be called by user code.
     *
     * @param resolutionContext The resolution context
     * @param context The context
     * @param argIndex The argument index
     * @return The resolved bean
     */
    @Internal
    protected Object getBeanForConstructorArgument(BeanResolutionContext resolutionContext, BeanContext context, int argIndex) {
        ConstructorInjectionPoint<T> constructorInjectionPoint = getConstructor();
        Argument argument = constructorInjectionPoint.getArguments()[argIndex];
        Class argumentType = argument.getType();
        if(argumentType.isArray()) {
            Collection beansOfType = getBeansOfTypeForConstructorArgument(resolutionContext, context, constructorInjectionPoint, argument);
            return beansOfType.toArray((Object[]) Array.newInstance(argumentType.getComponentType(), beansOfType.size()));
        }
        else if (Collection.class.isAssignableFrom(argumentType)) {
            Collection beansOfType = getBeansOfTypeForConstructorArgument(resolutionContext, context, constructorInjectionPoint, argument);
            return coerceCollectionToCorrectType(resolutionContext, argument, argumentType, beansOfType);
        }
        else if (Stream.class.isAssignableFrom(argumentType)) {
            return streamOfTypeForConstructorArgument(resolutionContext, context, constructorInjectionPoint, argument);
        }
        else if(Provider.class.isAssignableFrom(argumentType)) {
            return getBeanProviderForConstructorArgument(resolutionContext, context, constructorInjectionPoint, argument);
        }
        else {
            BeanResolutionContext.Path path = resolutionContext.getPath();
            path.pushConstructorResolve(this,  argument);
            try {
                Qualifier qualifier = resolveQualifier(argument);
                Object bean = ((DefaultBeanContext)context).getBean(resolutionContext, argumentType, qualifier);
                path.pop();
                return bean;
            } catch (NoSuchBeanException | BeanInstantiationException e) {
                throw new DependencyInjectionException(resolutionContext, argument , e);
            }
        }
    }




    /**
     * Obtains a bean provider for the method at the given index and the argument at the given index
     *
     * Warning: this method is used by internal generated code and should not be called by user code.
     *
     * @param resolutionContext The resolution context
     * @param context The context
     * @return The resolved bean
     */
    @Internal
    protected Provider getBeanProviderForMethodArgument(BeanResolutionContext resolutionContext, BeanContext context, MethodInjectionPoint injectionPoint, Argument argument) {
        BeanResolutionContext.Path path = resolutionContext.getPath();
        path.pushMethodArgumentResolve(this, injectionPoint, argument);
        try {
            Qualifier qualifier = resolveQualifier(argument);
            Class[] genericTypes = argument.getGenericTypes();
            Class genericType;
            if(genericTypes.length != 1) {
                throw new DependencyInjectionException(resolutionContext, argument, "Expected exactly 1 generic type for argument ["+argument+"] of method ["+injectionPoint.getName()+"]");
            }
            else {
                genericType = genericTypes[0];
            }
            Provider beanProvider = ((DefaultBeanContext)context).getBeanProvider(resolutionContext, genericType,qualifier);
            path.pop();
            return beanProvider;
        } catch (NoSuchBeanException e) {
            throw new DependencyInjectionException(resolutionContext, injectionPoint, argument, e);
        }

    }

    /**
     * Obtains a bean provider for a constructor at the given index
     *
     * Warning: this method is used by internal generated code and should not be called by user code.
     *
     * @param resolutionContext The resolution context
     * @param context The context
     * @return The resolved bean
     */
    @Internal
    protected Provider getBeanProviderForConstructorArgument(BeanResolutionContext resolutionContext, BeanContext context, ConstructorInjectionPoint constructorInjectionPoint, Argument argument) {
        BeanResolutionContext.Path path = resolutionContext.getPath();
        path.pushConstructorResolve(this,  argument);
        try {
            Class[] genericTypes = argument.getGenericTypes();
            Class genericType;
            if(genericTypes.length != 1) {
                throw new DependencyInjectionException(resolutionContext, argument, "Expected exactly 1 generic type argument to constructor");
            }
            else {
                genericType = genericTypes[0];
            }
            Qualifier qualifier = resolveQualifier(argument);
            Provider beanProvider  = ((DefaultBeanContext)context).getBeanProvider(resolutionContext, genericType, qualifier);
            path.pop();
            return beanProvider;
        } catch (NoSuchBeanException e) {
            throw new DependencyInjectionException(resolutionContext, argument , e);
        }
    }

    private Object coerceToType(Collection beans, Class<? extends Iterable> componentType) throws Exception {
        if(componentType.isInstance(beans)) {
            return beans;
        }
        if (componentType == Set.class) {
            return new HashSet<>(beans);
        } else if (componentType == Queue.class) {
            return new LinkedList<>(beans);
        } else if (componentType == List.class) {
            return new ArrayList<>(beans);
        } else if (!componentType.isInterface()) {
            Constructor<? extends Iterable> constructor = componentType.getConstructor(Collection.class);
            return constructor.newInstance(beans);
        } else {
            return null;
        }
    }

    private AbstractBeanDefinition addMethodInjectionPointInternal(
            Field field,
            Method method,
            Map<String, Class> arguments,
            Map<String, Class> qualifierTypes,
            Map<String, List<Class>> genericTypes,
            boolean requiresReflection,
            Collection<MethodInjectionPoint> methodInjectionPoints) {
        if(!hasPreDestroyMethods && method.getAnnotation(PreDestroy.class) != null) {
            hasPreDestroyMethods = true;
        }
        if(!hasPostConstructMethods && method.getAnnotation(PostConstruct.class) != null) {
            hasPostConstructMethods = true;
        }

        LinkedHashMap<String, Annotation> qualifiers = null;
        if(qualifierTypes != null && !qualifierTypes.isEmpty()) {
            qualifiers = new LinkedHashMap<>();
            if(field != null) {
                Map.Entry<String, Class> entry = qualifierTypes.entrySet().iterator().next();
                Annotation matchingAnnotation = findMatchingAnnotation(field.getAnnotations(), entry.getValue());
                if(matchingAnnotation != null) {
                    qualifiers.put(entry.getKey(), matchingAnnotation);
                }
                else {
                    qualifiers.put(entry.getKey(), null);
                }
            }
            else {
                Annotation[][] parameterAnnotations = method.getParameterAnnotations();
                populateQualifiersFromParameterAnnotations(arguments, qualifierTypes, qualifiers, parameterAnnotations);
            }

        }
        DefaultMethodInjectionPoint methodInjectionPoint = new DefaultMethodInjectionPoint(this, method,requiresReflection, arguments, qualifiers, genericTypes);
        for (Argument argument : methodInjectionPoint.getArguments()) {
            requiredComponents.add(argument.getType());
        }
        methodInjectionPoints.add(methodInjectionPoint);
        return this;
    }

    private void populateQualifiersFromParameterAnnotations(Map<String, Class> argumentTypes, Map<String, Class> qualifierTypes, LinkedHashMap<String, Annotation> qualifiers, Annotation[][] parameterAnnotations) {
        int i = 0;
        for (Map.Entry<String, Class> entry : argumentTypes.entrySet()) {
            Annotation[] annotations = parameterAnnotations[i++];
            Annotation matchingAnnotation = null;
            if(annotations.length>0) {
                Class annotationType = qualifierTypes.get(entry.getKey());
                if(annotationType != null) {
                    matchingAnnotation = findMatchingAnnotation(annotations, annotationType);
                }
            }

            if(matchingAnnotation != null) {
                qualifiers.put(entry.getKey(), matchingAnnotation);
            }
            else {
                qualifiers.put(entry.getKey(), null);
            }
        }
    }

    private Annotation findMatchingAnnotation(Annotation[] annotations, Class annotationType) {
        for (Annotation annotation : annotations) {
            if(annotation.annotationType() == annotationType) {
                return annotation;
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AbstractBeanDefinition<?> that = (AbstractBeanDefinition<?>) o;

        return type != null ? type.equals(that.type) : that.type == null;
    }

    @Override
    public int hashCode() {
        return type != null ? type.hashCode() : 0;
    }

    protected static Map createMap(Object[] values) {
        Map answer = new LinkedHashMap(values.length / 2);
        int i = 0;
        while (i < values.length - 1) {
            answer.put(values[i++], values[i++]);
        }
        return answer;
    }
}