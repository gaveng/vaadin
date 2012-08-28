/* 
@VaadinApache2LicenseForJavaFiles@
 */

package com.vaadin.terminal.gwt.widgetsetutils.metadata;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.TreeLogger.Type;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.typeinfo.JArrayType;
import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.core.ext.typeinfo.JEnumType;
import com.google.gwt.core.ext.typeinfo.JMethod;
import com.google.gwt.core.ext.typeinfo.JParameterizedType;
import com.google.gwt.core.ext.typeinfo.JType;
import com.google.gwt.core.ext.typeinfo.NotFoundException;
import com.google.gwt.core.ext.typeinfo.TypeOracle;
import com.google.gwt.json.client.JSONValue;
import com.vaadin.shared.communication.ClientRpc;
import com.vaadin.shared.communication.ServerRpc;
import com.vaadin.shared.ui.Connect;
import com.vaadin.terminal.gwt.client.ApplicationConnection;
import com.vaadin.terminal.gwt.client.ComponentConnector;
import com.vaadin.terminal.gwt.client.ServerConnector;
import com.vaadin.terminal.gwt.client.communication.JSONSerializer;

public class ConnectorBundle {
    private static final String FAIL_IF_NOT_SERIALIZABLE = "vFailIfNotSerializable";

    private final String name;
    private final ConnectorBundle previousBundle;
    private final Collection<TypeVisitor> visitors;
    private final Map<JType, JClassType> customSerializers;

    private final Set<JType> hasSerializeSupport = new HashSet<JType>();
    private final Set<JType> needsSerializeSupport = new HashSet<JType>();
    private final Map<JType, GeneratedSerializer> serializers = new HashMap<JType, GeneratedSerializer>();

    private final Set<JClassType> needsPropertyList = new HashSet<JClassType>();
    private final Set<JClassType> needsGwtConstructor = new HashSet<JClassType>();
    private final Set<JClassType> visitedTypes = new HashSet<JClassType>();
    private final Set<JClassType> needsProxySupport = new HashSet<JClassType>();

    private final Map<JClassType, Set<String>> identifiers = new HashMap<JClassType, Set<String>>();
    private final Map<JClassType, Set<JMethod>> needsReturnType = new HashMap<JClassType, Set<JMethod>>();
    private final Map<JClassType, Set<JMethod>> needsInvoker = new HashMap<JClassType, Set<JMethod>>();
    private final Map<JClassType, Set<JMethod>> needsParamTypes = new HashMap<JClassType, Set<JMethod>>();
    private final Map<JClassType, Set<JMethod>> needsDelayedInfo = new HashMap<JClassType, Set<JMethod>>();

    private final Set<Property> needsSetter = new HashSet<Property>();
    private final Set<Property> needsType = new HashSet<Property>();
    private final Set<Property> needsGetter = new HashSet<Property>();

    private ConnectorBundle(String name, ConnectorBundle previousBundle,
            Collection<TypeVisitor> visitors,
            Map<JType, JClassType> customSerializers) {
        this.name = name;
        this.previousBundle = previousBundle;
        this.visitors = visitors;
        this.customSerializers = customSerializers;
    }

    public ConnectorBundle(String name, ConnectorBundle previousBundle) {
        this(name, previousBundle, previousBundle.visitors,
                previousBundle.customSerializers);
    }

    public ConnectorBundle(String name, Collection<TypeVisitor> visitors,
            TypeOracle oracle) throws NotFoundException {
        this(name, null, visitors, findCustomSerializers(oracle));
    }

    private static Map<JType, JClassType> findCustomSerializers(
            TypeOracle oracle) throws NotFoundException {
        Map<JType, JClassType> serializers = new HashMap<JType, JClassType>();

        JClassType serializerInterface = oracle.findType(JSONSerializer.class
                .getName());
        JType[] deserializeParamTypes = new JType[] {
                oracle.findType(com.vaadin.terminal.gwt.client.metadata.Type.class
                        .getName()),
                oracle.findType(JSONValue.class.getName()),
                oracle.findType(ApplicationConnection.class.getName()) };
        String deserializeMethodName = "deserialize";
        // Just test that the method exists
        serializerInterface.getMethod(deserializeMethodName,
                deserializeParamTypes);

        for (JClassType serializer : serializerInterface.getSubtypes()) {
            JMethod deserializeMethod = serializer.findMethod(
                    deserializeMethodName, deserializeParamTypes);
            if (deserializeMethod == null) {
                continue;
            }
            JType returnType = deserializeMethod.getReturnType();

            serializers.put(returnType, serializer);
        }
        return serializers;
    }

    public void setNeedsGwtConstructor(JClassType type) {
        if (!needsGwtConstructor(type)) {
            needsGwtConstructor.add(type);
        }
    }

    private boolean needsGwtConstructor(JClassType type) {
        if (needsGwtConstructor.contains(type)) {
            return true;
        } else {
            return previousBundle != null
                    && previousBundle.needsGwtConstructor(type);
        }
    }

    public void setIdentifier(JClassType type, String identifier) {
        if (!hasIdentifier(type, identifier)) {
            addMapping(identifiers, type, identifier);
        }
    }

    private boolean hasIdentifier(JClassType type, String identifier) {
        if (hasMapping(identifiers, type, identifier)) {
            return true;
        } else {
            return previousBundle != null
                    && previousBundle.hasIdentifier(type, identifier);
        }
    }

    public ConnectorBundle getPreviousBundle() {
        return previousBundle;
    }

    public String getName() {
        return name;
    }

    public Map<JClassType, Set<String>> getIdentifiers() {
        return Collections.unmodifiableMap(identifiers);
    }

    public Set<JClassType> getGwtConstructors() {
        return Collections.unmodifiableSet(needsGwtConstructor);
    }

    public void processTypes(TreeLogger logger, Collection<JClassType> types)
            throws UnableToCompleteException {
        for (JClassType type : types) {
            processType(logger, type);
        }
    }

    public void processType(TreeLogger logger, JClassType type)
            throws UnableToCompleteException {
        if (!isTypeVisited(type)) {
            for (TypeVisitor typeVisitor : visitors) {
                invokeVisitor(logger, type, typeVisitor);
            }
            visitedTypes.add(type);
            purgeSerializeSupportQueue(logger);
        }
    }

    private boolean isTypeVisited(JClassType type) {
        if (visitedTypes.contains(type)) {
            return true;
        } else {
            return previousBundle != null && previousBundle.isTypeVisited(type);
        }
    }

    private void purgeSerializeSupportQueue(TreeLogger logger)
            throws UnableToCompleteException {
        while (!needsSerializeSupport.isEmpty()) {
            Iterator<JType> iterator = needsSerializeSupport.iterator();
            JType type = iterator.next();
            iterator.remove();

            if (hasSserializeSupport(type)) {
                continue;
            }

            addSerializeSupport(logger, type);
        }
    }

    private void addSerializeSupport(TreeLogger logger, JType type)
            throws UnableToCompleteException {
        hasSerializeSupport.add(type);

        JParameterizedType parametrized = type.isParameterized();
        if (parametrized != null) {
            for (JClassType parameterType : parametrized.getTypeArgs()) {
                setNeedsSerialize(parameterType);
            }
        }

        if (serializationHandledByFramework(type)) {
            return;
        }

        JClassType customSerializer = customSerializers.get(type);
        JClassType typeAsClass = type.isClass();
        JEnumType enumType = type.isEnum();
        JArrayType arrayType = type.isArray();

        if (customSerializer != null) {
            logger.log(Type.INFO, "Will serialize " + type + " using "
                    + customSerializer.getName());
            setSerializer(type, new CustomSerializer(customSerializer));
        } else if (arrayType != null) {
            logger.log(Type.INFO, "Will serialize " + type + " as an array");
            setSerializer(type, new ArraySerializer(arrayType));
            setNeedsSerialize(arrayType.getComponentType());
        } else if (enumType != null) {
            logger.log(Type.INFO, "Will serialize " + type + " as an enum");
            setSerializer(type, new EnumSerializer(enumType));
        } else if (typeAsClass != null) {
            // Bean
            checkSerializable(logger, typeAsClass);

            logger.log(Type.INFO, "Will serialize " + type + " as a bean");

            setNeedsPropertyList(typeAsClass);

            for (Property property : getProperties(typeAsClass)) {
                setNeedsGwtConstructor(property.getBeanType());
                setNeedsSetter(property);

                // Getters needed for reading previous value that should be
                // passed to sub encoder
                setNeedsGetter(property);
                setNeedsType(property);

                JType propertyType = property.getPropertyType();
                setNeedsSerialize(propertyType);
            }
        }
    }

    private void checkSerializable(TreeLogger logger, JClassType type)
            throws UnableToCompleteException {
        JClassType javaSerializable = type.getOracle().findType(
                Serializable.class.getName());
        boolean serializable = type.isAssignableTo(javaSerializable);
        if (!serializable) {
            boolean abortCompile = "true".equals(System
                    .getProperty(FAIL_IF_NOT_SERIALIZABLE));
            logger.log(
                    abortCompile ? Type.ERROR : Type.WARN,
                    type
                            + " is used in RPC or shared state but does not implement "
                            + Serializable.class.getName()
                            + ". Communication will work but the Application on server side cannot be serialized if it refers to objects of this type. "
                            + "If the system property "
                            + FAIL_IF_NOT_SERIALIZABLE
                            + " is set to \"true\", this causes the compilation to fail instead of just emitting a warning.");
            if (abortCompile) {
                throw new UnableToCompleteException();
            }
        }
    }

    private void setSerializer(JType type, GeneratedSerializer serializer) {
        if (!hasSerializer(type)) {
            serializers.put(type, serializer);
        }
    }

    private boolean hasSerializer(JType type) {
        if (serializers.containsKey(type)) {
            return true;
        } else {
            return previousBundle != null && previousBundle.hasSerializer(type);
        }
    }

    public Map<JType, GeneratedSerializer> getSerializers() {
        return Collections.unmodifiableMap(serializers);
    }

    private void setNeedsGetter(Property property) {
        if (!isNeedsGetter(property)) {
            needsGetter.add(property);
        }
    }

    private boolean isNeedsGetter(Property property) {
        if (needsGetter.contains(property)) {
            return true;
        } else {
            return previousBundle != null
                    && previousBundle.isNeedsGetter(property);
        }
    }

    public Set<Property> getNeedsGetter() {
        return Collections.unmodifiableSet(needsGetter);
    }

    private void setNeedsType(Property property) {
        if (!isNeedsType(property)) {
            needsType.add(property);
        }
    }

    public Set<Property> getNeedsType() {
        return Collections.unmodifiableSet(needsType);
    }

    private boolean isNeedsType(Property property) {
        if (needsType.contains(property)) {
            return true;
        } else {
            return previousBundle != null
                    && previousBundle.isNeedsType(property);
        }
    }

    public void setNeedsSetter(Property property) {
        if (!isNeedsSetter(property)) {
            needsSetter.add(property);
        }
    }

    private boolean isNeedsSetter(Property property) {
        if (needsSetter.contains(property)) {
            return true;
        } else {
            return previousBundle != null
                    && previousBundle.isNeedsSetter(property);
        }
    }

    public Set<Property> getNeedsSetter() {
        return Collections.unmodifiableSet(needsSetter);
    }

    private void setNeedsPropertyList(JClassType type) {
        if (!isNeedsPropertyList(type)) {
            needsPropertyList.add(type);
        }
    }

    private boolean isNeedsPropertyList(JClassType type) {
        if (needsPropertyList.contains(type)) {
            return true;
        } else {
            return previousBundle != null
                    && previousBundle.isNeedsPropertyList(type);
        }
    }

    public Set<JClassType> getNeedsPropertyListing() {
        return Collections.unmodifiableSet(needsPropertyList);
    }

    public Collection<Property> getProperties(JClassType type) {
        HashSet<Property> properties = new HashSet<Property>();

        properties.addAll(MethodProperty.findProperties(type));
        properties.addAll(FieldProperty.findProperties(type));

        return properties;
    }

    private void invokeVisitor(TreeLogger logger, JClassType type,
            TypeVisitor typeVisitor) throws UnableToCompleteException {
        TreeLogger subLogger = logger.branch(Type.TRACE,
                "Visiting " + type.getName() + " with "
                        + typeVisitor.getClass().getSimpleName());
        if (isConnectedConnector(type)) {
            typeVisitor.visitConnector(subLogger, type, this);
        }
        if (isClientRpc(type)) {
            typeVisitor.visitClientRpc(subLogger, type, this);
        }
        if (isServerRpc(type)) {
            typeVisitor.visitServerRpc(subLogger, type, this);
        }
    }

    public void processSubTypes(TreeLogger logger, JClassType type)
            throws UnableToCompleteException {
        processTypes(logger, Arrays.asList(type.getSubtypes()));
    }

    public void setNeedsReturnType(JClassType type, JMethod method) {
        if (!isNeedsReturnType(type, method)) {
            addMapping(needsReturnType, type, method);
        }
    }

    private boolean isNeedsReturnType(JClassType type, JMethod method) {
        if (hasMapping(needsReturnType, type, method)) {
            return true;
        } else {
            return previousBundle != null
                    && previousBundle.isNeedsReturnType(type, method);
        }
    }

    public Map<JClassType, Set<JMethod>> getMethodReturnTypes() {
        return Collections.unmodifiableMap(needsReturnType);
    }

    private static boolean isClientRpc(JClassType type) {
        return isType(type, ClientRpc.class);
    }

    private static boolean isServerRpc(JClassType type) {
        return isType(type, ServerRpc.class);
    }

    public static boolean isConnectedConnector(JClassType type) {
        return isConnected(type) && isType(type, ServerConnector.class);
    }

    private static boolean isConnected(JClassType type) {
        return type.isAnnotationPresent(Connect.class);
    }

    public static boolean isConnectedComponentConnector(JClassType type) {
        return isConnected(type) && isType(type, ComponentConnector.class);
    }

    private static boolean isType(JClassType type, Class<?> class1) {
        try {
            return type.getOracle().getType(class1.getName())
                    .isAssignableFrom(type);
        } catch (NotFoundException e) {
            throw new RuntimeException("Could not find " + class1.getName(), e);
        }
    }

    public void setNeedsInvoker(JClassType type, JMethod method) {
        if (!isNeedsInvoker(type, method)) {
            addMapping(needsInvoker, type, method);
        }
    }

    private <K, V> void addMapping(Map<K, Set<V>> map, K key, V value) {
        Set<V> set = map.get(key);
        if (set == null) {
            set = new HashSet<V>();
            map.put(key, set);
        }
        set.add(value);
    }

    private <K, V> boolean hasMapping(Map<K, Set<V>> map, K key, V value) {
        return map.containsKey(key) && map.get(key).contains(value);
    }

    private boolean isNeedsInvoker(JClassType type, JMethod method) {
        if (hasMapping(needsInvoker, type, method)) {
            return true;
        } else {
            return previousBundle != null
                    && previousBundle.isNeedsInvoker(type, method);
        }
    }

    public Map<JClassType, Set<JMethod>> getNeedsInvoker() {
        return Collections.unmodifiableMap(needsInvoker);
    }

    public void setNeedsParamTypes(JClassType type, JMethod method) {
        if (!isNeedsParamTypes(type, method)) {
            addMapping(needsParamTypes, type, method);
        }
    }

    private boolean isNeedsParamTypes(JClassType type, JMethod method) {
        if (hasMapping(needsParamTypes, type, method)) {
            return true;
        } else {
            return previousBundle != null
                    && previousBundle.isNeedsParamTypes(type, method);
        }
    }

    public Map<JClassType, Set<JMethod>> getNeedsParamTypes() {
        return Collections.unmodifiableMap(needsParamTypes);
    }

    public void setNeedsProxySupport(JClassType type) {
        if (!isNeedsProxySupport(type)) {
            needsProxySupport.add(type);
        }
    }

    private boolean isNeedsProxySupport(JClassType type) {
        if (needsProxySupport.contains(type)) {
            return true;
        } else {
            return previousBundle != null
                    && previousBundle.isNeedsProxySupport(type);
        }
    }

    public Set<JClassType> getNeedsProxySupport() {
        return Collections.unmodifiableSet(needsProxySupport);
    }

    public void setNeedsDelayedInfo(JClassType type, JMethod method) {
        if (!isNeedsDelayedInfo(type, method)) {
            addMapping(needsDelayedInfo, type, method);
        }
    }

    private boolean isNeedsDelayedInfo(JClassType type, JMethod method) {
        if (hasMapping(needsDelayedInfo, type, method)) {
            return true;
        } else {
            return previousBundle != null
                    && previousBundle.isNeedsDelayedInfo(type, method);
        }
    }

    public Map<JClassType, Set<JMethod>> getNeedsDelayedInfo() {
        return Collections.unmodifiableMap(needsDelayedInfo);
    }

    public void setNeedsSerialize(JType type) {
        if (!hasSserializeSupport(type)) {
            needsSerializeSupport.add(type);
        }
    }

    private static Set<Class<?>> frameworkHandledTypes = new HashSet<Class<?>>();
    {
        frameworkHandledTypes.add(String.class);
        frameworkHandledTypes.add(Boolean.class);
        frameworkHandledTypes.add(Integer.class);
        frameworkHandledTypes.add(Float.class);
        frameworkHandledTypes.add(Double.class);
        frameworkHandledTypes.add(Long.class);
        frameworkHandledTypes.add(Enum.class);
        frameworkHandledTypes.add(String[].class);
        frameworkHandledTypes.add(Object[].class);
        frameworkHandledTypes.add(Map.class);
        frameworkHandledTypes.add(List.class);
        frameworkHandledTypes.add(Set.class);
        frameworkHandledTypes.add(Byte.class);
        frameworkHandledTypes.add(Character.class);

    }

    private boolean serializationHandledByFramework(JType setterType) {
        // Some types are handled by the framework at the moment. See #8449
        // This method should be removed at some point.
        if (setterType.isPrimitive() != null) {
            return true;
        }

        String qualifiedName = setterType.getQualifiedSourceName();
        for (Class<?> cls : frameworkHandledTypes) {
            if (qualifiedName.equals(cls.getName())) {
                return true;
            }
        }

        return false;
    }

    private boolean hasSserializeSupport(JType type) {
        if (hasSerializeSupport.contains(type)) {
            return true;
        } else {
            return previousBundle != null
                    && previousBundle.hasSserializeSupport(type);
        }
    }
}